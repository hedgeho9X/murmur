/**
 * 启动 Hono 服务并定期执行对象清理；收到退出信号时停止监听并关闭连接池。
 * 模块装配与 HTTP 契约分别交由 modules 和 api 目录维护。
 */
import { WebSocketServer } from "ws";
import { serve } from "@hono/node-server";
import { cleanup } from "./modules/images/images.cleanup.js";
import { config } from "./config.js";
import { connect } from "./database/database.client.js";
import { Storage } from "./modules/images/images.storage.js";
import { createServices } from "./modules/modules.js";
import { ImagesRepository } from "./modules/images/images.repository.js";
import { createApp } from "./api/app.api.js";
const c = config();
const { db, pool } = connect(c.DATABASE_URL);
const storage = new Storage(c);
let cleaning = false;
const timer = setInterval(async () => {
  if (cleaning) return;
  cleaning = true;
  try {
    const result = await cleanup(new ImagesRepository(db), storage);
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
    fetch: createApp(createServices(db, storage), c.API_TOKEN, {
      endpoint: c.ASR_ENDPOINT,
      key: c.ASR_API_KEY ?? "",
      model: c.ASR_MODEL,
    }).fetch,
    websocket: {
      server: new WebSocketServer({ noServer: true, maxPayload: 64000 }),
    },
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
