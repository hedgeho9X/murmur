/** 定义帖子容器与标题字段，不包含消息正文；关联消息由消息模块声明。 */
import { pgTable, uuid, text, timestamp } from "drizzle-orm/pg-core";
import { created } from "./columns.js";

/** 帖子容器与可修改标题；消息内容由 messages 表维护。 */
export const posts = pgTable("posts", {
  id: uuid().primaryKey(),
  title: text(),
  created_at: created(),
  updated_at: timestamp("updated_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow(),
});
