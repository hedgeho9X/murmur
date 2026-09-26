/** Durable object deletion retries are independent of already committed post deletion. */
import { and, eq, isNull, lt, sql } from 'drizzle-orm';
import type { DB } from './db.js';
import type { Storage } from './storage.js';
import { images, objectCleanup } from './schema.js';
/** Expires abandoned image records and drains a bounded queue; failures retain their keys. */
export async function cleanup(db: DB, storage: Storage) {
  await db.delete(images).where(and(isNull(images.message_id), lt(images.created_at, new Date(Date.now() - 86400_000))));
  const jobs = await db.select().from(objectCleanup).orderBy(objectCleanup.created_at).limit(100);
  let removed = 0; let failed = 0;
  for (const job of jobs) {
    try { await storage.remove(job.object_key); await db.delete(objectCleanup).where(eq(objectCleanup.object_key, job.object_key)); removed++; }
    catch { await db.update(objectCleanup).set({ attempts: sql`${objectCleanup.attempts} + 1` }).where(eq(objectCleanup.object_key, job.object_key)); failed++; }
  }
  return { removed, failed };
}
