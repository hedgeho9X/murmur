/**
 * 将已提交的 SQL 迁移应用到配置指定的数据库，完成后关闭连接池。
 * 会修改数据库结构，不根据当前模型直接推送表结构。
 */
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { connect } from "../src/database/database.client.js";
import { config } from "../src/config.js";
const { db, pool } = connect(config().DATABASE_URL);
try {
  await migrate(db, { migrationsFolder: "migrations" });
  console.log("Migrations applied");
} finally {
  await pool.end();
}
