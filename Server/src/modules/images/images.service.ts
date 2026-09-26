/** Image upload lifecycle and S3 orchestration without HTTP or SQL dependencies. */
import { Image } from "./images.contracts.js";
import { wire } from "../../common/serialization.js";
import { ApiError } from "../../common/errors.js";
import type { ImagesRepository } from "./images.repository.js";
import type { Storage } from "./images.storage.js";
import type { IdempotencyRepository } from "../idempotency/idempotency.repository.js";
/** Coordinates immutable image publication and short-lived client access. */
export class ImagesService {
  /** Injects persistence, object transport and the shared idempotency boundary. */
  constructor(
    private readonly images: ImagesRepository,
    private readonly storage: Storage,
    private readonly idempotency: IdempotencyRepository,
  ) {}
  /** Reuses the original image identity while issuing a fresh staging upload URL. */
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
  /** Verifies uploaded bytes once and returns stable published metadata. */
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
  /** Signs a read only when the image has been validated and published. */
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
