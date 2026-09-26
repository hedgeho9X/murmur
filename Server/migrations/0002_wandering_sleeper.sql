-- 删除仅用于幂等回执的触发器和函数，不影响消息不可变约束与图片清理队列。
DROP TRIGGER IF EXISTS posts_redact_receipts ON posts;
--> statement-breakpoint
DROP TRIGGER IF EXISTS images_redact_receipts ON images;
--> statement-breakpoint
DROP FUNCTION IF EXISTS redact_deleted_receipt();
--> statement-breakpoint
DROP TABLE "idempotency" CASCADE;