/**
 * 装配鉴权、请求体限制、统一错误处理、业务路由和 Scalar 文档。
 * 只负责 HTTP 应用组合，不创建数据库连接或执行后台任务。
 */
import { registerAsrApi } from "./asr.api.js";
import type { AsrOptions } from "../modules/asr/asr.session.js";
import { timingSafeEqual } from "node:crypto";
import { OpenAPIHono } from "@hono/zod-openapi";
import { Scalar } from "@scalar/hono-api-reference";
import { bodyLimit } from "hono/body-limit";
import { ApiError } from "../common/errors.js";
import type { Services } from "../modules/modules.js";
import { registerPostsApi } from "./posts.api.js";
import { registerImagesApi } from "./images.api.js";
/**
 * 根据模块服务和访问令牌创建 Hono 应用，返回可处理请求及导出 OpenAPI 的实例。
 * 注册阶段不调用服务；离线导出文档时允许注入不执行的服务占位对象。
 */
export function createApp(services: Services, token: string, asr?: AsrOptions) {
  const app = new OpenAPIHono({
    defaultHook: (result, c) => {
      if (!result.success)
        return c.json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: result.error.issues
                .map((i) => i.path.join(".") + ": " + i.message)
                .join("; "),
            },
          },
          422,
        );
    },
  });
  app.openAPIRegistry.registerComponent("securitySchemes", "bearerAuth", {
    type: "http",
    scheme: "bearer",
  });
  app.use("/api/*", async (c, next) => {
    const supplied = Buffer.from(c.req.header("Authorization") ?? "");
    const expected = Buffer.from("Bearer " + token);
    if (
      supplied.length !== expected.length ||
      !timingSafeEqual(supplied, expected)
    )
      return c.json(
        {
          error: {
            code: "UNAUTHORIZED",
            message: "Valid bearer token required",
          },
        },
        401,
      );
    await next();
  });
  app.use(
    "/api/*",
    bodyLimit({
      maxSize: 1024 * 1024,
      onError: (c) =>
        c.json(
          {
            error: {
              code: "BODY_TOO_LARGE",
              message: "Maximum JSON body is 1 MiB",
            },
          },
          413,
        ),
    }),
  );
  app.onError((e, c) => {
    if (e instanceof ApiError)
      return c.json({ error: { code: e.code, message: e.message } }, e.status);
    if (e instanceof SyntaxError || ("status" in e && e.status === 400))
      return c.json(
        { error: { code: "INVALID_JSON", message: "Malformed JSON body" } },
        400,
      );
    console.error("Request failed:", e.name);
    return c.json(
      {
        error: {
          code: "INTERNAL_ERROR",
          message: "Request failed",
        },
      },
      500,
    );
  });
  registerAsrApi(app, asr);
  registerPostsApi(app, services.posts);
  registerImagesApi(app, services.images);
  app.get("/health", (c) => c.json({ status: "ok" }));
  app.doc("/openapi.json", {
    openapi: "3.0.3",
    info: { title: "Murmur API", version: "0.1.0" },
  });
  app.get(
    "/docs",
    Scalar({
      url: "/openapi.json",
      pageTitle: "Murmur API",
      persistAuth: false,
    }),
  );
  return app;
}
