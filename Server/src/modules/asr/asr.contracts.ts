/** ASR 双向消息契约，统一运行时校验、OpenAPI 定义与 Kotlin 类型生成。 */
import { z } from "@hono/zod-openapi";
export const AsrStart = z
  .object({
    type: z.literal("start"),
    format: z.literal("pcm_s16le"),
    sample_rate: z.number().int().min(16000).max(16000),
    channels: z.number().int().min(1).max(1),
  })
  .strict()
  .openapi("AsrStart");
export const AsrFinish = z
  .object({ type: z.literal("finish") })
  .strict()
  .openapi("AsrFinish");
export const AsrCancel = z
  .object({ type: z.literal("cancel") })
  .strict()
  .openapi("AsrCancel");
export const AsrClientEvent = z
  .discriminatedUnion("type", [AsrStart, AsrFinish, AsrCancel])
  .openapi("AsrClientEvent");
export const AsrReady = z
  .object({ type: z.literal("ready") })
  .openapi("AsrReady");
export const AsrTranscript = z
  .object({
    type: z.literal("transcript"),
    segment_id: z.string(),
    text: z.string(),
    is_final: z.boolean(),
  })
  .openapi("AsrTranscript");
export const AsrCompleted = z
  .object({ type: z.literal("completed"), text: z.string() })
  .openapi("AsrCompleted");
export const AsrError = z
  .object({ type: z.literal("error"), code: z.string(), message: z.string() })
  .openapi("AsrError");
export const AsrServerEvent = z
  .discriminatedUnion("type", [AsrReady, AsrTranscript, AsrCompleted, AsrError])
  .openapi("AsrServerEvent");
/** 服务端可发送的事件类型，与生成客户端的判别联合对应。 */
export type ServerEvent = z.infer<typeof AsrServerEvent>;
