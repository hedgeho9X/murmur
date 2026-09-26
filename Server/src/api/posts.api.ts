/** Posts HTTP routes: validate input, call services and serialize responses. */
import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";
import * as C from "../modules/posts/posts.contracts.js";
import { KeyHeader } from "../common/contracts.js";
import type { PostsService } from "../modules/posts/posts.service.js";
import { json, errors, security, params } from "./api.shared.js";
/** Registers post operations without owning database or storage access. */
export function registerPostsApi(app: OpenAPIHono, service: PostsService) {
  app.openapi(
    createRoute({
      method: "post",
      path: "/api/v1/posts",
      operationId: "createPost",
      tags: ["Posts"],
      security,
      request: {
        headers: KeyHeader,
        body: {
          required: true,
          content: { "application/json": { schema: C.CreatePost } },
        },
      },
      responses: { 201: json(C.CreatedPost), ...errors },
    }),
    async (c) =>
      c.json(
        C.CreatedPost.parse(
          await service.create(
            c.req.valid("json"),
            c.req.valid("header")["Idempotency-Key"],
          ),
        ),
        201,
      ),
  );
  app.openapi(
    createRoute({
      method: "get",
      path: "/api/v1/posts",
      operationId: "listPosts",
      tags: ["Posts"],
      security,
      request: {
        query: z.object({
          limit: z.coerce.number().int().min(1).max(100).default(20),
          cursor: z.string().max(512).optional(),
        }),
      },
      responses: {
        200: json(
          z
            .object({
              items: z.array(C.Post),
              next_cursor: z.string().nullable(),
            })
            .openapi("PostPage"),
        ),
        ...errors,
      },
    }),
    async (c) => {
      const q = c.req.valid("query");
      return c.json(await service.list(q.limit, q.cursor), 200);
    },
  );
  app.openapi(
    createRoute({
      method: "get",
      path: "/api/v1/posts/{id}",
      operationId: "getPost",
      tags: ["Posts"],
      security,
      request: { params },
      responses: { 200: json(C.PostDetail), ...errors },
    }),
    async (c) =>
      c.json(
        C.PostDetail.parse(await service.detail(c.req.valid("param").id)),
        200,
      ),
  );
  app.openapi(
    createRoute({
      method: "patch",
      path: "/api/v1/posts/{id}",
      operationId: "renamePost",
      tags: ["Posts"],
      security,
      request: {
        params,
        body: {
          required: true,
          content: {
            "application/json": {
              schema: z
                .object({ title: z.string().trim().min(1).max(200).nullable() })
                .strict()
                .openapi("RenamePostRequest"),
            },
          },
        },
      },
      responses: { 200: json(C.Post), ...errors },
    }),
    async (c) =>
      c.json(
        C.Post.parse(
          await service.rename(
            c.req.valid("param").id,
            c.req.valid("json").title,
          ),
        ),
        200,
      ),
  );
  app.openapi(
    createRoute({
      method: "delete",
      path: "/api/v1/posts/{id}",
      operationId: "deletePost",
      tags: ["Posts"],
      security,
      request: { params },
      responses: {
        204: {
          description:
            "Deleted; repeated deletion succeeds. S3 cleanup is asynchronous.",
        },
        ...errors,
      },
    }),
    async (c) => {
      await service.delete(c.req.valid("param").id);
      return c.body(null, 204);
    },
  );
}
