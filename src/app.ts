/** HTTP boundary: authenticated CRUD, schema validation and generated documentation. */
import { timingSafeEqual } from 'node:crypto';
import { randomUUID } from 'node:crypto';
import { OpenAPIHono, createRoute, z } from '@hono/zod-openapi';
import { Scalar } from '@scalar/hono-api-reference';
import { bodyLimit } from 'hono/body-limit';
import { eq } from 'drizzle-orm';
import * as C from './contracts.js';
import { images, objectCleanup } from './schema.js';
import { ApiError } from './errors.js';
import { PostService, wire } from './service.js';
import type { Storage } from './storage.js';
const json = (schema: z.ZodType) => ({ content: { 'application/json': { schema } }, description: 'Success' });
const errors = Object.fromEntries([400,401,404,409,410,413,422,500,503].map(code => [code, { ...json(C.ErrorResponse), description: 'Error' }]));
const security = [{ bearerAuth: [] }];
const params = z.object({ id: C.Id });
/** Creates an app without opening connections, also allowing offline OpenAPI export. */
export function createApp(service: PostService, storage: Storage, token: string) {
  const app = new OpenAPIHono({ defaultHook: (result, c) => { if (!result.success) return c.json({ error: { code: 'VALIDATION_ERROR', message: result.error.issues.map(i => i.path.join('.') + ': ' + i.message).join('; ') } }, 422); } });
  app.openAPIRegistry.registerComponent('securitySchemes', 'bearerAuth', { type: 'http', scheme: 'bearer' });
  app.use('/api/*', async (c, next) => {
    const supplied = Buffer.from(c.req.header('Authorization') ?? ''); const expected = Buffer.from('Bearer ' + token);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return c.json({ error: { code: 'UNAUTHORIZED', message: 'Valid bearer token required' } }, 401);
    await next();
  });
  app.use('/api/*', bodyLimit({ maxSize: 1024 * 1024, onError: c => c.json({ error: { code: 'BODY_TOO_LARGE', message: 'Maximum JSON body is 1 MiB' } }, 413) }));
  app.onError((e, c) => {
    if (e instanceof ApiError) return c.json({ error: { code: e.code, message: e.message } }, e.status);
    if (e instanceof SyntaxError || ('status' in e && e.status === 400)) return c.json({ error: { code: 'INVALID_JSON', message: 'Malformed JSON body' } }, 400);
    console.error('Request failed:', e.name);
    return c.json({ error: { code: 'INTERNAL_ERROR', message: 'Request failed; retry with the same idempotency key' } }, 500);
  });
  app.openapi(createRoute({ method: 'post', path: '/api/v1/posts', operationId: 'createPost', tags: ['Posts'], security,
    request: { headers: C.KeyHeader, body: { required: true, content: { 'application/json': { schema: C.CreatePost } } } },
    responses: { 201: json(C.CreatedPost), ...errors },
  }), async c => c.json(C.CreatedPost.parse(await service.create(c.req.valid('json'), c.req.valid('header')['Idempotency-Key'])), 201));
  app.openapi(createRoute({ method: 'get', path: '/api/v1/posts', operationId: 'listPosts', tags: ['Posts'], security,
    request: { query: z.object({ limit: z.coerce.number().int().min(1).max(100).default(20), cursor: z.string().max(512).optional() }) },
    responses: { 200: json(z.object({ items: z.array(C.Post), next_cursor: z.string().nullable() }).openapi('PostPage')), ...errors },
  }), async c => { const q = c.req.valid('query'); return c.json(await service.list(q.limit, q.cursor), 200); });
  app.openapi(createRoute({ method: 'get', path: '/api/v1/posts/{id}', operationId: 'getPost', tags: ['Posts'], security, request: { params }, responses: { 200: json(C.PostDetail), ...errors } }),
    async c => c.json(C.PostDetail.parse(await service.detail(c.req.valid('param').id)), 200));
  app.openapi(createRoute({ method: 'patch', path: '/api/v1/posts/{id}', operationId: 'renamePost', tags: ['Posts'], security,
    request: { params, body: { required: true, content: { 'application/json': { schema: z.object({ title: z.string().trim().min(1).max(200).nullable() }).strict().openapi('RenamePostRequest') } } } }, responses: { 200: json(C.Post), ...errors } }),
    async c => c.json(C.Post.parse(await service.rename(c.req.valid('param').id, c.req.valid('json').title)), 200));
  app.openapi(createRoute({ method: 'delete', path: '/api/v1/posts/{id}', operationId: 'deletePost', tags: ['Posts'], security, request: { params }, responses: { 204: { description: 'Deleted; repeated deletion succeeds. S3 cleanup is asynchronous.' }, ...errors } }),
    async c => { await service.delete(c.req.valid('param').id); return c.body(null, 204); });
  app.openapi(createRoute({ method: 'post', path: '/api/v1/images/uploads', operationId: 'createImageUpload', tags: ['Images'], security,
    request: { headers: C.KeyHeader, body: { required: true, content: { 'application/json': { schema: C.UploadRequest } } } }, responses: { 201: json(C.UploadResponse), ...errors } }), async c => {
    const input = c.req.valid('json');
    const result = await service.once('image-upload', c.req.valid('header')['Idempotency-Key'], input, async tx => {
      const id = randomUUID();
      const [image] = await tx.insert(images).values({ id, object_key: `images/${id}`, staging_key: `uploads/${id}`, ...input }).returning();
      return { image: C.Image.parse(wire(image)) };
    });
    return c.json({ ...result, upload_url: await storage.uploadUrl(`uploads/${result.image.id}`, input.content_type, input.size_bytes), expires_in: 900 }, 201);
  });
  app.openapi(createRoute({ method: 'post', path: '/api/v1/images/{id}/complete', operationId: 'completeImageUpload', tags: ['Images'], security, request: { params }, responses: { 200: json(C.Image), ...errors } }), async c => {
    const image = await service.db.transaction(async tx => {
      const [row] = await tx.select().from(images).where(eq(images.id, c.req.valid('param').id)).for('update');
      if (!row) throw new ApiError(404, 'IMAGE_NOT_FOUND', 'Image not found');
      if (row.status === 'ready') return row;
      const dimensions = await storage.finalize(row.staging_key, row.object_key, row.content_type, row.size_bytes);
      const [ready] = await tx.update(images).set({ status: 'ready', ...dimensions }).where(eq(images.id, row.id)).returning();
      await tx.insert(objectCleanup).values({ object_key: row.staging_key }).onConflictDoNothing();
      return ready;
    });
    return c.json(C.Image.parse(wire(image)), 200);
  });
  app.openapi(createRoute({ method: 'get', path: '/api/v1/images/{id}/url', operationId: 'getImageUrl', tags: ['Images'], security, request: { params }, responses: { 200: json(z.object({ url: z.string().url(), expires_in: z.number().int() }).openapi('ImageUrl')), ...errors } }), async c => {
    const [image] = await service.db.select().from(images).where(eq(images.id, c.req.valid('param').id));
    if (!image || image.status !== 'ready') throw new ApiError(404, 'IMAGE_NOT_FOUND', 'Ready image not found');
    return c.json({ url: await storage.readUrl(image.object_key), expires_in: 300 }, 200);
  });
  app.get('/health', c => c.json({ status: 'ok' }));
  app.doc('/openapi.json', { openapi: '3.0.3', info: { title: 'MyAPP API', version: '0.1.0' } });
  app.get('/docs', Scalar({ url: '/openapi.json', pageTitle: 'MyAPP API', persistAuth: false }));
  return app;
}
