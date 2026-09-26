/** 定义图片归属和对象清理队列；图片字节保存在 S3，清理执行由图片模块负责。 */
import { pgTable, uuid, text, integer } from "drizzle-orm/pg-core";
import { created } from "./columns.js";
import { messages } from "./messages.schema.js";

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

/** 待删除对象的持久队列，存储失败时保留对象键和重试次数。 */
export const objectCleanup = pgTable("object_cleanup", {
  object_key: text().primaryKey(),
  created_at: created(),
  attempts: integer().notNull().default(0),
});
