/** 定义 ASR WebSocket 协议入口；认证沿用 /api 中间件，密钥仅留在服务端。 */
import { createRoute, OpenAPIHono } from "@hono/zod-openapi";
import { upgradeWebSocket } from "@hono/node-server";
import {
  AsrClientEvent,
  AsrServerEvent,
} from "../modules/asr/asr.contracts.js";
import { AsrSession, type AsrOptions } from "../modules/asr/asr.session.js";
/** 双向音频流契约，JSON 控制帧与二进制 PCM 共用一条连接。 */
const asrStreamRoute = createRoute({
  method: "get",
  path: "/api/v1/asr/stream",
  operationId: "streamAsr",
  tags: ["ASR"],
  security: [{ bearerAuth: [] }],
  description:
    "WebSocket: start {format:pcm_s16le,sample_rate:16000,channels:1} → ready → binary PCM → finish → transcript/completed. Transcript fields: segment_id,text,is_final. Error: type=error,code,message.",
  responses: {
    101: { description: "WebSocket upgrade" },
    503: {
      description: "ASR is not configured or concurrent session limit reached",
    },
  },
});
/** 注册流式转发，每个连接拥有独立状态，最多同时处理两段录音。 */
export function registerAsrApi(app: OpenAPIHono, options?: AsrOptions) {
  let active = 0;
  app.openAPIRegistry.register("AsrClientEvent", AsrClientEvent);
  app.openAPIRegistry.register("AsrServerEvent", AsrServerEvent);
  app.openAPIRegistry.registerPath(asrStreamRoute);
  /** GET /api/v1/asr/stream：验证服务可用性后升级连接，转发单次录音。 */
  app.get(asrStreamRoute.path, async (c, next) => {
    if (!options?.key)
      return c.json(
        {
          error: {
            code: "ASR_NOT_CONFIGURED",
            message: "ASR is not configured",
          },
        },
        503,
      );
    if (active >= 2)
      return c.json(
        { error: { code: "ASR_BUSY", message: "Too many recordings" } },
        503,
      );
    const response = await upgradeWebSocket(() => {
      let session: AsrSession;
      return {
        onOpen: (_, ws) => {
          active++;
          session = new AsrSession(
            options,
            (event) => ws.send(JSON.stringify(event)),
            () => ws.close(1000),
            () => active--,
          );
        },
        onMessage: (event) =>
          session?.receive(event.data as string | ArrayBuffer),
        onClose: () => session?.dispose(),
        onError: () => session?.dispose(),
      };
    })(c, next);
    return (
      response ??
      c.json(
        { error: { code: "UPGRADE_REQUIRED", message: "Use WebSocket" } },
        400,
      )
    );
  });
}
