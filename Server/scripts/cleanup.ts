/** Runs one bounded cleanup pass; production deployments should schedule this command. */
import { config } from "../src/config.js";
import { connect } from "../src/database/database.client.js";
import { Storage } from "../src/modules/images/images.storage.js";
import { cleanup } from "../src/modules/images/images.cleanup.js";
import { ImagesRepository } from "../src/modules/images/images.repository.js";
const c = config();
const { db, pool } = connect(c.DATABASE_URL);
try {
  console.log(await cleanup(new ImagesRepository(db), new Storage(c)));
} finally {
  await pool.end();
}
