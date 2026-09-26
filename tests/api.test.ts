/** Integration acceptance against isolated PostgreSQL database and real local S3. */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { eq, sql } from 'drizzle-orm';
import sharp from 'sharp';
import { config } from '../src/config.js';
import { connect } from '../src/db.js';
import { createApp } from '../src/app.js';
import { PostService } from '../src/service.js';
import { Storage } from '../src/storage.js';
import { cleanup } from '../src/cleanup.js';
import { posts, messages, images, objectCleanup } from '../src/schema.js';
import { Message } from '../src/contracts.js';
const c = config(); const admin = connect(c.DATABASE_URL);
const schema = 'test_' + randomUUID().replaceAll('-', '');
const url = new URL(c.DATABASE_URL); url.pathname = '/' + schema;
const { db, pool } = connect(url.toString()); const storage = new Storage(c);
const app = createApp(new PostService(db), storage, c.API_TOKEN);
/** Sends a validated-style HTTP request through the actual Hono router. */
function request(path: string, method = 'GET', body?: unknown, key = randomUUID()) {
  return app.request(path, { method, headers: { Authorization: 'Bearer ' + c.API_TOKEN, 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: body === undefined ? undefined : JSON.stringify(body) });
}
/** Reads an expected response while showing safe error bodies on assertion failure. */
async function json(response: Response, status: number) { const body = await response.json(); assert.equal(response.status, status, JSON.stringify(body)); return body; }
const content = { parts: [{ type: 'text', text: '记录一个想法' }] };
before(async () => { await admin.pool.query(`CREATE DATABASE ${schema}`); await migrate(db, { migrationsFolder: 'migrations' }); });
after(async () => {
  try { await db.delete(posts); await db.delete(images); await cleanup(db, storage); }
  finally { await pool.end(); await admin.pool.query(`DROP DATABASE ${schema}`); await admin.pool.end(); }
});
test('authentication, validation and immutable user role', async () => {
  assert.equal((await app.request('/api/v1/posts')).status, 401);
  await json(await request('/api/v1/posts', 'POST', { content: { parts: [{ type: 'text', text: '  ' }] } }), 422);
  await json(await request('/api/v1/posts', 'POST', { content, role: 'assistant' }), 422);
  assert.equal((await app.request('/api/v1/posts', { method: 'POST', headers: { Authorization: 'Bearer ' + c.API_TOKEN, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() }, body: '{' })).status, 400);
});
test('concurrent identical creates persist exactly one post and message', async () => {
  const key = randomUUID(); const body = { content };
  const results = await Promise.all(Array.from({ length: 8 }, async () => json(await request('/api/v1/posts', 'POST', body, key), 201)));
  for (const result of results) assert.deepEqual(result, results[0]);
  const detail = await json(await request('/api/v1/posts/' + results[0].post.id), 200);
  assert.equal(detail.messages.length, 1); assert.equal(detail.messages[0].turn_id, results[0].message.turn_id);
  await json(await request('/api/v1/posts', 'POST', { title: 'changed', content }, key), 409);
});
test('failed image binding rolls back post, message and idempotency receipt', async () => {
  const [{ n: beforeCount }] = await db.select({ n: sql<number>`count(*)::int` }).from(posts);
  const key = randomUUID(); const bad = { content: { parts: [{ type: 'image', image_id: randomUUID() }] } };
  await json(await request('/api/v1/posts', 'POST', bad, key), 409);
  const [{ n: afterCount }] = await db.select({ n: sql<number>`count(*)::int` }).from(posts);
  assert.equal(afterCount, beforeCount);
  await json(await request('/api/v1/posts', 'POST', { content }, key), 201);
});
test('database rejects message UPDATE; assistant/tool messages retain same turn', async () => {
  const result = await json(await request('/api/v1/posts', 'POST', { content }), 201);
  await assert.rejects(db.update(messages).set({ content: {} }).where(eq(messages.id, result.message.id)));
  const shared = { post_id: result.post.id, turn_id: result.message.turn_id };
  await db.insert(messages).values([
    { ...shared, id: randomUUID(), role: 'assistant', content: { parts: [{ type: 'tool_call', id: 'call_1', name: 'search', arguments: { q: 'test' } }], finish_reason: 'tool_call' } },
    { ...shared, id: randomUUID(), role: 'tool', tool_call_id: 'call_1', content: { parts: [{ type: 'json', data: { found: true } }], is_error: false } },
    { ...shared, id: randomUUID(), role: 'assistant', content: { parts: [{ type: 'text', text: 'partial' }], finish_reason: 'interrupted' } },
  ]);
  const detail = await json(await request('/api/v1/posts/' + result.post.id), 200);
  assert.equal(detail.messages.length, 4);
  detail.messages.forEach((m: unknown) => Message.parse(m));
  assert.equal((await request('/api/v1/messages/' + result.message.id, 'PATCH', {})).status, 404);
});
test('rename changes only post, pagination has no duplicates, delete is idempotent', async () => {
  const key = randomUUID(); const created = await json(await request('/api/v1/posts', 'POST', { content }, key), 201);
  await json(await request('/api/v1/posts/' + created.post.id, 'PATCH', { title: 'new title' }), 200);
  const detail = await json(await request('/api/v1/posts/' + created.post.id), 200);
  assert.equal(detail.post.title, 'new title'); assert.deepEqual(detail.messages[0], created.message);
  let cursor = ''; const seen = new Set();
  do { const page = await json(await request('/api/v1/posts?limit=2' + (cursor ? '&cursor=' + cursor : '')), 200); for (const p of page.items) { assert.ok(!seen.has(p.id)); seen.add(p.id); } cursor = page.next_cursor; } while (cursor);
  await json(await request('/api/v1/posts?cursor=invalid'), 400);
  assert.equal((await request('/api/v1/posts/' + created.post.id, 'DELETE')).status, 204);
  assert.equal((await request('/api/v1/posts/' + created.post.id, 'DELETE')).status, 204);
  await json(await request('/api/v1/posts/' + created.post.id), 404);
  await json(await request('/api/v1/posts', 'POST', { content }, key), 410);
});
/** Uploads a real byte buffer using the API-issued signed PUT URL. */
async function upload(bytes: Buffer, type = 'image/png') {
  const result = await json(await request('/api/v1/images/uploads', 'POST', { content_type: type, size_bytes: bytes.length }), 201);
  const put = await fetch(result.upload_url, { method: 'PUT', headers: { 'Content-Type': type }, body: new Uint8Array(bytes) });
  assert.equal(put.status, 200, await put.text()); return result;
}
test('real S3 upload, content verification, immutable final image and cascade cleanup', async () => {
  const bytes = await sharp({ create: { width: 8, height: 6, channels: 3, background: '#cc4422' } }).png().toBuffer();
  const u = await upload(bytes);
  const ready = await json(await request('/api/v1/images/' + u.image.id + '/complete', 'POST'), 200);
  assert.equal(ready.width, 8); assert.equal(ready.height, 6);
  const read = await json(await request('/api/v1/images/' + u.image.id + '/url'), 200);
  assert.deepEqual(Buffer.from(await (await fetch(read.url)).arrayBuffer()), bytes);
  // Reusing a still-valid upload URL can only overwrite staging, not the published image.
  assert.equal((await fetch(u.upload_url, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: Buffer.alloc(bytes.length) })).status, 200);
  await json(await request('/api/v1/images/' + u.image.id + '/complete', 'POST'), 200);
  assert.deepEqual(Buffer.from(await (await fetch(read.url)).arrayBuffer()), bytes);
  const result = await json(await request('/api/v1/posts', 'POST', { content: { parts: [{ type: 'image', image_id: u.image.id }] } }), 201);
  await json(await request('/api/v1/posts', 'POST', { content: { parts: [{ type: 'image', image_id: u.image.id }] } }), 409);
  assert.equal((await request('/api/v1/posts/' + result.post.id, 'DELETE')).status, 204);
  assert.equal((await db.select().from(images).where(eq(images.id, u.image.id))).length, 0);
  assert.equal((await db.select().from(messages).where(eq(messages.post_id, result.post.id))).length, 0);
  const remove = storage.remove.bind(storage);
  storage.remove = async () => { throw Error('simulated storage outage'); };
  try { const failed = await cleanup(db, storage); assert.ok(failed.failed > 0); } finally { storage.remove = remove; }
  assert.ok((await db.select().from(objectCleanup)).length > 0);
  await cleanup(db, storage);
  assert.equal((await fetch(read.url)).status, 404);
});
test('corrupt image and missing upload are rejected', async () => {
  const u = await upload(Buffer.from('not an actual image'));
  await json(await request('/api/v1/images/' + u.image.id + '/complete', 'POST'), 422);
  const pending = await json(await request('/api/v1/images/uploads', 'POST', { content_type: 'image/png', size_bytes: 100 }), 201);
  await json(await request('/api/v1/images/' + pending.image.id + '/complete', 'POST'), 422);
});
test('OpenAPI declares stable operations and bearer security; Scalar is served', async () => {
  const spec = await json(await app.request('/openapi.json'), 200);
  assert.equal(spec.paths['/api/v1/posts'].post.operationId, 'createPost');
  assert.ok(spec.paths['/api/v1/posts'].post.security.length);
  assert.ok(spec.components.schemas.ToolContent.properties.is_error);
  assert.ok(!spec.components.schemas.UserMessage.properties.updated_at);
  assert.match(await (await app.request('/docs')).text(), /scalar/i);
});
