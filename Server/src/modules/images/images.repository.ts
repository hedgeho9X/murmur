/**
 * 封装图片元数据、消息归属、上传完成状态与清理队列的 PostgreSQL 操作。
 * 不访问 S3；需要验证图片时通过回调交回服务层。
 */
import { randomUUID } from "node:crypto";
import { and, eq, isNull, lt, sql } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { images, objectCleanup } from "../../database/schemas/index.js";
/**
 * 图片持久化记录，包含服务端对象键和归属信息；不直接作为公开响应。
 */
export type ImageRow = typeof images.$inferSelect;
/**
 * 管理图片 SQL 与行锁，协调上传完成、附件认领和可重试对象清理。
 */
export class ImagesRepository {
  /**
   * 注入共享数据库客户端；跨模块写入使用调用方传入的事务。
   */
  constructor(private readonly db: DB) {}
  /**
   * 分配图片 ID、临时键和最终键，写入新的待上传记录并返回。
   * 每次申请创建独立记录，不创建 S3 对象。
   */
  async create(input: { content_type: string; size_bytes: number }) {
    const id = randomUUID();
    const [image] = await this.db
      .insert(images)
      .values({
        id,
        object_key: `images/${id}`,
        staging_key: `uploads/${id}`,
        ...input,
      })
      .returning();
    return image;
  }
  /**
   * 在传入事务内将已完成且未被认领的图片绑定到消息，返回是否绑定成功。
   * 条件更新保证并发请求最多有一个能认领同一图片。
   */
  async attach(tx: Tx, id: string, messageId: string) {
    const rows = await tx
      .update(images)
      .set({ message_id: messageId })
      .where(
        and(
          eq(images.id, id),
          eq(images.status, "ready"),
          isNull(images.message_id),
        ),
      )
      .returning();
    return rows.length > 0;
  }
  /**
   * 按图片 ID 查询元数据，不存在时返回 undefined；不生成签名地址或修改数据。
   */
  async find(id: string) {
    const [image] = await this.db
      .select()
      .from(images)
      .where(eq(images.id, id));
    return image;
  }
  /**
   * 锁定图片行，调用 verify 验证字节，保存宽高与 ready 状态并登记临时对象清理。
   * 已完成时直接返回记录，不存在时返回 undefined；回调失败则回滚数据库事务。
   * 回调访问 S3 时行锁仍被持有，调用方需控制上传大小和外部操作耗时。
   */
  async complete(
    id: string,
    verify: (image: ImageRow) => Promise<{ width: number; height: number }>,
  ) {
    return this.db.transaction(async (tx) => {
      const [row] = await tx
        .select()
        .from(images)
        .where(eq(images.id, id))
        .for("update");
      if (!row || row.status === "ready") return row;
      const dimensions = await verify(row);
      const [ready] = await tx
        .update(images)
        .set({ status: "ready", ...dimensions })
        .where(eq(images.id, row.id))
        .returning();
      await tx
        .insert(objectCleanup)
        .values({ object_key: row.staging_key })
        .onConflictDoNothing();
      return ready;
    });
  }
  /**
   * 删除指定时间之前未关联消息的图片元数据，由删除触发器登记对象清理任务。
   */
  async expireUnattached(before: Date) {
    await this.db
      .delete(images)
      .where(and(isNull(images.message_id), lt(images.created_at, before)));
  }
  /**
   * 按创建时间读取最多 100 个清理任务；只读取，不认领或删除任务。
   */
  pendingCleanup() {
    return this.db
      .select()
      .from(objectCleanup)
      .orderBy(objectCleanup.created_at)
      .limit(100);
  }
  /**
   * 按对象键删除已完成的清理项；必须在 S3 删除成功后调用，重复确认无副作用。
   */
  async acknowledgeCleanup(key: string) {
    await this.db
      .delete(objectCleanup)
      .where(eq(objectCleanup.object_key, key));
  }
  /**
   * 增加指定清理项的失败次数并保留记录，以便后续清理继续重试。
   */
  async failCleanup(key: string) {
    await this.db
      .update(objectCleanup)
      .set({ attempts: sql`${objectCleanup.attempts} + 1` })
      .where(eq(objectCleanup.object_key, key));
  }
}
