/** 提供模块表复用的列定义，不声明表、外键或运行时数据库操作。 */
import { timestamp } from "drizzle-orm/pg-core";
/** 返回使用数据库默认时间的毫秒精度创建时间列，每次调用生成独立列对象。 */
export const created = () =>
  timestamp("created_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow();
