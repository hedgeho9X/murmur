/**
 * 协调上传申请、图片校验、最终对象发布和签名读取。
 * 不处理 HTTP 或 SQL，分别通过契约、仓储和 S3 适配器完成。
 */
import { Image } from "./images.contracts.js";
import { wire } from "../../common/serialization.js";
import { ApiError } from "../../common/errors.js";
import type { ImagesRepository } from "./images.repository.js";
import type { Storage } from "./images.storage.js";
import type { IdempotencyRepository } from "../idempotency/idempotency.repository.js";
/**
 * 提供图片生命周期操作，确保未验证的临时上传不能作为已发布图片读取。
 */
export class ImagesService {
  /**
   * 注入图片仓储、S3 适配器和共享幂等仓储；构造时不产生外部请求。
   */
  constructor(
    private readonly images: ImagesRepository,
    private readonly storage: Storage,
    private readonly idempotency: IdempotencyRepository,
  ) {}
  /**
   * 按声明的类型、大小和幂等键申请图片记录，返回元数据及短期上传地址。
   * 重试沿用图片 ID，但重新签发临时对象上传地址。
   */
  async createUpload(
    input: { content_type: string; size_bytes: number },
    key: string,
  ) {
    const result = await this.idempotency.once(
      "image-upload",
      key,
      input,
      async (tx) => ({
        image: Image.parse(wire(await this.images.create(tx, input))),
      }),
    );
    return {
      ...result,
      upload_url: await this.storage.uploadUrl(
        `uploads/${result.image.id}`,
        input.content_type,
        input.size_bytes,
      ),
      expires_in: 900,
    };
  }
  /**
   * 按 ID 校验已上传字节并发布最终对象，返回符合公开契约的图片元数据。
   * 缺失记录返回 404；重复完成返回原结果，不覆盖已发布对象。
   */
  async complete(id: string) {
    const image = await this.images.complete(id, (row) =>
      this.storage.finalize(
        row.staging_key,
        row.object_key,
        row.content_type,
        row.size_bytes,
      ),
    );
    if (!image) throw new ApiError(404, "IMAGE_NOT_FOUND", "Image not found");
    return Image.parse(wire(image));
  }
  /**
   * 为已完成上传的图片生成短期读取地址；未完成或不存在时返回 404。
   * 只返回签名地址和有效期，不修改图片记录。
   */
  async readUrl(id: string) {
    const image = await this.images.find(id);
    if (!image || image.status !== "ready")
      throw new ApiError(404, "IMAGE_NOT_FOUND", "Ready image not found");
    return {
      url: await this.storage.readUrl(image.object_key),
      expires_in: 300,
    };
  }
}
