/** Retries object cleanup independently of the already committed post deletion. */
import type { ImagesRepository } from "./images.repository.js";
import type { Storage } from "./images.storage.js";
/** Expires unclaimed uploads and drains one bounded batch, retaining failed items. */
export async function cleanup(images: ImagesRepository, storage: Storage) {
  await images.expireUnattached(new Date(Date.now() - 86400_000));
  const jobs = await images.pendingCleanup();
  let removed = 0;
  let failed = 0;
  for (const job of jobs) {
    try {
      await storage.remove(job.object_key);
      await images.acknowledgeCleanup(job.object_key);
      removed++;
    } catch {
      await images.failCleanup(job.object_key);
      failed++;
    }
  }
  return { removed, failed };
}
