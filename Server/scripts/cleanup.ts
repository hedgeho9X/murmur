/** Runs one bounded cleanup pass; production deployments should schedule this command. */
import { config } from "../src/config.js";
import { connect } from "../src/db.js";
import { Storage } from "../src/storage.js";
import { cleanup } from "../src/cleanup.js";
const c = config();
const { db, pool } = connect(c.DATABASE_URL);
try {
  console.log(await cleanup(db, new Storage(c)));
} finally {
  await pool.end();
}
