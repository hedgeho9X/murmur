/** 定义按操作范围隔离的幂等回执，事务锁与重放策略由幂等仓储负责。 */
import { pgTable, text, jsonb, primaryKey } from "drizzle-orm/pg-core";
import { created } from "./columns.js";

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
