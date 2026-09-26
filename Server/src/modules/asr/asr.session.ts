/**
 * 管理单次录音对应的上游 WebSocket 和识别片段，向客户端发送统一事件。
 * 不保存音频或创建帖子；客户端断开时立即终止上游，防止遗留计费任务。
 */
import { AsrClientEvent, type ServerEvent } from "./asr.contracts.js";
import { randomUUID } from "node:crypto";
import WebSocket from "ws";
/** 单次识别依赖的服务端配置；密钥不得发送到客户端。 */
export type AsrOptions = { endpoint: string; key: string; model: string };
/** 统一转写事件；同一 segment_id 的中间结果必须替换而不是重复追加。 */
export type TranscriptEvent = {
  type: "transcript";
  segment_id: string;
  text: string;
  is_final: boolean;
};
/** 使用明确的生命周期管理音频转发、收尾超时和上游异常。 */
export class AsrSession {
  private upstream?: WebSocket;
  private phase: "idle" | "connecting" | "ready" | "finishing" | "closed" =
    "idle";
  private task = randomUUID();
  private timeout?: ReturnType<typeof setTimeout>;
  private lifetime?: ReturnType<typeof setTimeout>;
  private bytes = 0;
  private segments = new Map<string, TranscriptEvent>();
  /** 注入配置、客户端发送/关闭函数和资源释放回调；构造不连接供应商。 */
  constructor(
    private options: AsrOptions,
    private send: (event: ServerEvent) => void,
    private close: () => void,
    private release: () => void,
  ) {
    this.deadline(15000, "START_TIMEOUT");
  }
  /** 接收 JSON 控制帧或 PCM 字节；不支持的协议与超额缓冲会明确终止。 */
  receive(data: string | ArrayBuffer | Uint8Array) {
    if (this.phase === "closed") return;
    if (typeof data !== "string") {
      const buffer = Buffer.from(
        data instanceof ArrayBuffer ? new Uint8Array(data) : data,
      );
      if (this.phase !== "ready")
        return this.fail(
          "AUDIO_NOT_READY",
          "Wait for ready before sending audio",
        );
      if (!buffer.length || buffer.length % 2 || buffer.length > 32000)
        return this.fail("INVALID_AUDIO", "Expected bounded PCM16 mono frames");
      this.bytes += buffer.length;
      if (
        this.bytes > 19_200_000 ||
        (this.upstream?.bufferedAmount ?? 0) > 128000
      )
        return this.fail(
          "AUDIO_LIMIT",
          "Audio duration or network buffer exceeded",
        );
      this.upstream!.send(buffer);
      this.deadline(30000, "AUDIO_TIMEOUT");
      return;
    }
    let message: Record<string, unknown>;
    try {
      message = AsrClientEvent.parse(JSON.parse(data));
      if (!message || typeof message !== "object") throw Error();
    } catch {
      return this.fail("INVALID_CONTROL", "Expected a JSON control message");
    }
    if (message.type === "cancel") {
      this.dispose();
      this.close();
      return;
    }
    if (message.type === "start" && this.phase === "idle") {
      if (
        message.sample_rate !== 16000 ||
        message.channels !== 1 ||
        message.format !== "pcm_s16le"
      )
        return this.fail("INVALID_FORMAT", "Use PCM16 mono at 16000 Hz");
      this.start();
      return;
    }
    if (message.type === "finish" && this.phase === "ready") {
      this.phase = "finishing";
      this.deadline(15000, "FINALIZATION_TIMEOUT");
      this.upstream!.send(
        JSON.stringify({
          header: {
            action: "finish-task",
            task_id: this.task,
            streaming: "duplex",
          },
          payload: { input: {} },
        }),
      );
      return;
    }
    this.fail(
      "INVALID_STATE",
      "Control message is not valid in the current state",
    );
  }
  /** 建立上游连接并提交识别任务；只有 task-started 后客户端才可以发送音频。 */
  private start() {
    this.phase = "connecting";
    this.deadline(15000, "CONNECT_TIMEOUT");
    this.lifetime = setTimeout(
      () =>
        this.fail(
          "DURATION_LIMIT",
          "Maximum recording duration is ten minutes",
        ),
      600000,
    );
    const upstream = new WebSocket(this.options.endpoint, {
      headers: { Authorization: `Bearer ${this.options.key}` },
      handshakeTimeout: 12000,
      maxPayload: 1024 * 1024,
    });
    this.upstream = upstream;
    upstream.on("open", () =>
      upstream.send(
        JSON.stringify({
          header: {
            action: "run-task",
            task_id: this.task,
            streaming: "duplex",
          },
          payload: {
            task_group: "audio",
            task: "asr",
            function: "recognition",
            model: this.options.model,
            parameters: { format: "pcm", sample_rate: 16000 },
            input: {},
          },
        }),
      ),
    );
    upstream.on("message", (raw) => {
      if (this.phase === "closed") return;
      try {
        const result = JSON.parse(raw.toString());
        const event = result.header?.event;
        if (event === "task-started") {
          this.phase = "ready";
          this.deadline(30000, "AUDIO_TIMEOUT");
          this.send({ type: "ready" });
        } else if (event === "result-generated") {
          const sentence = result.payload?.output?.sentence;
          if (!sentence || typeof sentence.text !== "string") return;
          const id = sentence.sentence_id ?? sentence.begin_time;
          if (id === undefined || id === null)
            return this.fail("UPSTREAM_PROTOCOL", "Missing segment identity");
          const segment: TranscriptEvent = {
            type: "transcript",
            segment_id: String(id),
            text: sentence.text,
            is_final: sentence.sentence_end === true,
          };
          if (this.segments.get(segment.segment_id)?.is_final) return;
          this.segments.set(segment.segment_id, segment);
          this.send(segment);
        } else if (event === "task-finished") {
          if (this.phase !== "finishing")
            return this.fail(
              "UPSTREAM_CLOSED",
              "Recognition ended before stop was requested",
            );
          this.send({
            type: "completed",
            text: [...this.segments.values()].map((s) => s.text).join(""),
          });
          this.dispose();
          this.close();
        } else if (event === "task-failed")
          this.fail("UPSTREAM_FAILED", "Speech recognition failed");
      } catch {
        this.fail("UPSTREAM_PROTOCOL", "Invalid recognition response");
      }
    });
    upstream.on("error", () =>
      this.fail("UPSTREAM_UNAVAILABLE", "Speech service is unavailable"),
    );
    upstream.on("close", () => {
      if (this.phase !== "closed")
        this.fail(
          "UPSTREAM_CLOSED",
          "Speech connection closed before completion",
        );
    });
  }
  /** 替换当前阶段超时，避免连接或收尾永久等待。 */
  private deadline(ms: number, code: string) {
    clearTimeout(this.timeout);
    this.timeout = setTimeout(
      () => this.fail(code, "Speech session timed out"),
      ms,
    );
  }
  /** 发送可公开错误并关闭两端；不暴露供应商原始响应或凭据。 */
  private fail(code: string, message: string) {
    if (this.phase === "closed") return;
    this.send({ type: "error", code, message });
    this.dispose();
    this.close();
  }
  /** 释放计时器、连接和并发名额，可从断线或正常完成路径重复调用。 */
  dispose() {
    if (this.phase === "closed") return;
    this.phase = "closed";
    clearTimeout(this.timeout);
    clearTimeout(this.lifetime);
    this.upstream?.terminate();
    this.release();
  }
}
