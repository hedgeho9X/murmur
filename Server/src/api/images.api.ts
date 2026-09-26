/**
 * 定义并注册图片上传与读取接口。负责 HTTP 契约；不直接访问数据库或 S3。
 */
import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";
import * as C from "../modules/images/images.contracts.js";
import type { ImagesService } from "../modules/images/images.service.js";
import { json, errors, security, params } from "./api.shared.js";
/** 申请图片上传的 OpenAPI 契约，定义类型、大小。 */
const createImageUploadRoute = createRoute({
  method: "post",
  path: "/api/v1/images/uploads",
  operationId: "createImageUpload",
  tags: ["Images"],
  security,
  request: {
    body: {
      required: true,
      content: { "application/json": { schema: C.UploadRequest } },
    },
  },
  responses: { 201: json(C.UploadResponse), ...errors },
});

/** 完成图片上传的 OpenAPI 契约，返回验证后的图片元数据。 */
const completeImageUploadRoute = createRoute({
  method: "post",
  path: "/api/v1/images/{id}/complete",
  operationId: "completeImageUpload",
  tags: ["Images"],
  security,
  request: { params },
  responses: { 200: json(C.Image), ...errors },
});

/** 获取图片签名地址的 OpenAPI 契约，返回地址与有效期。 */
const getImageUrlRoute = createRoute({
  method: "get",
  path: "/api/v1/images/{id}/url",
  operationId: "getImageUrl",
  tags: ["Images"],
  security,
  request: { params },
  responses: {
    200: json(
      z
        .object({ url: z.string().url(), expires_in: z.number().int() })
        .openapi("ImageUrl"),
    ),
    ...errors,
  },
});

/**
 * 将图片路由注册到传入的 Hono 应用，通过图片服务处理上传和读取。
 * 注册不产生存储操作，签名地址和上传校验结果在请求处理时生成。
 */
export function registerImagesApi(app: OpenAPIHono, service: ImagesService) {
  /**
   * POST /api/v1/images/uploads
   * 申请图片记录及临时上传签名地址。
   */
  app.openapi(createImageUploadRoute, async (c) =>
    c.json(await service.createUpload(c.req.valid("json")), 201),
  );
  /**
   * POST /api/v1/images/{id}/complete
   * 校验已上传图片并返回发布后的元数据。
   */
  app.openapi(completeImageUploadRoute, async (c) =>
    c.json(await service.complete(c.req.valid("param").id), 200),
  );
  /**
   * GET /api/v1/images/{id}/url
   * 为已就绪图片生成短期读取地址。
   */
  app.openapi(getImageUrlRoute, async (c) =>
    c.json(await service.readUrl(c.req.valid("param").id), 200),
  );
}
