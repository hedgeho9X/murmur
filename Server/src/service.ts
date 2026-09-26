/** Post operations own database transactions, idempotency and attachment ownership. */
import { randomUUID, createHash } from "node:crypto";
import { eq, and, sql, desc } from "drizzle-orm";
import type { DB, Tx } from "./db.js";
import { posts, messages, images, idempotency } from "./schema.js";
import type { NewPost } from "./contracts.js";
import { ApiError } from "./errors.js";
/** Produces deterministic hashes independent of JSON object key ordering. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value !== null && typeof value === "object")
    return (
      "{" +
      Object.keys(value)
        .sort()
        .map(
          (k) =>
            JSON.stringify(k) +
            ":" +
            canonical((value as Record<string, unknown>)[k]),
        )
        .join(",") +
      "}"
    );
  return JSON.stringify(value);
}
/** Converts database timestamps into the exact wire representation stored for replay. */
export function wire<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
/** Provides single-owner post persistence; callers validate the public request first. */
export class PostService {
  /** Shares the configured database pool with the API. */
  constructor(readonly db: DB) {}
  /** Serializes equal keys inside the same transaction as the effect and replay receipt. */
  async once<T>(
    scope: string,
    key: string,
    input: unknown,
    action: (tx: Tx) => Promise<T>,
  ): Promise<T> {
    const hash = createHash("sha256").update(canonical(input)).digest("hex");
    return this.db.transaction(async (tx) => {
      await tx.execute(
        sql`select pg_advisory_xact_lock(hashtextextended(${scope + ":" + key}, 0))`,
      );
      const [saved] = await tx
        .select()
        .from(idempotency)
        .where(and(eq(idempotency.scope, scope), eq(idempotency.key, key)));
      if (saved) {
        if ((saved.response as { deleted?: boolean }).deleted)
          throw new ApiError(
            410,
            "RESOURCE_DELETED",
            "Original resource was deleted; this request cannot recreate it",
          );
        if (saved.request_hash !== hash)
          throw new ApiError(
            409,
            "IDEMPOTENCY_CONFLICT",
            "Key already used for different content",
          );
        const response = saved.response as {
          post?: { id: string };
          image?: { id: string };
        };
        const exists = response.post
          ? await tx
              .select({ id: posts.id })
              .from(posts)
              .where(eq(posts.id, response.post.id))
          : response.image
            ? await tx
                .select({ id: images.id })
                .from(images)
                .where(eq(images.id, response.image.id))
            : [true];
        if (!exists.length)
          throw new ApiError(
            410,
            "RESOURCE_DELETED",
            "Original resource was deleted; this request cannot recreate it",
          );
        return saved.response as T;
      }
      const result = wire(await action(tx));
      await tx
        .insert(idempotency)
        .values({ scope, key, request_hash: hash, response: result });
      return result;
    });
  }
  /** Creates one post, its first immutable user message and image bindings atomically. */
  async create(input: NewPost, key: string) {
    if (
      !input.content.parts.some(
        (p) => p.type === "image" || p.text.trim().length,
      )
    )
      throw new ApiError(422, "EMPTY_CONTENT", "Text or an image is required");
    const imageIds = input.content.parts.flatMap((p) =>
      p.type === "image" ? [p.image_id] : [],
    );
    if (new Set(imageIds).size !== imageIds.length)
      throw new ApiError(
        422,
        "DUPLICATE_IMAGE",
        "Each image may appear only once",
      );
    return this.once(
      "create-post",
      key,
      { ...input, title: input.title ?? null },
      async (tx) => {
        const [post] = await tx
          .insert(posts)
          .values({ id: randomUUID(), title: input.title ?? null })
          .returning();
        const [message] = await tx
          .insert(messages)
          .values({
            id: randomUUID(),
            post_id: post.id,
            turn_id: randomUUID(),
            role: "user",
            content: input.content,
          })
          .returning();
        // Stable lock order prevents deadlocks when concurrent posts reference overlapping images.
        for (const id of [...imageIds].sort()) {
          const attached = await tx
            .update(images)
            .set({ message_id: message.id })
            .where(
              and(
                eq(images.id, id),
                eq(images.status, "ready"),
                sql`${images.message_id} is null`,
              ),
            )
            .returning();
          if (!attached.length)
            throw new ApiError(
              409,
              "IMAGE_UNAVAILABLE",
              "Image is missing, unfinished or already attached",
            );
        }
        return { post, message };
      },
    );
  }
  /** Loads an existing post and chronological messages without rewriting stored content. */
  async detail(id: string) {
    return this.db.transaction(
      async (tx) => {
        const [post] = await tx.select().from(posts).where(eq(posts.id, id));
        if (!post) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
        const rows = await tx
          .select()
          .from(messages)
          .where(eq(messages.post_id, id))
          .orderBy(messages.created_at, messages.id);
        return wire({ post, messages: rows });
      },
      { isolationLevel: "repeatable read", accessMode: "read only" },
    );
  }
  /** Returns stable created-time cursor pages; the cursor is opaque to clients. */
  async list(limit: number, cursor?: string) {
    let boundary;
    if (cursor) {
      try {
        const v = JSON.parse(Buffer.from(cursor, "base64url").toString());
        if (
          typeof v.t !== "string" ||
          !/^\d{4}-\d\d-\d\dT/.test(v.t) ||
          !Number.isFinite(Date.parse(v.t)) ||
          typeof v.id !== "string" ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
            v.id,
          )
        )
          throw Error();
        boundary = sql`(${posts.created_at}, ${posts.id}) < (${v.t}::timestamptz, ${v.id}::uuid)`;
      } catch {
        throw new ApiError(400, "INVALID_CURSOR", "Invalid pagination cursor");
      }
    }
    const rows = await this.db
      .select()
      .from(posts)
      .where(boundary)
      .orderBy(desc(posts.created_at), desc(posts.id))
      .limit(limit + 1);
    const items = rows.slice(0, limit);
    const last = items.at(-1);
    return wire({
      items,
      next_cursor:
        rows.length > limit && last
          ? Buffer.from(
              JSON.stringify({ t: last.created_at.toISOString(), id: last.id }),
            ).toString("base64url")
          : null,
    });
  }
  /** Changes only the post title; sent messages are immutable. */
  async rename(id: string, title: string | null) {
    const [post] = await this.db
      .update(posts)
      .set({ title, updated_at: new Date() })
      .where(eq(posts.id, id))
      .returning();
    if (!post) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    return wire(post);
  }
  /** Cascades relational deletion; the database trigger queues S3 keys in the same transaction. */
  async delete(id: string) {
    await this.db.delete(posts).where(eq(posts.id, id));
  }
}
