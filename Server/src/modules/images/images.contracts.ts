/** Image upload and metadata contracts; object keys remain server-owned. */
import { z } from "@hono/zod-openapi";
import { Id } from "../../common/contracts.js";
export const Image = z
  .object({
    id: Id,
    content_type: z.string(),
    size_bytes: z.number().int(),
    width: z.number().int().nullable(),
    height: z.number().int().nullable(),
    status: z.enum(["pending", "ready"]),
    created_at: z.string().datetime(),
  })
  .openapi("Image");
export const UploadRequest = z
  .object({
    content_type: z.enum(["image/jpeg", "image/png", "image/webp"]),
    size_bytes: z
      .number()
      .int()
      .min(1)
      .max(10 * 1024 * 1024),
  })
  .strict()
  .openapi("UploadRequest");
export const UploadResponse = z
  .object({
    image: Image,
    upload_url: z.string().url(),
    expires_in: z.number().int(),
  })
  .openapi("UploadResponse");
