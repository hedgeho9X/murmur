/** Shared identifiers and HTTP error/idempotency contracts. */
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
