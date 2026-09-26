/**
 * 装配业务模块及其存储依赖，确保各仓储复用同一数据库连接池。
 * 不注册 HTTP 路由，不在装配阶段执行查询。
 */
import type { DB } from "../database/database.client.js";
import type { Storage } from "./images/images.storage.js";
import { PostsRepository } from "./posts/posts.repository.js";
import { PostsService } from "./posts/posts.service.js";
import { ImagesRepository } from "./images/images.repository.js";
import { ImagesService } from "./images/images.service.js";
/**
 * 供 HTTP 层调用的模块服务集合，不暴露数据库连接和仓储对象。
 */
export type Services = { posts: PostsService; images: ImagesService };
/**
 * 接收数据库客户端与 S3 适配器，构造帖子、图片服务。
 * 返回服务集合；此过程不发送查询或存储请求。
 */
export function createServices(db: DB, storage: Storage): Services {
  const images = new ImagesRepository(db);
  return {
    posts: new PostsService(new PostsRepository(db), images),
    images: new ImagesService(images, storage),
  };
}
