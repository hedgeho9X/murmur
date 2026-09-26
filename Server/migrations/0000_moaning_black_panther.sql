CREATE TABLE "idempotency" (
	"scope" text NOT NULL,
	"key" text NOT NULL,
	"request_hash" text NOT NULL,
	"response" jsonb NOT NULL,
	"created_at" timestamp (3) with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "idempotency_scope_key_pk" PRIMARY KEY("scope","key")
);
--> statement-breakpoint
CREATE TABLE "images" (
	"id" uuid PRIMARY KEY NOT NULL,
	"object_key" text NOT NULL,
	"staging_key" text NOT NULL,
	"content_type" text NOT NULL,
	"size_bytes" integer NOT NULL,
	"width" integer,
	"height" integer,
	"status" text DEFAULT 'pending' NOT NULL,
	"message_id" uuid,
	"created_at" timestamp (3) with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "images_object_key_unique" UNIQUE("object_key"),
	CONSTRAINT "images_staging_key_unique" UNIQUE("staging_key")
);
--> statement-breakpoint
CREATE TABLE "messages" (
	"id" uuid PRIMARY KEY NOT NULL,
	"post_id" uuid NOT NULL,
	"turn_id" uuid NOT NULL,
	"role" text NOT NULL,
	"content" jsonb NOT NULL,
	"tool_call_id" text,
	"created_at" timestamp (3) with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "object_cleanup" (
	"object_key" text PRIMARY KEY NOT NULL,
	"created_at" timestamp (3) with time zone DEFAULT now() NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE "posts" (
	"id" uuid PRIMARY KEY NOT NULL,
	"title" text,
	"created_at" timestamp (3) with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp (3) with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "images" ADD CONSTRAINT "images_message_id_messages_id_fk" FOREIGN KEY ("message_id") REFERENCES "public"."messages"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "messages" ADD CONSTRAINT "messages_post_id_posts_id_fk" FOREIGN KEY ("post_id") REFERENCES "public"."posts"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "messages_post_order" ON "messages" USING btree ("post_id","created_at","id");--> statement-breakpoint
CREATE INDEX "messages_turn" ON "messages" USING btree ("turn_id");--> statement-breakpoint
ALTER TABLE messages ADD CONSTRAINT message_role_check CHECK (role IN ('user', 'assistant', 'tool'));
--> statement-breakpoint
ALTER TABLE messages ADD CONSTRAINT message_content_check CHECK (jsonb_typeof(content) = 'object' AND content ? 'parts' AND jsonb_typeof(content->'parts') = 'array');
--> statement-breakpoint
ALTER TABLE messages ADD CONSTRAINT message_tool_check CHECK ((role = 'tool' AND tool_call_id IS NOT NULL AND content ? 'is_error' AND jsonb_typeof(content->'is_error') = 'boolean') OR (role <> 'tool' AND tool_call_id IS NULL AND NOT content ? 'is_error'));
--> statement-breakpoint
CREATE FUNCTION reject_message_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'messages are immutable'; END $$;
--> statement-breakpoint
CREATE TRIGGER messages_immutable BEFORE UPDATE ON messages FOR EACH ROW EXECUTE FUNCTION reject_message_update();
--> statement-breakpoint
CREATE FUNCTION enqueue_image_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO object_cleanup(object_key) VALUES (OLD.object_key), (OLD.staging_key) ON CONFLICT DO NOTHING; RETURN OLD; END $$;
--> statement-breakpoint
CREATE TRIGGER images_cleanup BEFORE DELETE ON images FOR EACH ROW EXECUTE FUNCTION enqueue_image_cleanup();
