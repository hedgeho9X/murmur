/** 将音量与识别结果的显示窗口分开，防止低音量或迟到的转写被同一个静音门槛隐藏。 */
package cn.hedgeho9.murmur

/** 单次录音的显示时钟；不影响完整转写保存或 ASR 音频发送。 */
class RecordingVisibility {
    private var lastSound = Long.MIN_VALUE / 2
    private var lastText = Long.MIN_VALUE / 2

    /** 根据输入音量更新语音活动，低于门槛的稳定背景不刷新活动窗口。 */
    fun audio(level: Float, now: Long) {
        if (level >= 0.002f) lastSound = now
    }

    /** 收到非空的新识别结果时提供短暂可读窗口，允许用户看见延迟到达的最后一句。 */
    fun transcript(now: Long) {
        lastText = now
    }

    fun waveform(now: Long) = now - lastSound < 700

    fun caption(now: Long) = waveform(now) || now - lastText < 1400
}
