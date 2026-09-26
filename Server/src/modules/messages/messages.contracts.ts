/**
 * 定义不可变消息、角色约束和有序内容块契约，供 API 序列化与客户端生成使用。
 * 不负责消息持久化或 Agent 执行。
 */
import { z } from "@hono/zod-openapi";
import { Id } from "../../common/contracts.js";
export const TextPart = z
  .object({ type: z.literal("text"), text: z.string().max(100_000) })
  .strict()
  .openapi("TextPart");
export const ImagePart = z
  .object({ type: z.literal("image"), image_id: Id })
  .strict()
  .openapi("ImagePart");
export const ToolCallPart = z
  .object({
    type: z.literal("tool_call"),
    id: z.string().min(1),
    name: z.string().min(1),
    arguments: z.record(z.string(), z.unknown()),
  })
  .strict()
  .openapi("ToolCallPart");
export const JsonPart = z
  .object({
    type: z.literal("json"),
    data: z
      .any()
      .refine((value) => value !== undefined, "JSON data is required")
      .openapi({ description: "Any JSON value" }),
  })
  .strict()
  .openapi("JsonPart");
export const UserContent = z
  .object({
    parts: z
      .array(z.discriminatedUnion("type", [TextPart, ImagePart]))
      .min(1)
      .max(30),
  })
  .strict()
  .openapi("UserContent");
export const AssistantContent = z
  .object({
    parts: z.array(
      z.discriminatedUnion("type", [TextPart, ImagePart, ToolCallPart]),
    ),
    finish_reason: z.enum(["stop", "tool_call", "interrupted", "error"]),
  })
  .strict()
  .openapi("AssistantContent");
export const ToolContent = z
  .object({
    parts: z.array(
      z.discriminatedUnion("type", [TextPart, ImagePart, JsonPart]),
    ),
    is_error: z.boolean(),
  })
  .strict()
  .openapi("ToolContent");
const common = {
  id: Id,
  post_id: Id,
  turn_id: Id,
  created_at: z.string().datetime(),
};
export const UserMessage = z
  .object({
    ...common,
    role: z.literal("user"),
    content: UserContent,
    tool_call_id: z.null(),
  })
  .openapi("UserMessage");
export const AssistantMessage = z
  .object({
    ...common,
    role: z.literal("assistant"),
    content: AssistantContent,
    tool_call_id: z.null(),
  })
  .openapi("AssistantMessage");
export const ToolMessage = z
  .object({
    ...common,
    role: z.literal("tool"),
    content: ToolContent,
    tool_call_id: z.string().min(1),
  })
  .openapi("ToolMessage");
export const Message = z
  .discriminatedUnion("role", [UserMessage, AssistantMessage, ToolMessage])
  .openapi("Message");
