/**
 * 执行独立于帖子删除事务的对象清理，协调图片仓储与 S3 删除。
 * 本文件只运行单次批处理，调度周期由服务入口或命令脚本决定。
 */
import type { ImagesRepository } from "./images.repository.js";
import type { Storage } from "./images.storage.js";
/**
 * 清除一天前未认领的图片记录，并处理最多 100 个对象删除任务。
 * 成功后移除队列项，失败时记录次数并保留重试，返回成功和失败数量。
 */
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
