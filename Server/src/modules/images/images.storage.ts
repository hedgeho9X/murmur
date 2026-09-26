/**
 * 通过 S3 协议访问图片对象，区分服务端访问地址和客户端可达的签名地址。
 * 负责字节校验及对象传输，不维护图片数据库状态或业务归属。
 */
import {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  DeleteObjectCommand,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import sharp from "sharp";
import { config } from "../../config.js";
import { ApiError } from "../../common/errors.js";
/**
 * 管理 S3 客户端，在发布图片前验证临时上传的实际内容。
 */
export class Storage {
  readonly client: S3Client;
  private publicClient: S3Client;
  readonly bucket: string;
  /**
   * 根据运行配置创建内部与公开地址的 S3 客户端，二者共享凭据和 bucket。
   * 构造过程不向 S3 发送请求。
   */
  constructor(c: ReturnType<typeof config>) {
    const options = {
      region: c.S3_REGION,
      forcePathStyle: true,
      credentials: {
        accessKeyId: c.S3_ACCESS_KEY,
        secretAccessKey: c.S3_SECRET_KEY,
      },
      requestChecksumCalculation: "WHEN_REQUIRED" as const,
    };
    this.client = new S3Client({ ...options, endpoint: c.S3_ENDPOINT });
    this.publicClient = new S3Client({
      ...options,
      endpoint: c.S3_PUBLIC_ENDPOINT,
    });
    this.bucket = c.S3_BUCKET;
  }
  /**
   * 根据临时对象键、声明类型和大小生成 15 分钟 PUT 地址。
   * 只签名不上传；调用方必须传入临时键，不能开放最终图片键的覆盖权限。
   */
  uploadUrl(key: string, type: string, size: number) {
    return getSignedUrl(
      this.publicClient,
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: key,
        ContentType: type,
        ContentLength: size,
      }),
      { expiresIn: 900 },
    );
  }
  /**
   * 根据最终对象键生成 5 分钟 GET 地址，不读取对象。
   * 调用方必须先验证该图片存在、已就绪且允许访问。
   */
  readUrl(key: string) {
    return getSignedUrl(
      this.publicClient,
      new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      { expiresIn: 300 },
    );
  }
  /**
   * 读取临时对象，核对大小并实际解码单帧图片，将验证后的相同字节写入最终键。
   * 成功返回宽高；缺失、损坏、类型不符或超出限制时拒绝发布。
   * S3 写入不参与数据库事务，调用方需通过重试或对象清理处理跨系统失败。
   */
  async finalize(staging: string, target: string, type: string, size: number) {
    try {
      const head = await this.client.send(
        new HeadObjectCommand({ Bucket: this.bucket, Key: staging }),
      );
      if (head.ContentLength !== size)
        throw new ApiError(
          422,
          "IMAGE_SIZE_MISMATCH",
          "Uploaded size differs from declared size",
        );
      const response = await this.client.send(
        new GetObjectCommand({
          Bucket: this.bucket,
          Key: staging,
          IfMatch: head.ETag,
        }),
      );
      if (!response.Body)
        throw new ApiError(422, "INVALID_IMAGE", "Empty image");
      const chunks: Buffer[] = [];
      let total = 0;
      for await (const chunk of response.Body as AsyncIterable<Uint8Array>) {
        total += chunk.length;
        if (total > size)
          throw new ApiError(
            422,
            "IMAGE_SIZE_MISMATCH",
            "Image exceeds declared size",
          );
        chunks.push(Buffer.from(chunk));
      }
      if (total !== size)
        throw new ApiError(422, "IMAGE_SIZE_MISMATCH", "Incomplete image");
      const bytes = Buffer.concat(chunks);
      let meta;
      try {
        const decoder = sharp(bytes, {
          limitInputPixels: 25_000_000,
          failOn: "warning",
        });
        meta = await decoder.metadata();
        await decoder.clone().stats();
      } catch {
        throw new ApiError(
          422,
          "INVALID_IMAGE",
          "Unsupported or corrupt image",
        );
      }
      if (
        `image/${meta.format}` !== type ||
        !meta.width ||
        !meta.height ||
        (meta.pages ?? 1) > 1
      )
        throw new ApiError(
          422,
          "INVALID_IMAGE",
          "Expected a single-frame JPEG, PNG or WebP",
        );
      await this.client.send(
        new PutObjectCommand({
          Bucket: this.bucket,
          Key: target,
          Body: bytes,
          ContentType: type,
        }),
      );
      return { width: meta.width, height: meta.height };
    } catch (e) {
      if (
        (e as { name?: string }).name === "NotFound" ||
        (e as { name?: string }).name === "NoSuchKey"
      )
        throw new ApiError(
          422,
          "UPLOAD_MISSING",
          "Upload the image before completing it",
        );
      throw e;
    }
  }
  /**
   * 按对象键请求 S3 删除，对象已不存在时仍可安全重试。
   * 请求失败时向调用方抛错，由清理队列保留任务。
   */
  async remove(key: string) {
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.bucket, Key: key }),
    );
  }
}
