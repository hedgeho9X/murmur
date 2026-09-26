/** Image HTTP routes; services own upload verification and storage operations. */
import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";
import * as C from "../modules/images/images.contracts.js";
import { KeyHeader } from "../common/contracts.js";
import type { ImagesService } from "../modules/images/images.service.js";
import { json, errors, security, params } from "./api.shared.js";
/** Registers upload and read operations without direct database access. */
export function registerImagesApi(app: OpenAPIHono, service: ImagesService) {
  app.openapi(
    createRoute({
      method: "post",
      path: "/api/v1/images/uploads",
      operationId: "createImageUpload",
      tags: ["Images"],
      security,
      request: {
        headers: KeyHeader,
        body: {
          required: true,
          content: { "application/json": { schema: C.UploadRequest } },
        },
      },
      responses: { 201: json(C.UploadResponse), ...errors },
    }),
    async (c) =>
      c.json(
        await service.createUpload(
          c.req.valid("json"),
          c.req.valid("header")["Idempotency-Key"],
        ),
        201,
      ),
  );
  app.openapi(
    createRoute({
      method: "post",
      path: "/api/v1/images/{id}/complete",
      operationId: "completeImageUpload",
      tags: ["Images"],
      security,
      request: { params },
      responses: { 200: json(C.Image), ...errors },
    }),
    async (c) => c.json(await service.complete(c.req.valid("param").id), 200),
  );
  app.openapi(
    createRoute({
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
    }),
    async (c) => c.json(await service.readUrl(c.req.valid("param").id), 200),
  );
}
