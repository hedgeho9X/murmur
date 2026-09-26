/**
 * 使用独立临时 PostgreSQL 数据库与真实本地 S3 验证 API 行为。
 * 测试仅创建和清理自己的数据，不复用日常帖子；运行账号需要创建数据库权限。
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { v7 as uuidv7 } from "uuid";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { eq, sql } from "drizzle-orm";
import sharp from "sharp";
import { config } from "../src/config.js";
import { connect } from "../src/database/database.client.js";
import { createApp } from "../src/api/app.api.js";
import { createServices } from "../src/modules/modules.js";
import { ImagesRepository } from "../src/modules/images/images.repository.js";
import { Storage } from "../src/modules/images/images.storage.js";
import { cleanup } from "../src/modules/images/images.cleanup.js";
import {
  posts,
  messages,
  images,
  objectCleanup,
} from "../src/database/schemas/index.js";
import { Message } from "../src/modules/messages/messages.contracts.js";
const c = config();
const admin = connect(c.DATABASE_URL);
const schema = "test_" + randomUUID().replaceAll("-", "");
const url = new URL(c.DATABASE_URL);
url.pathname = "/" + schema;
const { db, pool } = connect(url.toString());
const storage = new Storage(c);
const app = createApp(createServices(db, storage), c.API_TOKEN);
/**
 * 构造带认证和客户端帖子 ID的请求并交给实际 Hono 路由，返回 HTTP 响应。
 * 有请求体时进行 JSON 编码；测试产生的业务写入由测试生命周期清理。
 */
function request(path: string, method = "GET", body?: unknown, id = uuidv7()) {
  return app.request(path, {
    method,
    headers: {
      Authorization: "Bearer " + c.API_TOKEN,
      "Content-Type": "application/json",
    },
    body:
      body === undefined
        ? undefined
        : JSON.stringify(
            path === "/api/v1/posts" && method === "POST"
              ? { id, ...(body as object) }
              : body,
          ),
  });
}
/**
 * 解析响应 JSON 并断言状态码，返回解析结果供后续断言使用。
 * 失败时显示业务响应，不读取或输出环境密钥。
 */
