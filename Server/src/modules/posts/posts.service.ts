/**
 * 校验帖子业务输入，协调事务创建、图片绑定与列表响应。
 * 所有数据库访问交由仓储，消息正文在创建后不允许修改。
 */
import { ApiError } from "../../common/errors.js";
import { wire } from "../../common/serialization.js";
import type { NewPost, NewMessage } from "./posts.contracts.js";
import type { PostsRepository, PostCursor } from "./posts.repository.js";
import type { ImagesRepository } from "../images/images.repository.js";
/**
 * 提供帖子创建、读取、改名和删除能力，协调跨模块仓储而不直接编写 SQL。
 */
export class PostsService {
  /**
   * 注入帖子和图片仓储，要求它们使用同一数据库以共享创建事务。
   */
  constructor(
    private readonly posts: PostsRepository,
    private readonly images: ImagesRepository,
  ) {}
  /**
   * 校验正文与附件，按客户端 UUIDv7 原子创建帖子、首条消息与图片归属。
   * 返回创建快照；空内容、重复图片或不可用图片会拒绝请求，不留下部分写入。
   */
  async create(input: NewPost) {
    if (
      !input.content.parts.some(
        (p) => p.type === "image" || p.text.trim().length,
      )
    )
      throw new ApiError(422, "EMPTY_CONTENT", "Text or an image is required");
    const ids = input.content.parts.flatMap((p) =>
      p.type === "image" ? [p.image_id] : [],
    );
    if (new Set(ids).size !== ids.length)
      throw new ApiError(
        422,
        "DUPLICATE_IMAGE",
        "Each image may appear only once",
      );
    const result = await this.posts.create(input, async (tx, messageId) => {
      // 按固定顺序获取图片行锁，避免并发绑定重叠图片时发生死锁。
      for (const id of [...ids].sort()) {
        if (!(await this.images.attach(tx, id, messageId)))
          throw new ApiError(
            409,
            "IMAGE_UNAVAILABLE",
            "Image is missing, unfinished or already attached",
          );
      }
    });
    if (!result)
      throw new ApiError(409, "POST_ALREADY_EXISTS", "Post ID already exists");
    return wire(result);
  }

  /** 向已有帖子追加记录，保留原历史；图片绑定失败时整体回滚。 */
  async append(postId: string, input: NewMessage) {
    if (!input.content.parts.some((p) => p.type === "image" || p.text.trim()))
      throw new ApiError(422, "EMPTY_CONTENT", "Text or image required");
    const ids = input.content.parts.flatMap((p) =>
      p.type === "image" ? [p.image_id] : [],
    );
    if (new Set(ids).size !== ids.length)
      throw new ApiError(422, "DUPLICATE_IMAGE", "Duplicate image");
    const result = await this.posts.append(postId, input, async (tx, id) => {
      for (const image of [...ids].sort())
        if (!(await this.images.attach(tx, image, id)))
          throw new ApiError(409, "IMAGE_UNAVAILABLE", "Image unavailable");
    });
    if (result.kind === "missing")
      throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    if (result.kind === "duplicate")
      throw new ApiError(
        409,
        "MESSAGE_ALREADY_EXISTS",
        "Message ID already exists",
      );
    return wire(result.message);
  }
  /**
   * 按 ID 返回帖子和消息的 JSON 快照；不存在时抛出可公开的 404 异常。
   */
  async detail(id: string) {
    const result = await this.posts.detail(id);
    if (!result) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    return wire(result);
  }
  /**
   * 校验并解码游标，读取一页帖子并生成下一页游标，返回 JSON 响应数据。
   * 非法游标返回 400；本操作不修改帖子。
   */
  async list(
    limit: number,
    cursor?: string,
    query?: string,
    imagesOnly = false,
  ) {
    let boundary: PostCursor | undefined;
    if (cursor) {
      try {
        const value = JSON.parse(Buffer.from(cursor, "base64url").toString());
        if (
          typeof value.t !== "string" ||
          !/^\d{4}-\d\d-\d\dT/.test(value.t) ||
          !Number.isFinite(Date.parse(value.t)) ||
          typeof value.id !== "string" ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
            value.id,
          )
        )
          throw Error();
        boundary = value;
      } catch {
        throw new ApiError(400, "INVALID_CURSOR", "Invalid pagination cursor");
      }
    }
    const rows = await this.posts.list(limit, boundary, query, imagesOnly);
    const items = rows.slice(0, limit);
    const last = items.at(-1);
    return wire({
      items,
      next_cursor:
        rows.length > limit && last
          ? Buffer.from(
              JSON.stringify({ t: last.created_at.toISOString(), id: last.id }),
            ).toString("base64url")
          : null,
    });
  }
  /**
   * 按 ID 修改帖子标题并返回 JSON 快照；不存在时返回 404，消息历史保持不变。
   */
  async rename(id: string, title: string | null) {
    const post = await this.posts.rename(id, title);
    if (!post) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    return wire(post);
  }
  /**
   * 删除指定帖子及所属数据，重复调用不会重新创建数据；对象删除通过持久队列完成。
   */
  delete(id: string) {
    return this.posts.delete(id);
  }
}
