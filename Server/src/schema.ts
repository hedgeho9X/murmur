/** Relational ownership and immutable messages; image bytes live in S3. */
import {
  pgTable,
  uuid,
  text,
  timestamp,
  jsonb,
  integer,
  primaryKey,
  index,
} from "drizzle-orm/pg-core";
const created = () =>
  timestamp("created_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow();
export const posts = pgTable("posts", {
  id: uuid().primaryKey(),
  title: text(),
  created_at: created(),
  updated_at: timestamp("updated_at", { withTimezone: true, precision: 3 })
    .notNull()
    .defaultNow(),
});
export const messages = pgTable(
  "messages",
  {
    id: uuid().primaryKey(),
    post_id: uuid()
      .notNull()
      .references(() => posts.id, { onDelete: "cascade" }),
    turn_id: uuid().notNull(),
    role: text().notNull(),
    content: jsonb().notNull(),
    tool_call_id: text(),
    created_at: created(),
  },
  (t) => [
    index("messages_post_order").on(t.post_id, t.created_at, t.id),
    index("messages_turn").on(t.turn_id),
  ],
);
export const images = pgTable("images", {
  id: uuid().primaryKey(),
  object_key: text().notNull().unique(),
  staging_key: text().notNull().unique(),
  content_type: text().notNull(),
  size_bytes: integer().notNull(),
  width: integer(),
  height: integer(),
  status: text().notNull().default("pending"),
  message_id: uuid().references(() => messages.id, { onDelete: "cascade" }),
  created_at: created(),
});
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
export const objectCleanup = pgTable("object_cleanup", {
  object_key: text().primaryKey(),
  created_at: created(),
  attempts: integer().notNull().default(0),
});
