/** Applies checked-in migrations only to the explicitly configured database. */
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { connect } from "../src/db.js";
import { config } from "../src/config.js";
const { db, pool } = connect(config().DATABASE_URL);
try {
  await migrate(db, { migrationsFolder: "migrations" });
  console.log("Migrations applied");
} finally {
  await pool.end();
}
