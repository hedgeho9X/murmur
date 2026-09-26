/**
 * 定义帖子创建、详情和列表复用的数据契约，供 API 校验与客户端生成使用。
 * 不承载数据库操作或业务流程。
 */
import { z } from "@hono/zod-openapi";
import { Id } from "../../common/contracts.js";
import {
  UserContent,
  UserMessage,
  Message,
} from "../messages/messages.contracts.js";
export const Post = z
  .object({
    id: Id,
    title: z.string().nullable(),
    created_at: z.string().datetime(),
    updated_at: z.string().datetime(),
  })
  .openapi("Post");
export const CreatePost = z
  .object({
    id: z.uuid({ version: "v7" }).openapi({
      description:
        "客户端创建草稿时生成并保存的 UUIDv7，重试复用；重复创建返回 409。",
    }),
    title: z.string().trim().min(1).max(200).nullable().optional(),
    content: UserContent,
  })
  .strict()
  .openapi("CreatePostRequest");
export const CreatedPost = z
  .object({ post: Post, message: UserMessage })
  .openapi("CreatedPost");
export const PostDetail = z
  .object({ post: Post, messages: z.array(Message) })
  .openapi("PostDetail");
/** 通过创建契约校验的帖子输入；帖子 ID 由客户端提供，消息角色、时间和轮次由服务端生成。 */
export type NewPost = z.infer<typeof CreatePost>;

/** 向已有帖子追加用户记录；客户端在草稿中生成稳定消息 UUIDv7。 */
export const AppendMessage = z
  .object({ id: z.uuid({ version: "v7" }), content: UserContent })
  .strict()
  .openapi("AppendMessageRequest");
export type NewMessage = z.infer<typeof AppendMessage>;
