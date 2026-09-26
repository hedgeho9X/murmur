/** Exports the contract offline; the app factory never invokes these runtime dependencies. */
import { writeFileSync } from "node:fs";
import { createApp } from "../src/api/app.api.js";
import type { Services } from "../src/modules/modules.js";
const app = createApp({} as Services, "offline-contract-only");
writeFileSync(
  "openapi/openapi.json",
  JSON.stringify(
    app.getOpenAPIDocument({
      openapi: "3.0.3",
      info: { title: "Murmur API", version: "0.1.0" },
    }),
    null,
    2,
  ) + "\n",
);
