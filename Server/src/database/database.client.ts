/**
 * 创建 PostgreSQL 连接池与 Drizzle 客户端，并提供事务类型。
 * 连接释放由调用方负责，本文件不执行迁移或业务查询。
 */
import pg from "pg";
import { drizzle } from "drizzle-orm/node-postgres";
import * as schema from "./database.schema.js";
/**
 * 根据连接地址创建连接池和带表定义的 Drizzle 客户端，返回二者供服务复用。
 * 调用方必须在关闭进程或测试结束时调用 pool.end() 释放连接。
 */
export function connect(url: string) {
  const pool = new pg.Pool({ connectionString: url });
  return { pool, db: drizzle(pool, { schema }) };
}
/** 共享数据库客户端类型，用于查询和开启事务；不表示某次事务。 */
export type DB = ReturnType<typeof connect>["db"];
/** 事务回调内的数据库句柄，跨仓储原子写入时传递，不应在事务结束后使用。 */
export type Tx = Parameters<Parameters<DB["transaction"]>[0]>[0];
