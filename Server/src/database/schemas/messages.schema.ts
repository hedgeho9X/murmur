/** 定义不可变消息、帖子外键及查询索引；更新限制由 SQL 迁移中的触发器实施。 */
import { pgTable, uuid, text, jsonb, index } from "drizzle-orm/pg-core";
import { created } from "./columns.js";
import { posts } from "./posts.schema.js";

/** 只追加的消息记录；帖子删除时级联清理，更新限制由数据库触发器实施。 */
export const messages = pgTable(
  "messages",
  {
    id: uuid().primaryKey(),
    post_id: uuid()
      .notNull()
      .references(() => posts.id, { onDelete: "cascade" }),
    turn_id: uuid().notNull(),
    role: text().notNull(),
    content: jsonb().notNull(),
    tool_call_id: text(),
    created_at: created(),
  },
  (t) => [
    index("messages_post_order").on(t.post_id, t.created_at, t.id),
    index("messages_turn").on(t.turn_id),
  ],
);
