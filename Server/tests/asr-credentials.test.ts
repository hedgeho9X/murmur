/** 验证短期凭据签发的地域绑定和密钥边界，不调用付费上游。 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { issueAsrCredentials } from "../src/modules/asr/asr.credentials.js";
const options = {
  endpoint:
    "wss://workspace.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/inference",
  key: "server-only-key",
  model: "qwen-audio-3.0-asr-flash-streaming",
};
test("temporary credentials use the configured workspace and never return the master key", async () => {
  const expires = Math.floor(Date.now() / 1000) + 900;
  const mock: typeof fetch = async (input, init) => {
    assert.equal(
      new URL(input.toString()).hostname,
      "workspace.ap-southeast-1.maas.aliyuncs.com",
    );
    assert.equal(new URL(input.toString()).pathname, "/api/v1/tokens");
    assert.equal(
      new Headers(init?.headers).get("Authorization"),
      "Bearer server-only-key",
    );
    return Response.json({ token: "st-short-lived", expires_at: expires });
  };
  const value = await issueAsrCredentials(options, mock);
  assert.equal(value.token, "st-short-lived");
  assert.equal(value.expires_at, expires);
  assert.ok(!JSON.stringify(value).includes(options.key));
});
test("invalid or permanent upstream credentials fail closed", async () => {
  for (const body of [
    { token: options.key, expires_at: 9999999999 },
    { token: "st-expired", expires_at: 1 },
  ]) {
    await assert.rejects(
      issueAsrCredentials(options, async () => Response.json(body)),
      /Unable to issue/,
    );
  }
  await assert.rejects(
    issueAsrCredentials({ ...options, endpoint: "wss://unrelated.example/ws" }),
    /supported ASR/,
  );
});
