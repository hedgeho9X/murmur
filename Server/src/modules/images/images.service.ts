/**
 * 协调上传申请、图片校验、最终对象发布和签名读取。
 * 不处理 HTTP 或 SQL，分别通过契约、仓储和 S3 适配器完成。
 */
import { Image } from "./images.contracts.js";
import { wire } from "../../common/serialization.js";
import { ApiError } from "../../common/errors.js";
import type { ImagesRepository } from "./images.repository.js";
import type { Storage } from "./images.storage.js";
/**
 * 提供图片生命周期操作，确保未验证的临时上传不能作为已发布图片读取。
 */
export class ImagesService {
  /**
   * 注入图片仓储和 S3 适配器；构造时不产生外部请求。
   */
  constructor(
    private readonly images: ImagesRepository,
    private readonly storage: Storage,
  ) {}
  /**
   * 按声明的类型和大小申请新的图片记录，返回元数据及短期上传地址。
   * 每次请求分配新图片 ID；未被帖子引用的上传由过期清理回收。
   */
  async createUpload(input: { content_type: string; size_bytes: number }) {
    const result = {
      image: Image.parse(wire(await this.images.create(input))),
    };
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
