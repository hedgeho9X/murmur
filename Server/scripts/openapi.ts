/** Exports the contract offline; the app factory never invokes these runtime dependencies. */
import { writeFileSync } from "node:fs";
import { createApp } from "../src/app.js";
import type { PostService } from "../src/service.js";
import type { Storage } from "../src/storage.js";
const app = createApp(
  undefined as unknown as PostService,
  undefined as unknown as Storage,
  "offline-contract-only",
);
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
