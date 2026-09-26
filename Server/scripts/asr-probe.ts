/** 使用指定的 PCM16 WAV 文件按实时速度验证本地 ASR 转发，不输出认证信息。 */
import { readFileSync } from "node:fs";
import WebSocket from "ws";
import { once } from "node:events";
import { config } from "../src/config.js";
const c = config();
const file = readFileSync(process.argv[2]);
let data = Buffer.alloc(0),
  rate = 0,
  channels = 0,
  bits = 0;
for (let offset = 12; offset + 8 <= file.length;) {
  const size = file.readUInt32LE(offset + 4),
    name = file.toString("ascii", offset, offset + 4);
  if (name === "fmt ") {
    channels = file.readUInt16LE(offset + 10);
    rate = file.readUInt32LE(offset + 12);
    bits = file.readUInt16LE(offset + 22);
  }
  if (name === "data") data = file.subarray(offset + 8, offset + 8 + size);
  offset += 8 + size + (size % 2);
}
if (rate !== 16000 || channels !== 1 || bits !== 16 || !data.length)
  throw Error("Expected 16 kHz mono PCM16 WAV");
const ws = new WebSocket(`ws://127.0.0.1:${c.PORT}/api/v1/asr/stream`, {
  headers: { Authorization: `Bearer ${c.API_TOKEN}` },
});
const events: any[] = [];
let start = 0,
  finish = 0,
  first = 0;
const deadline = setTimeout(() => {
  ws.terminate();
  process.exitCode = 1;
}, 30000);
ws.on("message", async (raw) => {
  const e = JSON.parse(raw.toString());
  events.push(e);
  if (e.type === "error") {
    console.log({ type: e.type, code: e.code });
    process.exitCode = 1;
  }
  if (e.type === "ready") {
    start = Date.now();
    for (
      let i = 0;
      i < data.length && ws.readyState === WebSocket.OPEN;
      i += 3200
    ) {
      ws.send(data.subarray(i, i + 3200));
      await new Promise((r) => setTimeout(r, 100));
    }
    finish = Date.now();
    if (ws.readyState === WebSocket.OPEN)
      ws.send(JSON.stringify({ type: "finish" }));
  }
  if (e.type === "transcript" && e.text && !first) first = Date.now();
  if (e.type === "completed")
    console.log({
      audio_seconds: data.length / 32000,
      first_text_ms: first - start,
      finish_to_complete_ms: Date.now() - finish,
      text: e.text,
    });
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
clearTimeout(deadline);
if (!events.some((e) => e.type === "completed")) process.exitCode = 1;
