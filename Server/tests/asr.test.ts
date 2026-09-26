/** 使用真实本地 WebSocket 上游验证认证、片段替换、停止收尾与异常清理，不调用付费服务。 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { serve } from "@hono/node-server";
import WebSocket, { WebSocketServer } from "ws";
import { createApp } from "../src/api/app.api.js";
import type { Services } from "../src/modules/modules.js";
/** 启动可控上游与实际 HTTP 服务，返回测试连接及资源关闭函数。 */
async function setup() {
  const upstream = new WebSocketServer({ port: 0 });
  await once(upstream, "listening");
  let audioBytes = 0;
  let closed = 0;
  upstream.on("connection", (ws) => {
    ws.on("close", () => closed++);
    ws.on("message", (raw, binary) => {
      if (binary) {
        audioBytes +=
          raw instanceof ArrayBuffer
            ? raw.byteLength
            : Array.isArray(raw)
              ? raw.reduce((n, b) => n + b.length, 0)
              : raw.length;
        return;
      }
      const msg = JSON.parse(raw.toString());
      if (msg.header.action === "run-task")
        ws.send(JSON.stringify({ header: { event: "task-started" } }));
      else if (msg.header.action === "finish-task") {
        for (const [text, final] of [
          ["你", false],
          ["你好", false],
          ["你好，世界。", true],
        ] as const)
          ws.send(
            JSON.stringify({
              header: { event: "result-generated" },
              payload: {
                output: {
                  sentence: { sentence_id: 1, text, sentence_end: final },
                },
              },
            }),
          );
        ws.send(JSON.stringify({ header: { event: "task-finished" } }));
      }
    });
  });
  const app = createApp({} as Services, "test-secret", {
    endpoint: `ws://127.0.0.1:${(upstream.address() as { port: number }).port}`,
    key: "private-test",
    model: "test",
  });
  const server = serve({
    fetch: app.fetch,
    port: 0,
    hostname: "127.0.0.1",
    websocket: {
      server: new WebSocketServer({ noServer: true, maxPayload: 64000 }),
    },
  });
  if (!server.listening) await once(server, "listening");
  const url = `ws://127.0.0.1:${(server.address() as { port: number }).port}/api/v1/asr/stream`;
  return {
    app,
    url,
    stats: () => ({ audioBytes, closed }),
    stop: async () => {
      for (const c of upstream.clients) c.terminate();
      await new Promise<void>((r) => server.close(() => r()));
      await new Promise<void>((r) => upstream.close(() => r()));
    },
  };
}
test("ASR sends ready, forwards bytes and preserves final tail before completed", async () => {
  const s = await setup();
  try {
    assert.equal((await s.app.request("/api/v1/asr/stream")).status, 401);
    const ws = new WebSocket(s.url, {
      headers: { Authorization: "Bearer test-secret" },
    });
    const events: any[] = [];
    ws.on("message", (raw) => {
      const event = JSON.parse(raw.toString());
      events.push(event);
      if (event.type === "ready") {
        ws.send(Buffer.alloc(3200));
        ws.send(JSON.stringify({ type: "finish" }));
      }
    });
    await once(ws, "open");
    ws.send(
      JSON.stringify({
        type: "start",
        format: "pcm_s16le",
        sample_rate: 16000,
        channels: 1,
      }),
    );
    await once(ws, "close");
    assert.equal(events[0].type, "ready");
    assert.equal(events.at(-1).type, "completed");
    assert.equal(events.at(-1).text, "你好，世界。");
    assert.deepEqual(
      events.filter((e) => e.type === "transcript").map((e) => e.segment_id),
      ["1", "1", "1"],
    );
    assert.equal(s.stats().audioBytes, 3200);
  } finally {
    await s.stop();
  }
});
test("ASR rejects invalid controls without inventing successful completion", async () => {
  const s = await setup();
  try {
    const ws = new WebSocket(s.url, {
      headers: { Authorization: "Bearer test-secret" },
    });
    const events: any[] = [];
    ws.on("message", (d) => events.push(JSON.parse(d.toString())));
    await once(ws, "open");
    ws.send(JSON.stringify({ type: "finish" }));
    await once(ws, "close");
    assert.equal(events[0].code, "INVALID_STATE");
    assert.ok(!events.some((e) => e.type === "completed"));
  } finally {
    await s.stop();
  }
});
