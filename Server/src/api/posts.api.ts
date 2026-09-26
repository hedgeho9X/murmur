/**
 * 定义并注册帖子 HTTP 接口。负责请求校验和响应序列化；业务规则交由帖子服务处理。
 */
import { UserMessage } from "../modules/messages/messages.contracts.js";
import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";
import * as C from "../modules/posts/posts.contracts.js";
import type { PostsService } from "../modules/posts/posts.service.js";
import { json, errors, security, params } from "./api.shared.js";
/** 创建帖子和首条用户消息的 OpenAPI 契约，要求客户端提供 UUIDv7。 */
const createPostRoute = createRoute({
  method: "post",
  path: "/api/v1/posts",
  operationId: "createPost",
  tags: ["Posts"],
  security,
  request: {
    body: {
      required: true,
      content: { "application/json": { schema: C.CreatePost } },
    },
  },
  responses: { 201: json(C.CreatedPost), ...errors },
});

/** 分页查询帖子的 OpenAPI 契约，定义页大小和游标参数。 */
const listPostsRoute = createRoute({
  method: "get",
  path: "/api/v1/posts",
  operationId: "listPosts",
  tags: ["Posts"],
  security,
  request: {
    query: z.object({
      limit: z.coerce.number().int().min(1).max(100).default(20),
      cursor: z.string().max(512).optional(),
      q: z.string().trim().max(200).optional(),
      images_only: z.enum(["true", "false"]).optional(),
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
});

/** 读取帖子详情的 OpenAPI 契约，返回帖子及其消息。 */
const getPostRoute = createRoute({
  method: "get",
  path: "/api/v1/posts/{id}",
  operationId: "getPost",
  tags: ["Posts"],
  security,
  request: { params },
  responses: { 200: json(C.PostDetail), ...errors },
});

/** 修改帖子标题的 OpenAPI 契约，不接受消息正文更新。 */
const renamePostRoute = createRoute({
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
});

/** 删除帖子的 OpenAPI 契约，重复删除返回成功。 */
const deletePostRoute = createRoute({
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
});

/** 在已有帖子中追加不可变用户消息的 OpenAPI 契约。 */
const appendMessageRoute = createRoute({
  method: "post",
  path: "/api/v1/posts/{id}/messages",
  operationId: "appendMessage",
  tags: ["Posts"],
  security,
  request: {
    params,
    body: {
      required: true,
      content: { "application/json": { schema: C.AppendMessage } },
    },
  },
  responses: { 201: json(UserMessage), ...errors },
});
/**
 * 将帖子路由注册到传入的 Hono 应用，注入帖子服务作为处理依赖。
 * 注册本身不执行查询；收到请求后才调用服务并返回约定的 HTTP 响应。
 */
export function registerPostsApi(app: OpenAPIHono, service: PostsService) {
  /** POST /api/v1/posts/{id}/messages：追加用户记录并返回分配的轮次，不修改历史。 */
  app.openapi(appendMessageRoute, async (c) =>
    c.json(
      UserMessage.parse(
        await service.append(c.req.valid("param").id, c.req.valid("json")),
      ),
      201,
    ),
  );
  /**
   * POST /api/v1/posts
   * 创建帖子及首条用户消息，重复帖子 ID 返回 409。
   */
  app.openapi(createPostRoute, async (c) =>
    c.json(C.CreatedPost.parse(await service.create(c.req.valid("json"))), 201),
  );
  /**
   * GET /api/v1/posts
   * 按游标分页读取帖子列表。
   */
  app.openapi(listPostsRoute, async (c) => {
    const q = c.req.valid("query");
    return c.json(
      await service.list(q.limit, q.cursor, q.q, q.images_only === "true"),
      200,
    );
  });
  /**
   * GET /api/v1/posts/{id}
   * 读取帖子及其按顺序排列的消息。
   */
  app.openapi(getPostRoute, async (c) =>
    c.json(
      C.PostDetail.parse(await service.detail(c.req.valid("param").id)),
      200,
    ),
  );
  /**
   * PATCH /api/v1/posts/{id}
   * 修改帖子标题，不修改已发送消息。
   */
  app.openapi(renamePostRoute, async (c) =>
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
  /**
   * DELETE /api/v1/posts/{id}
   * 级联删除帖子所属数据，并登记对象清理任务。
   */
  app.openapi(deletePostRoute, async (c) => {
    await service.delete(c.req.valid("param").id);
    return c.body(null, 204);
  });
}
