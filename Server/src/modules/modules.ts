/** Composition root for module services sharing one transaction-capable database pool. */
import type { DB } from "../database/database.client.js";
import type { Storage } from "./images/images.storage.js";
import { PostsRepository } from "./posts/posts.repository.js";
import { PostsService } from "./posts/posts.service.js";
import { ImagesRepository } from "./images/images.repository.js";
import { ImagesService } from "./images/images.service.js";
import { IdempotencyRepository } from "./idempotency/idempotency.repository.js";
/** Public application capabilities supplied to route registration. */
export type Services = { posts: PostsService; images: ImagesService };
/** Constructs concrete repositories/services without executing queries. */
export function createServices(db: DB, storage: Storage): Services {
  const idempotency = new IdempotencyRepository(db);
  const images = new ImagesRepository(db);
  return {
    posts: new PostsService(new PostsRepository(db), images, idempotency),
    images: new ImagesService(images, storage, idempotency),
  };
}
