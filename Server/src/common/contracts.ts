/**
 * 定义跨模块复用的标识符、错误响应和幂等请求头契约，不包含具体业务数据。
 */
import { z } from "@hono/zod-openapi";
export const Id = z.string().uuid();
export const ErrorResponse = z
  .object({ error: z.object({ code: z.string(), message: z.string() }) })
  .openapi("ErrorResponse");
export const KeyHeader = z.object({
  "Idempotency-Key": z
    .string()
    .min(8)
    .max(128)
    .regex(/^[A-Za-z0-9_-]+$/),
});
