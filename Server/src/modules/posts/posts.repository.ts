/**
 * 封装帖子及首条用户消息的 PostgreSQL 读写。
 * 只处理查询、事务快照和关系删除；内容校验与分页编码由服务负责。
 */
import { randomUUID } from "node:crypto";
import { eq, sql, desc } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { posts, messages } from "../../database/schemas/index.js";
import type { NewPost } from "./posts.contracts.js";
/**
 * 已解码的帖子分页边界，包含创建时间和 ID；不包含 HTTP 游标编码逻辑。
 */
export type PostCursor = { t: string; id: string };
/**
 * 执行帖子相关 SQL，创建操作开启事务，并将事务传给附件绑定回调。
 */
export class PostsRepository {
  /**
   * 注入共享数据库客户端，用于读取、元数据更新和删除；构造时不执行查询。
   */
  constructor(private readonly db: DB) {}
  /**
   * 使用客户端帖子 ID 创建帖子和首条消息，分配消息与轮次 ID，返回插入结果。
   * 帖子 ID 冲突时返回 undefined；附件回调共享事务，失败则全部回滚。
   */
  async create(
    input: NewPost,
    attach: (tx: Tx, messageId: string) => Promise<void>,
  ) {
    return this.db.transaction(async (tx) => {
      const [post] = await tx
        .insert(posts)
        .values({ id: input.id, title: input.title ?? null })
        .onConflictDoNothing({ target: posts.id })
        .returning();
      if (!post) return undefined;
      const [message] = await tx
        .insert(messages)
        .values({
          id: randomUUID(),
          post_id: post.id,
          turn_id: randomUUID(),
          role: "user",
          content: input.content,
        })
        .returning();
      await attach(tx, message.id);
      return { post, message };
    });
  }
  /**
   * 按帖子 ID 在同一只读快照中读取帖子及按时间、ID 排列的消息。
   * 帖子不存在时返回 undefined，不修改任何数据。
   */
  async detail(id: string) {
    return this.db.transaction(
      async (tx) => {
        const [post] = await tx.select().from(posts).where(eq(posts.id, id));
        if (!post) return undefined;
        const rows = await tx
          .select()
          .from(messages)
          .where(eq(messages.post_id, id))
          .orderBy(messages.created_at, messages.id);
        return { post, messages: rows };
      },
      { isolationLevel: "repeatable read", accessMode: "read only" },
    );
  }
  /**
   * 根据页大小和可选边界读取倒序帖子，多取一条以判断是否还有下一页。
   * 返回数据库行，不生成游标或修改数据。
   */
  list(limit: number, cursor?: PostCursor) {
    const boundary = cursor
      ? sql`(${posts.created_at}, ${posts.id}) < (${cursor.t}::timestamptz, ${cursor.id}::uuid)`
      : undefined;
    return this.db
      .select()
      .from(posts)
      .where(boundary)
      .orderBy(desc(posts.created_at), desc(posts.id))
      .limit(limit + 1);
  }
  /**
   * 按帖子 ID 更新标题和修改时间，返回更新后的帖子；不存在时返回 undefined。
   * 不修改该帖子的任何已发送消息。
   */
  async rename(id: string, title: string | null) {
    const [post] = await this.db
      .update(posts)
      .set({ title, updated_at: new Date() })
      .where(eq(posts.id, id))
      .returning();
    return post;
  }
  /**
   * 按 ID 删除帖子，依赖外键级联删除消息和图片元数据。
   * 数据库触发器同时记录对象清理任务；不存在时无操作。
   */
  async delete(id: string) {
    await this.db.delete(posts).where(eq(posts.id, id));
  }
}
