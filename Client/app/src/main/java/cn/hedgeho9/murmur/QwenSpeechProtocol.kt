/** 百炼外部 WebSocket 协议适配；输出复用生成的 ASR 事件，不定义业务 REST 契约。 */
package cn.hedgeho9.murmur

import cn.hedgeho9.murmur.api.infrastructure.Serializer
import cn.hedgeho9.murmur.api.models.*
import java.util.UUID
import kotlinx.serialization.json.*

/** 单次直连任务的协议状态；只持有模型和任务 ID，不持有密钥或录音。 */
class QwenSpeechProtocol(private val model: String) {
    private val taskId = UUID.randomUUID().toString()
    private val assembler = TranscriptAssembler()
    private var text = ""

    /** 返回开始或结束控制帧，音频通过二进制帧独立传输。 */
    fun control(start: Boolean): String =
        buildJsonObject {
                putJsonObject("header") {
                    put("action", if (start) "run-task" else "finish-task")
                    put("task_id", taskId)
                    put("streaming", "duplex")
                }
                putJsonObject("payload") {
                    if (start) {
                        put("task_group", "audio")
                        put("task", "asr")
                        put("function", "recognition")
                        put("model", model)
                        putJsonObject("parameters") {
                            put("format", "pcm")
                            put("sample_rate", 16000)
                        }
                    }
                    putJsonObject("input") {}
                }
            }
            .toString()

    /** 将上游片段转换为生成事件；未知事件忽略，缺失片段标识则拒绝拼接。 */
    fun decode(raw: String): AsrServerEvent? {
        val event = Serializer.kotlinxSerializationJson.parseToJsonElement(raw).jsonObject
        val header = event["header"]?.jsonObject ?: error("缺少事件头")
        when (header["event"]?.jsonPrimitive?.content) {
            "task-started" -> return AsrServerEvent.ReadyWrapper(AsrReady(AsrReady.Type.ready))
            "result-generated" -> {
                val sentence =
                    event["payload"]
                        ?.jsonObject
                        ?.get("output")
                        ?.jsonObject
                        ?.get("sentence")
                        ?.jsonObject ?: return null
                val value = sentence["text"]?.jsonPrimitive?.content ?: return null
                val id =
                    (sentence["sentence_id"] ?: sentence["begin_time"])
                        ?.jsonPrimitive
                        ?.contentOrNull ?: error("缺少片段标识")
                val final = sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull == true
                text = assembler.accept(id, value, final)
                return AsrServerEvent.TranscriptWrapper(
                    AsrTranscript(AsrTranscript.Type.transcript, id, value, final)
                )
            }
            "task-finished" ->
                return AsrServerEvent.CompletedWrapper(
                    AsrCompleted(AsrCompleted.Type.completed, text)
                )
            "task-failed" ->
                return AsrServerEvent.ErrorWrapper(
                    AsrError(AsrError.Type.error, "UPSTREAM_FAILED", "语音服务处理失败")
                )
            else -> return null
        }
    }
}
