/** 重建配置数据库的帖子标签索引，只读取用户正文，不修改消息或图片。 */
import { eq } from "drizzle-orm";
import { connect } from "../src/database/database.client.js";
import { config } from "../src/config.js";
import { posts, messages } from "../src/database/schemas/index.js";
import { UserContent } from "../src/modules/messages/messages.contracts.js";
import { extractTags } from "../src/modules/posts/posts.tags.js";

const { db, pool } = connect(config().DATABASE_URL);
try {
  const rows = await db.select({ id: posts.id }).from(posts);
  for (const row of rows) {
    await db.transaction(async (tx) => {
      // 与追加消息使用同一帖子行锁，避免重建覆盖并发追加得到的标签。
      const [post] = await tx
        .select()
        .from(posts)
        .where(eq(posts.id, row.id))
        .for("update");
      if (!post) return;
      const contents = await tx
        .select()
        .from(messages)
        .where(eq(messages.post_id, row.id))
        .orderBy(messages.created_at, messages.id);
      const tags = [
        ...new Set(
          contents
            .filter((m) => m.role === "user")
            .flatMap((m) => extractTags(UserContent.parse(m.content))),
        ),
      ];
      await tx.update(posts).set({ tags }).where(eq(posts.id, row.id));
    });
  }
  console.log(`Reindexed ${rows.length} posts`);
} finally {
  await pool.end();
}
