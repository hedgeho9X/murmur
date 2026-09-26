/** 汇总业务表供 Drizzle 客户端与迁移生成使用；各 schema 的外键直接引用目标文件。 */
export { posts } from "./posts.schema.js";
export { messages } from "./messages.schema.js";
export { images, objectCleanup } from "./images.schema.js";
