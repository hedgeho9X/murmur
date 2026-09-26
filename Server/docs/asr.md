# ASR WebSocket

`GET /api/v1/asr/stream` 使用与其他业务接口相同的 Bearer 认证。仅支持单声道、16 kHz、PCM signed 16-bit little-endian；每段录音一对连接。

客户端发送 `type=start, format=pcm_s16le, sample_rate=16000, channels=1`，收到 `type=ready` 后发送二进制音频。推荐每 100ms 一帧（3200 字节）。发送最后音频帧后再发送 `type=finish`，等待 `type=completed` 才结束会话。取消使用 `type=cancel`。

后端事件：

- ready：上游任务已建立。
- transcript：segment_id、text、is_final。同 ID 的 partial 替换；final 定稿后不再被迟到的 partial 覆盖。
- completed：包含完整 text，代表已收到供应商 task-finished。
- error：code、message。保留客户端已识别文字，不能伪装为完成。

服务端配置 `ASR_API_KEY`、`ASR_ENDPOINT`、`ASR_MODEL`。供应商密钥只存在私有 .env；Android 只持有自己的后端访问令牌。未配置返回 503，不静默回退到模拟识别。

每帧最多 32000 字节，连接消息最多 64000 字节，录音最长十分钟。连接、等待音频和最终收尾均有超时；客户端断开立即取消供应商任务。流式音频不写入帖子数据库或 S3。

通过 Hono → Qwen 的本地真实测试（8.84 秒中文合成音频）得到首次文字 344ms、结束发送到完成 297ms，最后一句完整。此记录验证协议，不代表真机或网络环境的固定延迟。

OpenAPI 只描述升级入口，双向事件协议以上述说明为准。生成的 Retrofit ASRApi 不是 WebSocket 客户端；Android 使用独立的 SpeechRecorder 实现连接。

客户端的 JSON 事件类型由 `asr.contracts.ts → OpenAPI → Kotlin` 生成；`api-paths.ts` 从同一份 OpenAPI 生成 ApiPaths。WebSocket 的握手、二进制发送和生命周期仍由传输层实现，不能用普通 Retrofit GET 替代升级连接。
