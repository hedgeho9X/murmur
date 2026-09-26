/**
 * 配置 Drizzle 从数据库表定义生成 SQL 迁移。
 * 此配置只指定生成输入和输出目录，不执行数据库变更。
 */
import { defineConfig } from "drizzle-kit";
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/database/database.schema.ts",
  out: "./migrations",
});
