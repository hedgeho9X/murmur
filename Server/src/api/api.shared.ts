/** Shared OpenAPI response and authentication declarations. */
import { z } from "@hono/zod-openapi";
import * as C from "../common/contracts.js";
export const json = (schema: z.ZodType) => ({
  content: { "application/json": { schema } },
  description: "Success",
});
export const errors = Object.fromEntries(
  [400, 401, 404, 409, 410, 413, 422, 500, 503].map((code) => [
    code,
    { ...json(C.ErrorResponse), description: "Error" },
  ]),
);
export const security = [{ bearerAuth: [] }];
export const params = z.object({ id: C.Id });