async function json(response: Response, status: number) {
  const body = await response.json();
  assert.equal(response.status, status, JSON.stringify(body));
  return body;
}
const content = { parts: [{ type: "text", text: "记录一个想法" }] };
before(async () => {
  await admin.pool.query(`CREATE DATABASE ${schema}`);
  await migrate(db, { migrationsFolder: "migrations" });
});
after(async () => {
  try {
    await db.delete(posts);
    await db.delete(images);
    await cleanup(new ImagesRepository(db), storage);
  } finally {
    await pool.end();
    await admin.pool.query(`DROP DATABASE ${schema}`);
    await admin.pool.end();
  }
});
test("authentication, validation and immutable user role", async () => {
  assert.equal((await app.request("/api/v1/posts")).status, 401);
  await json(
    await request("/api/v1/posts", "POST", {
      content: { parts: [{ type: "text", text: "  " }] },
    }),
    422,
  );
  await json(
    await request("/api/v1/posts", "POST", { content, role: "assistant" }),
    422,
  );
  assert.equal(
    (
      await app.request("/api/v1/posts", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + c.API_TOKEN,
          "Content-Type": "application/json",
        },
        body: "{",
      })
    ).status,
    400,
  );
});
test("post creation requires a client UUIDv7 and no receipt table remains", async () => {
  const missing = await app.request("/api/v1/posts", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + c.API_TOKEN,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ content }),
  });
  await json(missing, 422);
  await json(
    await request("/api/v1/posts", "POST", { id: randomUUID(), content }),
    422,
  );
  const table = await pool.query(
    "select to_regclass('public.idempotency') as name",
  );
  assert.equal(table.rows[0].name, null);
});
test("concurrent duplicate UUIDv7 creates return one success and conflicts", async () => {
  const id = uuidv7();
  const body = { content };
  const responses = await Promise.all(
    Array.from({ length: 8 }, () => request("/api/v1/posts", "POST", body, id)),
  );
  assert.equal(responses.filter((r) => r.status === 201).length, 1);
  assert.equal(responses.filter((r) => r.status === 409).length, 7);
  const result = await json(
    responses.find((r) => r.status === 201)!,
    201,
  );
  assert.equal(result.post.id, id);
  const detail = await json(await request("/api/v1/posts/" + id), 200);
  assert.equal(detail.messages.length, 1);
  assert.equal(detail.messages[0].turn_id, result.message.turn_id);
  const conflict = await json(
    await request("/api/v1/posts", "POST", { title: "changed", content }, id),
    409,
  );
  assert.equal(conflict.error.code, "POST_ALREADY_EXISTS");
  assert.deepEqual(
    (await json(await request("/api/v1/posts/" + id), 200)).messages,
    detail.messages,
  );
});
test("failed image binding rolls back post and message; same UUID can retry", async () => {
  const [{ n: beforeCount }] = await db
    .select({ n: sql<number>`count(*)::int` })
    .from(posts);
  const id = uuidv7();
  const bad = {
    content: { parts: [{ type: "image", image_id: randomUUID() }] },
  };
  await json(await request("/api/v1/posts", "POST", bad, id), 409);
  const [{ n: afterCount }] = await db
    .select({ n: sql<number>`count(*)::int` })
    .from(posts);
  assert.equal(afterCount, beforeCount);
  await json(await request("/api/v1/posts", "POST", { content }, id), 201);
});
test("database rejects message UPDATE; assistant/tool messages retain same turn", async () => {
  const result = await json(
    await request("/api/v1/posts", "POST", { content }),
    201,
  );
  await assert.rejects(
    db
      .update(messages)
      .set({ content: {} })
      .where(eq(messages.id, result.message.id)),
  );
  const shared = { post_id: result.post.id, turn_id: result.message.turn_id };
  await db.insert(messages).values([
    {
      ...shared,
      id: randomUUID(),
      role: "assistant",
      content: {
        parts: [
          {
            type: "tool_call",
            id: "call_1",
            name: "search",
            arguments: { q: "test" },
          },
        ],
        finish_reason: "tool_call",
      },
    },
    {
      ...shared,
      id: randomUUID(),
      role: "tool",
      tool_call_id: "call_1",
      content: {
        parts: [{ type: "json", data: { found: true } }],
        is_error: false,
      },
    },
    {
      ...shared,
      id: randomUUID(),
      role: "assistant",
      content: {
        parts: [{ type: "text", text: "partial" }],
        finish_reason: "interrupted",
      },
    },
  ]);
  const detail = await json(
    await request("/api/v1/posts/" + result.post.id),
    200,
  );
  assert.equal(detail.messages.length, 4);
  detail.messages.forEach((m: unknown) => Message.parse(m));
  assert.equal(
    (await request("/api/v1/messages/" + result.message.id, "PATCH", {}))
      .status,
    404,
  );
});
test("rename changes only post, pagination has no duplicates, delete is idempotent", async () => {
  const id = uuidv7();
  const created = await json(
    await request("/api/v1/posts", "POST", { content }, id),
    201,
  );
  await json(
    await request("/api/v1/posts/" + created.post.id, "PATCH", {
      title: "new title",
    }),
    200,
  );
  const detail = await json(
    await request("/api/v1/posts/" + created.post.id),
    200,
  );
  assert.equal(detail.post.title, "new title");
  assert.deepEqual(detail.messages[0], created.message);
  let cursor = "";
  const seen = new Set();
  do {
    const page = await json(
      await request(
        "/api/v1/posts?limit=2" + (cursor ? "&cursor=" + cursor : ""),
      ),
      200,
    );
    for (const p of page.items) {
      assert.ok(!seen.has(p.id));
      seen.add(p.id);
    }
    cursor = page.next_cursor;
  } while (cursor);
  await json(await request("/api/v1/posts?cursor=invalid"), 400);
  assert.equal(
    (await request("/api/v1/posts/" + created.post.id, "DELETE")).status,
    204,
  );
  assert.equal(
    (await request("/api/v1/posts/" + created.post.id, "DELETE")).status,
    204,
  );
  await json(await request("/api/v1/posts/" + created.post.id), 404);
  await json(await request("/api/v1/posts", "POST", { content }, id), 201);
});
/**
 * 通过 API 申请上传地址并 PUT 指定图片字节，返回上传申请结果。
 * 会创建测试图片记录和临时 S3 对象，后续由测试清理。
 */
