-- Retain idempotency tombstones without retaining deleted post bodies or image metadata.
CREATE FUNCTION redact_deleted_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'posts' THEN
    UPDATE idempotency SET response = '{"deleted":true}'::jsonb WHERE response->'post'->>'id' = OLD.id::text;
  ELSE
    UPDATE idempotency SET response = '{"deleted":true}'::jsonb WHERE response->'image'->>'id' = OLD.id::text;
  END IF;
  RETURN OLD;
END $$;
--> statement-breakpoint
CREATE TRIGGER posts_redact_receipts BEFORE DELETE ON posts FOR EACH ROW EXECUTE FUNCTION redact_deleted_receipt();
--> statement-breakpoint
CREATE TRIGGER images_redact_receipts BEFORE DELETE ON images FOR EACH ROW EXECUTE FUNCTION redact_deleted_receipt();
