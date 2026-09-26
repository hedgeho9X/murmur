/**
 * 读取本地配置并执行一次对象清理，输出处理数量后关闭数据库连接。
 * 会删除过期未绑定图片和队列中的 S3 对象，不负责周期调度。
 */
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