async function upload(bytes: Buffer, type = "image/png") {
  const result = await json(
    await request("/api/v1/images/uploads", "POST", {
      content_type: type,
      size_bytes: bytes.length,
    }),
    201,
  );
  const put = await fetch(result.upload_url, {
    method: "PUT",
    headers: { "Content-Type": type },
    body: new Uint8Array(bytes),
  });
  assert.equal(put.status, 200, await put.text());
  return result;
}
test("real S3 upload, content verification, immutable final image and cascade cleanup", async () => {
  const bytes = await sharp({
    create: { width: 8, height: 6, channels: 3, background: "#cc4422" },
  })
    .png()
    .toBuffer();
  const u = await upload(bytes);
  const ready = await json(
    await request("/api/v1/images/" + u.image.id + "/complete", "POST"),
    200,
  );
  assert.equal(ready.width, 8);
  assert.equal(ready.height, 6);
  const read = await json(
    await request("/api/v1/images/" + u.image.id + "/url"),
    200,
  );
  assert.deepEqual(
    Buffer.from(await (await fetch(read.url)).arrayBuffer()),
    bytes,
  );
  // 旧签名仍可能有效，重复上传只能影响临时对象，不能改变已发布的图片。
  assert.equal(
    (
      await fetch(u.upload_url, {
        method: "PUT",
        headers: { "Content-Type": "image/png" },
        body: Buffer.alloc(bytes.length),
      })
    ).status,
    200,
  );
  await json(
    await request("/api/v1/images/" + u.image.id + "/complete", "POST"),
    200,
  );
  assert.deepEqual(
    Buffer.from(await (await fetch(read.url)).arrayBuffer()),
    bytes,
  );
  const result = await json(
    await request("/api/v1/posts", "POST", {
      content: { parts: [{ type: "image", image_id: u.image.id }] },
    }),
    201,
  );
  await json(
    await request("/api/v1/posts", "POST", {
      content: { parts: [{ type: "image", image_id: u.image.id }] },
    }),
    409,
  );
  assert.equal(
    (await request("/api/v1/posts/" + result.post.id, "DELETE")).status,
    204,
  );
  assert.equal(
    (await db.select().from(images).where(eq(images.id, u.image.id))).length,
    0,
  );
  assert.equal(
    (
      await db
        .select()
        .from(messages)
        .where(eq(messages.post_id, result.post.id))
    ).length,
    0,
  );
  const remove = storage.remove.bind(storage);
  storage.remove = async () => {
    throw Error("simulated storage outage");
  };
  try {
    const failed = await cleanup(new ImagesRepository(db), storage);
    assert.ok(failed.failed > 0);
  } finally {
    storage.remove = remove;
  }
  assert.ok((await db.select().from(objectCleanup)).length > 0);
  await cleanup(new ImagesRepository(db), storage);
  assert.equal((await fetch(read.url)).status, 404);
});
test("corrupt image and missing upload are rejected", async () => {
  const u = await upload(Buffer.from("not an actual image"));
  await json(
    await request("/api/v1/images/" + u.image.id + "/complete", "POST"),
    422,
  );
  const pending = await json(
    await request("/api/v1/images/uploads", "POST", {
      content_type: "image/png",
      size_bytes: 100,
    }),
    201,
  );
  await json(
    await request("/api/v1/images/" + pending.image.id + "/complete", "POST"),
    422,
  );
});
test("OpenAPI declares stable operations and bearer security; Scalar is served", async () => {
  const spec = await json(await app.request("/openapi.json"), 200);
  assert.equal(spec.paths["/api/v1/posts"].post.operationId, "createPost");
  assert.ok(spec.paths["/api/v1/posts"].post.security.length);
  assert.ok(spec.components.schemas.ToolContent.properties.is_error);
  assert.ok(!spec.components.schemas.UserMessage.properties.updated_at);
  assert.match(await (await app.request("/docs")).text(), /scalar/i);
});

test("append keeps original messages immutable and rejects duplicate message IDs", async () => {
  const created = await json(
    await request("/api/v1/posts", "POST", { content }),
    201,
  );
  const id = uuidv7();
  const path = `/api/v1/posts/${created.post.id}/messages`;
  const appended = await json(
    await request(path, "POST", { id, content }),
    201,
  );
  assert.notEqual(appended.turn_id, created.message.turn_id);
  await json(await request(path, "POST", { id, content }), 409);
  const detail = await json(
    await request(`/api/v1/posts/${created.post.id}`),
    200,
  );
  assert.equal(detail.messages.length, 2);
  assert.deepEqual(
    detail.messages.find((m: any) => m.id === created.message.id),
    created.message,
  );
});
