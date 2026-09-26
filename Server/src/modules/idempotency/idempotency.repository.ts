/**
 * 在数据库事务内协调幂等请求、业务写入与响应回执。
 * 供帖子创建和图片上传共用，不负责 HTTP 校验或外部存储操作。
 */
import { createHash } from "node:crypto";
import { eq, and, sql } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { posts, images, idempotency } from "../../database/database.schema.js";
import { ApiError } from "../../common/errors.js";
import { wire } from "../../common/serialization.js";
/**
 * 将 JSON 输入编码为键顺序稳定的字符串，供请求哈希使用。
 * 对象键排序，数组顺序保留；输入应为已通过契约校验的 JSON 数据。
 */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value !== null && typeof value === "object")
    return (
      "{" +
      Object.keys(value)
        .sort()
        .map(
          (k) =>
            JSON.stringify(k) +
            ":" +
            canonical((value as Record<string, unknown>)[k]),
        )
        .join(",") +
      "}"
    );
  return JSON.stringify(value);
}
/**
 * 管理幂等锁和回执，使同键请求的业务写入与结果保存处于同一事务。
 */
export class IdempotencyRepository {
  /**
   * 注入参与业务写入的数据库客户端，构造时不建立事务或执行查询。
   */
  constructor(private readonly db: DB) {}
  /**
   * 按操作范围和幂等键串行处理请求，返回原回执或本次事务产生的响应快照。
   * 相同键但内容不同返回冲突，已删除资源返回失效错误；首次执行将事务传给 action。
   * action 或回执写入失败时整体回滚，回调不得包含无法随事务回滚的外部副作用。
   */
  async once<T>(
    scope: string,
    key: string,
    input: unknown,
    action: (tx: Tx) => Promise<T>,
  ): Promise<T> {
    const hash = createHash("sha256").update(canonical(input)).digest("hex");
    return this.db.transaction(async (tx) => {
      await tx.execute(
        sql`select pg_advisory_xact_lock(hashtextextended(${scope + ":" + key}, 0))`,
      );
      const [saved] = await tx
        .select()
        .from(idempotency)
        .where(and(eq(idempotency.scope, scope), eq(idempotency.key, key)));
      if (saved) {
        if ((saved.response as { deleted?: boolean }).deleted)
          throw new ApiError(
            410,
            "RESOURCE_DELETED",
            "Original resource was deleted; this request cannot recreate it",
          );
        if (saved.request_hash !== hash)
          throw new ApiError(
            409,
            "IDEMPOTENCY_CONFLICT",
            "Key already used for different content",
          );
        const response = saved.response as {
          post?: { id: string };
          image?: { id: string };
        };
        const exists = response.post
          ? await tx
              .select({ id: posts.id })
              .from(posts)
              .where(eq(posts.id, response.post.id))
          : response.image
            ? await tx
                .select({ id: images.id })
                .from(images)
                .where(eq(images.id, response.image.id))
            : [true];
        if (!exists.length)
          throw new ApiError(
            410,
            "RESOURCE_DELETED",
            "Original resource was deleted; this request cannot recreate it",
          );
        return saved.response as T;
      }
      const result = wire(await action(tx));
      await tx
        .insert(idempotency)
        .values({ scope, key, request_hash: hash, response: result });
      return result;
    });
  }
}
