/**
 * 提供 API 共用的响应描述、鉴权声明和路径参数契约，不处理业务请求。
 */
import { z } from "@hono/zod-openapi";
import * as C from "../common/contracts.js";
/** 根据响应 Schema 构造 OpenAPI JSON 响应描述；不执行运行时序列化。 */
export const json = (schema: z.ZodType) => ({
  content: { "application/json": { schema } },
  description: "Success",
});
/** 各业务路由复用的错误响应结构，实际状态由异常处理器决定。 */
export const errors = Object.fromEntries(
  [400, 401, 404, 409, 410, 413, 422, 500, 503].map((code) => [
    code,
    { ...json(C.ErrorResponse), description: "Error" },
  ]),
);
/** 声明业务接口使用 Bearer 认证；实际令牌校验由应用中间件执行。 */
export const security = [{ bearerAuth: [] }];
/** 校验路径中的资源 UUID，不执行资源存在性查询。 */
export const params = z.object({ id: C.Id });
