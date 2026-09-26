/**
 * 封装帖子及首条用户消息的 PostgreSQL 读写。
 * 只处理查询、事务快照和关系删除；内容校验与分页编码由服务负责。
 */
import { UserContent } from "../messages/messages.contracts.js";
import { extractTags } from "./posts.tags.js";
import { randomUUID } from "node:crypto";
import { eq, sql, desc, and, getTableColumns } from "drizzle-orm";
import type { DB, Tx } from "../../database/database.client.js";
import { posts, messages } from "../../database/schemas/index.js";
import type { NewPost, NewMessage } from "./posts.contracts.js";
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
        .values({
          id: input.id,
          title: input.title ?? null,
          tags: extractTags(input.content),
        })
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
  /** 在帖子行锁下追加用户消息和附件；帖子不存在或消息重复时返回明确结果。 */
  async append(
    postId: string,
    input: NewMessage,
    attach: (tx: Tx, messageId: string) => Promise<void>,
  ) {
    return this.db.transaction(async (tx) => {
      const [post] = await tx
        .select()
        .from(posts)
        .where(eq(posts.id, postId))
        .for("update");
      if (!post) return { kind: "missing" as const };
      const tags = [...new Set([...post.tags, ...extractTags(input.content)])];
      const [message] = await tx
        .insert(messages)
        .values({
          id: input.id,
          post_id: postId,
          turn_id: randomUUID(),
          role: "user",
          content: input.content,
        })
        .onConflictDoNothing({ target: messages.id })
        .returning();
      if (!message) return { kind: "duplicate" as const };
      await attach(tx, message.id);
      await tx
        .update(posts)
        .set({
          updated_at: new Date(),
          tags,
        })
        .where(eq(posts.id, postId));
      return { kind: "created" as const, message };
    });
  }
  /** 在帖子锁内覆盖用户正文并重建全部用户消息的标签，不创建历史副本。 */
  async editText(postId: string, messageId: string, text: string) {
    return this.db.transaction(async (tx) => {
      const [post] = await tx
        .select()
        .from(posts)
        .where(eq(posts.id, postId))
        .for("update");
      if (!post) return { kind: "missing" as const };
      const [message] = await tx
        .select()
        .from(messages)
        .where(and(eq(messages.id, messageId), eq(messages.post_id, postId)));
      if (!message) return { kind: "missing" as const };
      if (message.role !== "user") return { kind: "readonly" as const };
      const original = UserContent.parse(message.content);
      const images = original.parts.filter((p) => p.type === "image");
      if (!text.trim() && !images.length) return { kind: "empty" as const };
      const content = UserContent.parse({
        parts: [...(text.trim() ? [{ type: "text", text }] : []), ...images],
      });
      const [updated] = await tx
        .update(messages)
        .set({ content })
        .where(eq(messages.id, messageId))
        .returning();
      const all = await tx
        .select()
        .from(messages)
        .where(and(eq(messages.post_id, postId), eq(messages.role, "user")))
        .orderBy(messages.created_at, messages.id);
      const tags = [
        ...new Set(
          all.flatMap((m) => extractTags(UserContent.parse(m.content))),
        ),
      ];
      await tx
        .update(posts)
        .set({ tags, updated_at: new Date() })
        .where(eq(posts.id, postId));
      return { kind: "updated" as const, message: updated };
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
  list(limit: number, cursor?: PostCursor, query?: string, imagesOnly = false) {
    const boundary = cursor
      ? sql`(${posts.created_at}, ${posts.id}) < (${cursor.t}::timestamptz, ${cursor.id}::uuid)`
      : undefined;
    const tagQuery = query?.startsWith("#")
      ? query.slice(1).trim().normalize("NFKC").toLowerCase()
      : undefined;
    const tagFilter = tagQuery
      ? sql`${tagQuery} = any(${posts.tags})`
      : undefined;
    const match =
      query && tagQuery === undefined
        ? sql`(${posts.title} ilike ${"%" + query.replace(/[\\%_]/g, "\\$&") + "%"} or exists(select 1 from messages m, jsonb_array_elements(m.content->'parts') part where m.post_id=${posts.id} and part->>'type'='text' and position(lower(${query}) in lower(part->>'text'))>0))`
        : undefined;
    const imageFilter = imagesOnly
      ? sql`exists(select 1 from messages m, jsonb_array_elements(m.content->'parts') part where m.post_id=${posts.id} and part->>'type'='image')`
      : undefined;
    return this.db
      .select({
        ...getTableColumns(posts),
        preview: sql<string>`coalesce((select left(string_agg(part->>'text', ' ' order by m.created_at,m.id,ordinality),160) from messages m, jsonb_array_elements(m.content->'parts') with ordinality as blocks(part,ordinality) where m.post_id=posts.id and m.role='user' and part->>'type'='text'),'')`,
      })
      .from(posts)
      .where(and(boundary, match, imageFilter, tagFilter))
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
  /** 按前缀返回当前使用中的标签和帖子数量，不单独维护冗余标签表。 */
  async suggestTags(prefix: string) {
    const result = await this.db.execute(
      sql`select tag as name, count(*)::int as count from posts, unnest(tags) tag where starts_with(tag, ${prefix.normalize("NFKC").replace(/^#+/, "").toLowerCase()}) group by tag order by count desc, tag asc limit 12`,
    );
    return result.rows as { name: string; count: number }[];
  }
  /**
   * 按 ID 删除帖子，依赖外键级联删除消息和图片元数据。
   * 数据库触发器同时记录对象清理任务；不存在时无操作。
   */
  async delete(id: string) {
    await this.db.delete(posts).where(eq(posts.id, id));
  }
}
