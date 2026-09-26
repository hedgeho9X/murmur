/** 验证稳定草稿标识和流式识别片段合并，不依赖真实麦克风或供应商 API。 */
package cn.hedgeho9.murmur

import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordStateTest {
    /** UUIDv7 与正文编辑解耦，序列化恢复后沿用同一 ID。 */
    @Test
    fun draftIdentitySurvivesEdits() {
        val first = Draft()
        val edited = first.copy(text = "新的想法")
        val restored = Json.decodeFromString<Draft>(Json.encodeToString(edited))
        assertEquals(7, UUID.fromString(first.id).version())
        assertEquals(2, UUID.fromString(first.id).variant())
        assertEquals(first.id, restored.id)
    }

    /** 当前片段替换，最终片段只保留一次，下一段按顺序追加。 */
    @Test
    fun partialsReplaceAndFinalsDoNotDuplicate() {
        val a = TranscriptAssembler()
        assertEquals("你", a.accept("1", "你", false))
        assertEquals("你好", a.accept("1", "你好", false))
        assertEquals("你好。", a.accept("1", "你好。", true))
        assertEquals("你好。", a.accept("1", "迟到内容", false))
        assertEquals("你好。世界", a.accept("2", "世界", false))
        assertEquals("你好。世界。", a.accept("2", "世界。", true))
    }

    /** 低音量语音仍显示波形，迟到文本独立获得可读窗口，静音最终恢复留白。 */
    @Test
    fun visibilitySeparatesAudioAndDelayedText() {
        val v = RecordingVisibility()
        assertFalse(v.waveform(1000))
        assertFalse(v.caption(1000))
        v.audio(.004f, 1000)
        assertTrue(v.waveform(1100))
        assertFalse(v.waveform(1800))
        v.transcript(1800)
        assertTrue(v.caption(2000))
        assertFalse(v.waveform(2000))
        assertFalse(v.caption(3300))
    }
}
