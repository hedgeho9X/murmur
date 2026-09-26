/** Post policies and orchestration, with all SQL delegated to repositories. */
import { ApiError } from "../../common/errors.js";
import { wire } from "../../common/serialization.js";
import type { NewPost } from "./posts.contracts.js";
import type { PostsRepository, PostCursor } from "./posts.repository.js";
import type { ImagesRepository } from "../images/images.repository.js";
import type { IdempotencyRepository } from "../idempotency/idempotency.repository.js";
/** Coordinates post creation, attachment ownership and cursor serialization. */
export class PostsService {
  /** Injects repositories that share a database and transaction contract. */
  constructor(
    private readonly posts: PostsRepository,
    private readonly images: ImagesRepository,
    private readonly idempotency: IdempotencyRepository,
  ) {}
  /** Validates content and commits post, message, attachments and receipt together. */
  async create(input: NewPost, key: string) {
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
    return this.idempotency.once(
      "create-post",
      key,
      { ...input, title: input.title ?? null },
      async (tx) => {
        const result = await this.posts.create(tx, input);
        // A stable lock order avoids deadlocks for overlapping concurrent attachments.
        for (const id of [...ids].sort()) {
          if (!(await this.images.attach(tx, id, result.message.id)))
            throw new ApiError(
              409,
              "IMAGE_UNAVAILABLE",
              "Image is missing, unfinished or already attached",
            );
        }
        return result;
      },
    );
  }
  /** Returns a complete post view or an explicit not-found error. */
  async detail(id: string) {
    const result = await this.posts.detail(id);
    if (!result) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    return wire(result);
  }
  /** Validates opaque cursors and builds the next page token. */
  async list(limit: number, cursor?: string) {
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
    const rows = await this.posts.list(limit, boundary);
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
  /** Applies the post-title update policy while leaving message history untouched. */
  async rename(id: string, title: string | null) {
    const post = await this.posts.rename(id, title);
    if (!post) throw new ApiError(404, "POST_NOT_FOUND", "Post not found");
    return wire(post);
  }
  /** Repeated deletion is safe and does not recreate any data. */
  delete(id: string) {
    return this.posts.delete(id);
  }
}
