/**
 * 定义帖子、不可变消息、图片归属、幂等回执和对象清理表。
 * 这里只声明表与关联；消息不可更新等触发器约束由已提交的 SQL 迁移实施。
 */
import {
  pgTable,
  uuid,
  text,
  timestamp,
  jsonb,
  integer,
  primaryKey,
  index,
} from "drizzle-orm/pg-core";
/** 创建使用数据库默认时间的毫秒精度时间列，与接口时间序列化精度保持一致。 */
const created = () =>
  timestamp("created_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow();
/** 帖子容器与可修改标题；消息内容由 messages 表维护。 */
export const posts = pgTable("posts", {
  id: uuid().primaryKey(),
  title: text(),
  created_at: created(),
  updated_at: timestamp("updated_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow(),
});
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
/** 图片对象元数据及单一消息归属；实际文件存储在 S3。 */
export const images = pgTable("images", {
  id: uuid().primaryKey(),
  object_key: text().notNull().unique(),
  staging_key: text().notNull().unique(),
  content_type: text().notNull(),
  size_bytes: integer().notNull(),
  width: integer(),
  height: integer(),
  status: text().notNull().default("pending"),
  message_id: uuid().references(() => messages.id, { onDelete: "cascade" }),
  created_at: created(),
});
/** 按操作范围和请求键保存请求哈希及响应快照，防止重复创建。 */
export const idempotency = pgTable(
  "idempotency",
  {
    scope: text().notNull(),
    key: text().notNull(),
    request_hash: text().notNull(),
    response: jsonb().notNull(),
    created_at: created(),
  },
  (t) => [primaryKey({ columns: [t.scope, t.key] })],
);
/** 待删除对象的持久队列，存储失败时保留对象键和重试次数。 */
export const objectCleanup = pgTable("object_cleanup", {
  object_key: text().primaryKey(),
  created_at: created(),
  attempts: integer().notNull().default(0),
});
