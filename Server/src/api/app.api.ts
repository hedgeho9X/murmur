/** Composes HTTP middleware, module routes and API documentation. */
import { timingSafeEqual } from "node:crypto";
import { OpenAPIHono } from "@hono/zod-openapi";
import { Scalar } from "@scalar/hono-api-reference";
import { bodyLimit } from "hono/body-limit";
import { ApiError } from "../common/errors.js";
import type { Services } from "../modules/modules.js";
import { registerPostsApi } from "./posts.api.js";
import { registerImagesApi } from "./images.api.js";
/** Registers routes offline as well as with live injected services. */
export function createApp(services: Services, token: string) {
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
          message: "Request failed; retry with the same idempotency key",
        },
      },
      500,
    );
  });
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
