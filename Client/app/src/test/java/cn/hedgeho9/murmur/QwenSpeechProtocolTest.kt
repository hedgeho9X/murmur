/** 验证直连片段修订和任务收尾，不连接供应商或使用真实录音。 */
package cn.hedgeho9.murmur

import cn.hedgeho9.murmur.api.models.AsrServerEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 外部协议应与中转链路产生相同的最终文本与片段标识。 */
class QwenSpeechProtocolTest {
    /** 同片段 partial 替换，定稿不会被迟到事件覆盖，后续片段按顺序拼接。 */
    @Test
    fun revisionsAndCompletion() {
        val protocol = QwenSpeechProtocol("fixture")
        fun result(id: Int, text: String, final: Boolean): AsrServerEvent? =
            protocol.decode(
                """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":$id,"text":"$text","sentence_end":$final}}}}"""
            )
        result(0, "你", false)
        result(0, "你好", true)
        result(0, "旧结果", false)
        result(1, "世界", true)
        val completed =
            protocol.decode("""{"header":{"event":"task-finished"}}""")
                as AsrServerEvent.CompletedWrapper
        assertEquals("你好世界", completed.value.text)
        val start = Json.parseToJsonElement(protocol.control(true)).jsonObject
        val finish = Json.parseToJsonElement(protocol.control(false)).jsonObject
        assertEquals(
            start["header"]!!.jsonObject["task_id"],
            finish["header"]!!.jsonObject["task_id"],
        )
    }

    /** 有正文但没有稳定片段 ID 时必须报错，避免重复追加或覆盖错误句子。 */
    @Test(expected = IllegalStateException::class)
    fun missingSegmentIdentityFails() {
        QwenSpeechProtocol("fixture")
            .decode(
                """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"test"}}}}"""
            )
    }
}
