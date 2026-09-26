/**
 * 定义跨模块复用的标识符、错误响应契约，不包含具体业务数据。
 */
import { z } from "@hono/zod-openapi";
export const Id = z.string().uuid();
export const ErrorResponse = z
  .object({ error: z.object({ code: z.string(), message: z.string() }) })
  .openapi("ErrorResponse");
