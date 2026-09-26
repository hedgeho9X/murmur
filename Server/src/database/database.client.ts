/** PostgreSQL connection and transaction ownership for a single API process. */
import pg from "pg";
import { drizzle } from "drizzle-orm/node-postgres";
import * as schema from "./database.schema.js";
/** Opens a pool; callers must close pool.end() during shutdown. */
export function connect(url: string) {
  const pool = new pg.Pool({ connectionString: url });
  return { pool, db: drizzle(pool, { schema }) };
}
export type DB = ReturnType<typeof connect>["db"];
export type Tx = Parameters<Parameters<DB["transaction"]>[0]>[0];
