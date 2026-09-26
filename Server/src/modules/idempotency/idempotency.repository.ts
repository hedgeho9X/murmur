/** Transactional idempotency receipts shared by post creation and uploads. */
import { createHash } from "node:crypto";
import { eq, and, sql } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { posts, images, idempotency } from "../../database/database.schema.js";
import { ApiError } from "../../common/errors.js";
import { wire } from "../../common/serialization.js";
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
/** Serializes duplicate operations together with their effects. */
export class IdempotencyRepository {
  /** Uses the same pool as the repositories participating in the transaction. */
  constructor(private readonly db: DB) {}
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
}
