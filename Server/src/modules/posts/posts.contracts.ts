/** Post request and response schemas used by the HTTP boundary. */
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
export type NewPost = z.infer<typeof CreatePost>;
