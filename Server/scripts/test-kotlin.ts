/**
 * 运行 Kotlin 客户端契约测试，通过环境传递本地 API 凭据。
 * 测试需要已启动的后端；强制重新执行 HTTP 测试，避免 Gradle 缓存掩盖服务变化。
 */
import { spawnSync } from "node:child_process";
const result = spawnSync("sh", ["gradlew", "test", "--rerun", "--no-daemon"], {
  cwd: "../Client/api",
  stdio: "inherit",
  env: process.env,
});
process.exit(result.status ?? 1);
