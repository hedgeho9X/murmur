/** Image ownership, upload state and durable object-cleanup SQL. */
import { randomUUID } from "node:crypto";
import { and, eq, isNull, lt, sql } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { images, objectCleanup } from "../../database/database.schema.js";
/** A persisted image row passed to byte verification under a transaction lock. */
export type ImageRow = typeof images.$inferSelect;
/** Owns image SQL and the lock protecting upload completion and post attachment. */
export class ImagesRepository {
  /** Uses the shared pool; cross-module writes accept an existing transaction. */
  constructor(private readonly db: DB) {}
  /** Allocates private staging/final keys as part of an idempotent upload request. */
  async create(tx: Tx, input: { content_type: string; size_bytes: number }) {
    const id = randomUUID();
    const [image] = await tx
      .insert(images)
      .values({
        id,
        object_key: `images/${id}`,
        staging_key: `uploads/${id}`,
        ...input,
      })
      .returning();
    return image;
  }
  /** Atomically binds only a ready, unclaimed image to an immutable message. */
  async attach(tx: Tx, id: string, messageId: string) {
    const rows = await tx
      .update(images)
      .set({ message_id: messageId })
      .where(
        and(
          eq(images.id, id),
          eq(images.status, "ready"),
          isNull(images.message_id),
        ),
      )
      .returning();
    return rows.length > 0;
  }
  /** Finds metadata without exposing storage internals through the public contract. */
  async find(id: string) {
    const [image] = await this.db
      .select()
      .from(images)
      .where(eq(images.id, id));
    return image;
  }
  /** Holds the row lock while the service verifies bytes, then publishes ready metadata. */
  async complete(
    id: string,
    verify: (image: ImageRow) => Promise<{ width: number; height: number }>,
  ) {
    return this.db.transaction(async (tx) => {
      const [row] = await tx
        .select()
        .from(images)
        .where(eq(images.id, id))
        .for("update");
      if (!row || row.status === "ready") return row;
      const dimensions = await verify(row);
      const [ready] = await tx
        .update(images)
        .set({ status: "ready", ...dimensions })
        .where(eq(images.id, row.id))
        .returning();
      await tx
        .insert(objectCleanup)
        .values({ object_key: row.staging_key })
        .onConflictDoNothing();
      return ready;
    });
  }
  /** Removes abandoned metadata, letting the deletion trigger queue its objects. */
  async expireUnattached(before: Date) {
    await this.db
      .delete(images)
      .where(and(isNull(images.message_id), lt(images.created_at, before)));
  }
  /** Reads a bounded, oldest-first cleanup batch. */
  pendingCleanup() {
    return this.db
      .select()
      .from(objectCleanup)
      .orderBy(objectCleanup.created_at)
      .limit(100);
  }
  /** Removes a queue item only after its object was successfully deleted. */
  async acknowledgeCleanup(key: string) {
    await this.db
      .delete(objectCleanup)
      .where(eq(objectCleanup.object_key, key));
  }
  /** Keeps failed items queued and increments their diagnostic attempt count. */
  async failCleanup(key: string) {
    await this.db
      .update(objectCleanup)
      .set({ attempts: sql`${objectCleanup.attempts} + 1` })
      .where(eq(objectCleanup.object_key, key));
  }
}
