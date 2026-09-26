/** 从导出的 OpenAPI 生成客户端路径常量，供 WebSocket 传输层复用，避免手写接口路径。 */
import { readFileSync, writeFileSync } from "node:fs";
const spec = JSON.parse(readFileSync("openapi/openapi.json", "utf8"));
const lines = [
  "/** 由 OpenAPI 自动生成的接口路径，不手工修改。 */",
  "package cn.hedgeho9.murmur.api",
  "",
  "object ApiPaths {",
];
for (const [path, item] of Object.entries(spec.paths))
  for (const operation of Object.values(item as Record<string, any>)) {
    if (operation.operationId) {
      const name = operation.operationId
        .replace(/([a-z])([A-Z])/g, "$1_$2")
        .toUpperCase();
      lines.push(`    const val ${name} = ${JSON.stringify(path)}`);
    }
  }
lines.push("}", "");
writeFileSync(
  "../Client/api/src/main/kotlin/cn/hedgeho9/murmur/api/ApiPaths.kt",
  lines.join("\n"),
);
