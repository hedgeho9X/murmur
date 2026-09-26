/** Starts the local Hono server and closes database connections on shutdown. */
import { serve } from "@hono/node-server";
import { cleanup } from "./cleanup.js";
import { config } from "./config.js";
import { connect } from "./db.js";
import { Storage } from "./storage.js";
import { PostService } from "./service.js";
import { createApp } from "./app.js";
const c = config();
const { db, pool } = connect(c.DATABASE_URL);
const storage = new Storage(c);
let cleaning = false;
const timer = setInterval(async () => {
  if (cleaning) return;
  cleaning = true;
  try {
    const result = await cleanup(db, storage);
    if (result.failed)
      console.error("Object cleanup will retry:", result.failed);
  } catch {
    console.error("Cleanup pass failed; will retry");
  } finally {
    cleaning = false;
  }
}, 60_000);
timer.unref();
const server = serve(
  {
    fetch: createApp(new PostService(db), storage, c.API_TOKEN).fetch,
    hostname: c.HOST,
    port: c.PORT,
  },
  () => console.log(`API http://${c.HOST}:${c.PORT} — docs /docs`),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.once(signal, () => {
    clearInterval(timer);
    server.close(() => {
      void pool.end().then(() => process.exit(0));
    });
  });
