/** 首次启动先完成数据库迁移，等待 S3 就绪，再初始化私有 bucket；失败阻止 API 启动。 */
import { setTimeout } from "node:timers/promises";
import { config } from "../src/config.js";
await import("./migrate.js");
const endpoint = config().S3_ENDPOINT;
let ready = false;
for (let attempt = 0; attempt < 30; attempt++) {
  try {
    const response = await fetch(new URL("/health", endpoint), {
      signal: AbortSignal.timeout(3000),
    });
    if (response.ok) {
      ready = true;
      break;
    }
  } catch {
    /* 对象存储进程可能仍在初始化，短暂网络失败在启动窗口内重试。 */
  }
  await setTimeout(2000);
}
if (!ready) throw new Error("Object storage readiness timed out");
await import("./storage-init.js");
