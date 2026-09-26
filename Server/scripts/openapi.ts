/**
 * 从已注册的 HTTP 契约导出 OpenAPI 文件。
 * 仅写入 openapi/openapi.json，不读取密钥、不连接数据库或执行业务服务。
 */
import { writeFileSync } from "node:fs";
import { createApp } from "../src/api/app.api.js";
import type { Services } from "../src/modules/modules.js";
const app = createApp({} as Services, "offline-contract-only");
writeFileSync(
  "openapi/openapi.json",
  JSON.stringify(
    app.getOpenAPIDocument({
      openapi: "3.0.3",
      info: { title: "Murmur API", version: "0.1.0" },
    }),
    null,
    2,
  ) + "\n",
);
