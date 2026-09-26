/** PostgreSQL operations for posts and their first immutable message. */
import { randomUUID } from "node:crypto";
import { eq, sql, desc } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { posts, messages } from "../../database/database.schema.js";
import type { NewPost } from "./posts.contracts.js";
/** A decoded list cursor, independent of its HTTP representation. */
export type PostCursor = { t: string; id: string };
/** Owns post SQL; business validation and response shaping belong to the service. */
export class PostsRepository {
  /** Uses the shared pool for reads and the supplied transaction for atomic creation. */
  constructor(private readonly db: DB) {}
  /** Inserts a post and user message into an existing idempotent transaction. */
  async create(tx: Tx, input: NewPost) {
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
    return { post, message };
  }
  /** Reads post and messages from one consistent snapshot; returns undefined when absent. */
  async detail(id: string) {
    return this.db.transaction(
      async (tx) => {
        const [post] = await tx.select().from(posts).where(eq(posts.id, id));
        if (!post) return undefined;
        const rows = await tx
          .select()
          .from(messages)
          .where(eq(messages.post_id, id))
          .orderBy(messages.created_at, messages.id);
        return { post, messages: rows };
      },
      { isolationLevel: "repeatable read", accessMode: "read only" },
    );
  }
  /** Fetches one extra row to determine whether another cursor page exists. */
  list(limit: number, cursor?: PostCursor) {
    const boundary = cursor
      ? sql`(${posts.created_at}, ${posts.id}) < (${cursor.t}::timestamptz, ${cursor.id}::uuid)`
      : undefined;
    return this.db
      .select()
      .from(posts)
      .where(boundary)
      .orderBy(desc(posts.created_at), desc(posts.id))
      .limit(limit + 1);
  }
  /** Updates only post metadata, never sent messages. */
  async rename(id: string, title: string | null) {
    const [post] = await this.db
      .update(posts)
      .set({ title, updated_at: new Date() })
      .where(eq(posts.id, id))
      .returning();
    return post;
  }
  /** Cascades deletion; database triggers enqueue object cleanup and redact receipts. */
  async delete(id: string) {
    await this.db.delete(posts).where(eq(posts.id, id));
  }
}
