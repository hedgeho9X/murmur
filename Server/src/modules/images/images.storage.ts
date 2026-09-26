/** S3 transport separates private server access from client-reachable signed URLs. */
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
/** Owns S3 clients and verifies temporary uploads before publishing immutable image objects. */
export class Storage {
  readonly client: S3Client;
  private publicClient: S3Client;
  readonly bucket: string;
  /** Creates clients with the same credentials but different reachable endpoints. */
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
  /** Returns a short-lived PUT URL for a staging key, never the published object. */
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
  /** Returns a short-lived read URL after the API has checked resource ownership. */
  readUrl(key: string) {
    return getSignedUrl(
      this.publicClient,
      new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      { expiresIn: 300 },
    );
  }
  /** Validates a bounded image and writes the exact validated bytes to its final key. */
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
  /** S3 deletion is idempotent, allowing the durable cleanup queue to retry safely. */
  async remove(key: string) {
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.bucket, Key: key }),
    );
  }
}
