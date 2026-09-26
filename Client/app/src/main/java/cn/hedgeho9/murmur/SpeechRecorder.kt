/** Android PCM 采集与语音链路管理；支持服务器转发和短期凭据直连，不接触永久密钥。 */
package cn.hedgeho9.murmur

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import cn.hedgeho9.murmur.api.ApiPaths
import cn.hedgeho9.murmur.api.infrastructure.Serializer
import cn.hedgeho9.murmur.api.models.*
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.*
import okio.ByteString.Companion.toByteString

/** 维护同一片段的临时替换和最终状态；结果顺序按首次出现顺序保留。 */
class TranscriptAssembler {
    private val segments = linkedMapOf<String, Pair<String, Boolean>>()

    /** 合并一个识别片段，已定稿片段不再被迟到的 partial 覆盖，返回全文。 */
    @Synchronized
    fun accept(id: String, text: String, final: Boolean): String {
        if (segments[id]?.second != true) segments[id] = text to final
        return segments.values.joinToString("") { it.first }
    }
}

/** 一次录音会话：先缓存起始音频，ready 后顺序转发，停止后等待 completed。 */
class SpeechRecorder(
    private val base: String,
    private val token: String,
    private val direct: Boolean = false,
    private val onText: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        // 多次录音复用 TLS 会话和调度器；取消录音只关闭自己的 WebSocket。
        private val sharedClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vendor: QwenSpeechProtocol? = null
    private val client = sharedClient
    private val assembler = TranscriptAssembler()
    private var ws: WebSocket? = null
    private var audio: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    private var ready = false
    @Volatile private var stopped = false
    @Volatile private var done = false
    private val pending = ArrayDeque<ByteArray>()
    private var queued = 0

    /** 在已授予麦克风权限后开始采集并连接后端；缓存最多十秒音频。 */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val min =
                AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, 6400),
                )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
            audio = recorder
            recording = true
            recorder.startRecording()
            thread =
                Thread(
                        {
                            val buffer = ByteArray(3200)
                            var frames = 0
                            var peak = 0f
                            try {
                                while (recording) {
                                    val n = recorder.read(buffer, 0, buffer.size)
                                    if (n > 0) {
                                        var sum = 0.0
                                        for (i in 0 until n - 1 step 2) {
                                            val sample =
                                                ((buffer[i].toInt() and 255) or
                                                        (buffer[i + 1].toInt() shl 8))
                                                    .toShort()
                                                    .toDouble() / 32768
                                            sum += sample * sample
                                        }
                                        val level = sqrt(sum / (n / 2)).toFloat()
                                        onLevel(level)
                                        // 调试仅记录采集计数和音量，不记录音频、正文或密钥。
                                        if (BuildConfig.DEBUG) {
                                            peak = maxOf(peak, level)
                                            if (++frames % 20 == 0) {
                                                android.util.Log.d(
                                                    "MurmurAudio",
                                                    "frames=$frames peakRms=$peak",
                                                )
                                                peak = 0f
                                            }
                                        }
                                        forward(buffer.copyOf(n))
                                    } else if (n < 0 && recording) throw IllegalStateException()
                                }
                            } catch (_: Exception) {
                                if (recording) fail("录音中断，已保留文字")
                            } finally {
                                recorder.release()
                                synchronized(this) {
                                    audio = null
                                    stopped = true
                                    if (ready && !done) ws?.send(finishControl())
                                }
                            }
                        },
                        "murmur-audio",
                    )
                    .also { it.start() }
            sessionScope.launch {
                try {
                    connect()
                } catch (_: CancellationException) {
                    if (!done) fail("获取语音凭据超时，请重试")
                } catch (e: Exception) {
                    fail("语音连接失败：${syncError(e)}")
                }
            }
            sessionScope.launch {
                delay(600000)
                fail("录音已达十分钟，已保留文字")
            }
        } catch (_: Exception) {
            fail("麦克风启动失败")
        }
    }

    /** 获取临时凭据并建立当前会话；等待期间采集线程仍缓存起始音频。 */
    private suspend fun connect() {
        val credential =
            if (direct) withTimeout(10000) { MurmurApi(base, token).asrCredentials() } else null
        if (credential != null) {
            require(credential.expiresAt.toLong() > System.currentTimeMillis() / 1000 + 30)
            val uri = java.net.URI(credential.endpoint)
            require(uri.scheme == "wss" && uri.host.endsWith(".aliyuncs.com"))
            vendor = QwenSpeechProtocol(credential.model)
        }
        val url =
            credential?.endpoint
                ?: base
                    .trimEnd('/')
                    .replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://") + ApiPaths.STREAM_ASR
        synchronized(this@SpeechRecorder) {
            if (done) return
            ws =
                client.newWebSocket(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer ${credential?.token ?: token}")
                        .build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            synchronized(this@SpeechRecorder) {
                                if (done) {
                                    webSocket.cancel()
                                    return
                                }
                                webSocket.send(startControl())
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                if (done) return
                                val e =
                                    if (direct) vendor?.decode(text) ?: return
                                    else
                                        Serializer.kotlinxSerializationJson.decodeFromString<
                                            AsrServerEvent
                                        >(
                                            text
                                        )
                                when (e) {
                                    is AsrServerEvent.ReadyWrapper ->
                                        synchronized(this@SpeechRecorder) {
                                            ready = true
                                            while (pending.isNotEmpty()) {
                                                if (
                                                    !webSocket.send(
                                                        pending.removeFirst().toByteString()
                                                    )
                                                )
                                                    throw IllegalStateException("连接已关闭")
                                            }
                                            queued = 0
                                            if (stopped) webSocket.send(finishControl())
                                        }
                                    is AsrServerEvent.TranscriptWrapper ->
                                        onText(
                                            assembler.accept(
                                                e.value.segmentId,
                                                e.value.text,
                                                e.value.isFinal,
                                            )
                                        )
                                    is AsrServerEvent.CompletedWrapper -> {
                                        if (!stopped) {
                                            fail("识别连接提前结束，已保留文字")
                                            return
                                        }
                                        done = true
                                        sessionScope.cancel()
                                        onText(e.value.text)
                                        onComplete()
                                        webSocket.close(1000, "done")
                                    }
                                    is AsrServerEvent.ErrorWrapper -> fail("识别失败：${e.value.code}")
                                }
                            } catch (_: Exception) {
                                fail("识别结果格式错误")
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (!done) fail("语音连接失败${response?.let{"（${it.code}）"}?:""}，已保留文字")
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!done) fail("识别连接提前结束，已保留文字")
                        }
                    },
                )
        }
    }

    /** 根据链路选择开始控制帧；业务事件来自生成模型。 */
    private fun startControl(): String =
        vendor?.control(true)
            ?: Serializer.kotlinxSerializationJson.encodeToString(
                AsrStart(AsrStart.Type.start, AsrStart.Format.pcm_s16le, 16000, 1)
            )

    /** 根据链路选择结束控制帧，保留连接等待最终结果。 */
    private fun finishControl(): String =
        vendor?.control(false)
            ?: Serializer.kotlinxSerializationJson.encodeToString(AsrFinish(AsrFinish.Type.finish))

    /** 顺序发送音频并限制网络积压，不在网络异常时静默丢帧。 */
    @Synchronized
    private fun forward(bytes: ByteArray) {
        if (done) return
        if (!ready) {
            queued += bytes.size
            if (queued > 320000) {
                fail("连接等待过久，请重试")
                return
            }
            pending.add(bytes)
        } else {
            if ((ws?.queueSize() ?: 0) > 128000 || ws?.send(bytes.toByteString()) != true)
                fail("网络过慢，已停止录音并保留文字")
        }
    }

    /** 停止采集；采集线程收尾后发送 finish，保留连接接收最后的识别结果。 */
    fun stop() {
        recording = false
        try {
            audio?.stop()
        } catch (_: Exception) {}
    }

    /** 取消录音和网络连接，用于离开页面或销毁 ViewModel；不清除已保存草稿。 */
    @Synchronized
    fun cancel() {
        done = true
        sessionScope.cancel()
        recording = false
        try {
            audio?.stop()
        } catch (_: Exception) {}
        ws?.cancel()
        pending.clear()
    }

    /** 仅通知一次失败并释放录音与连接。 */
    @Synchronized
    private fun fail(message: String) {
        if (done) return
        cancel()
        onError(message)
    }
}
