/** Runs generated-client contract tests with the local API credentials kept in the environment. */
import { spawnSync } from "node:child_process";
const result = spawnSync("sh", ["gradlew", "test", "--no-daemon"], {
  cwd: "clients/kotlin",
  stdio: "inherit",
  env: process.env,
});
process.exit(result.status ?? 1);
