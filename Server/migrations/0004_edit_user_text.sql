-- 用户笔记仅可覆盖正文，身份、轮次、时间与非文字附件保持不变；助理和工具消息不可修改。
CREATE OR REPLACE FUNCTION reject_message_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.role <> 'user' OR (to_jsonb(NEW) - 'content') IS DISTINCT FROM (to_jsonb(OLD) - 'content') THEN
    RAISE EXCEPTION 'only user message text can be edited';
  END IF;
  IF (NEW.content - 'parts') IS DISTINCT FROM (OLD.content - 'parts') OR
     (SELECT coalesce(jsonb_agg(p), '[]'::jsonb) FROM jsonb_array_elements(NEW.content->'parts') p WHERE p->>'type' <> 'text')
     IS DISTINCT FROM
     (SELECT coalesce(jsonb_agg(p), '[]'::jsonb) FROM jsonb_array_elements(OLD.content->'parts') p WHERE p->>'type' <> 'text') THEN
    RAISE EXCEPTION 'message attachments cannot be changed';
  END IF;
  RETURN NEW;
END $$;
