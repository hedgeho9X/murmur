/** 记录页、草稿和帖子列表的状态容器，协调持久化、录音与 API，不持有 Activity。 */
package cn.hedgeho9.murmur

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.hedgeho9.murmur.api.models.Post
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 客户端页面状态；录音临时状态不写入帖子数据库。 */
data class UiState(
    val draft: Draft = Draft(),
    val editor: Boolean = false,
    val recording: Boolean = false,
    val finalizing: Boolean = false,
    val busy: Boolean = false,
    val level: Float = 0f,
    val speaking: Boolean = false,
    val captionVisible: Boolean = false,
    val liveText: String = "",
    val page: String = "capture",
    val query: String = "",
    val imagesOnly: Boolean = false,
    val posts: List<Post> = emptyList(),
    val cursor: String? = null,
    val details: List<DisplayMessage> = emptyList(),
    val selected: String? = null,
    val error: String? = null,
    val base: String = "",
    val token: String = "",
)

/** 单用户应用状态，网络任务在协程中执行，失败保留原始草稿。 */
class MurmurModel(application: Application) : AndroidViewModel(application) {
    private val store = DraftStore(application)
    private val mutable = MutableStateFlow(UiState(base = store.baseUrl(), token = store.token()))
    val state = mutable.asStateFlow()
    private var speech: SpeechRecorder? = null
    private var listJob: Job? = null
    private var silentJob: Job? = null
    private var finishJob: Job? = null

    init {
        try {
            mutable.value = mutable.value.copy(draft = store.load())
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "草稿文件无法读取，请保留文件并检查存储")
        }
    }

    /** 在主线程更新状态，供 UI 订阅。 */
    private fun change(block: (UiState) -> UiState) {
        mutable.value = block(mutable.value)
    }

    /** 保存草稿后更新界面，失败时显示错误而不丢弃内存内容。 */
    private fun draft(d: Draft) {
        change { it.copy(draft = d) }
        try {
            store.save(d)
        } catch (_: Exception) {
            change { it.copy(error = "草稿保存失败，请检查剩余空间") }
        }
    }

    /** 修改未发送正文，不改写任何服务端消息。 */
    fun edit(text: String) {
        draft(state.value.draft.copy(text = text))
    }

    /** 打开或收起共用编辑弹层；收尾阶段不允许手动覆盖转写。 */
    fun editor(open: Boolean) {
        change { it.copy(editor = open) }
    }

    /** 清除已展示的操作错误。 */
    fun clearError() {
        change { it.copy(error = null) }
    }

    /** 显示权限或系统错误，保留草稿。 */
    fun error(message: String) {
        change { it.copy(error = message) }
    }

    /** 保存连接配置并更新 API 调用入口。 */
    fun settings(url: String, token: String) {
        try {
            store.configure(url, token)
            change { it.copy(base = store.baseUrl(), token = token) }
        } catch (_: Exception) {
            error("请填写有效的 HTTP(S) 地址")
        }
    }

    /** 导入图片到应用私有目录并转为有界 JPEG，避免临时 URI 权限失效。 */
    fun addPhoto(uri: Uri) {
        addPhotos(listOf(uri))
    }

    /** 顺序导入一组附件，导入期间锁定发送，避免图片落入下一份草稿。 */
    fun addPhotos(uris: List<Uri>) {
        if (state.value.busy || state.value.recording) return
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val paths =
                    uris.take((6 - state.value.draft.images.size).coerceAtLeast(0)).map { uri ->
                        val path =
                            withContext(Dispatchers.IO) {
                                val context = getApplication<Application>()
                                val bounds =
                                    BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                (context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException())
                                    .use { BitmapFactory.decodeStream(it, null, bounds) }
                                var sample = 1
                                while (
                                    maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000
                                ) sample *= 2
                                val bitmap =
                                    context.contentResolver.openInputStream(uri)?.use {
                                        BitmapFactory.decodeStream(
                                            it,
                                            null,
                                            BitmapFactory.Options().apply { inSampleSize = sample },
                                        )
                                    } ?: throw IllegalStateException()
                                val exif =
                                    context.contentResolver.openInputStream(uri)?.use {
                                        ExifInterface(it)
                                    }
                                val matrix =
                                    Matrix().apply {
                                        if (exif?.isFlipped == true) postScale(-1f, 1f)
                                        postRotate((exif?.rotationDegrees ?: 0).toFloat())
                                    }
                                val oriented =
                                    Bitmap.createBitmap(
                                        bitmap,
                                        0,
                                        0,
                                        bitmap.width,
                                        bitmap.height,
                                        matrix,
                                        true,
                                    )
                                val file = File(context.filesDir, "${newId()}.jpg")
                                file.outputStream().use {
                                    oriented.compress(Bitmap.CompressFormat.JPEG, 85, it)
                                }
                                if (oriented !== bitmap) oriented.recycle()
                                bitmap.recycle()
                                file.absolutePath
                            }
                        path
                    }
                draft(state.value.draft.copy(images = state.value.draft.images + paths))
            } catch (_: Exception) {
                error("图片读取失败")
            } finally {
                change { it.copy(busy = false) }
            }
        }
    }

    /** 从草稿移除附件，不操作已经发送的服务端图片。 */
    fun removePhoto(path: String) {
        draft(
            state.value.draft.copy(
                images = state.value.draft.images - path,
                uploads = state.value.draft.uploads - path,
            )
        )
    }

    /** 建立录音与转写会话，完整识别正文持续保存，静音只控制显示。 */
    fun record() {
        if (state.value.recording || state.value.finalizing || state.value.busy) return
        if (state.value.token.isBlank()) {
            error("请先配置服务器和访问令牌")
            return
        }
        speech?.cancel()
        val visibility = RecordingVisibility()
        silentJob?.cancel()
        val baseline = state.value.draft.text
        change {
            it.copy(
                recording = true,
                finalizing = false,
                editor = false,
                liveText = "",
                speaking = false,
            )
        }
        speech =
            SpeechRecorder(
                    state.value.base,
                    state.value.token,
                    onText = { text ->
                        viewModelScope.launch {
                            draft(
                                state.value.draft.copy(
                                    text =
                                        baseline +
                                            (if (baseline.isNotBlank() && text.isNotBlank()) "\n"
                                            else "") +
                                            text
                                )
                            )
                            if (text.isNotBlank() && text != state.value.liveText)
                                visibility.transcript(android.os.SystemClock.elapsedRealtime())
                            change {
                                it.copy(
                                    liveText = text,
                                    captionVisible =
                                        visibility.caption(android.os.SystemClock.elapsedRealtime()),
                                )
                            }
                        }
                    },
                    onLevel = { level ->
                        viewModelScope.launch {
                            val now = android.os.SystemClock.elapsedRealtime()
                            visibility.audio(level, now)
                            change {
                                it.copy(
                                    level = level,
                                    speaking = visibility.waveform(now),
                                    captionVisible = visibility.caption(now),
                                )
                            }
                        }
                    },
                    onComplete = {
                        viewModelScope.launch {
                            finishJob?.cancel()
                            change {
                                it.copy(
                                    recording = false,
                                    finalizing = false,
                                    editor = true,
                                    speaking = false,
                                )
                            }
                        }
                    },
                    onError = { message ->
                        viewModelScope.launch {
                            finishJob?.cancel()
                            change {
                                it.copy(
                                    recording = false,
                                    finalizing = false,
                                    editor = true,
                                    speaking = false,
                                    error = message,
                                )
                            }
                        }
                    },
                )
                .also { it.start() }
    }

    /** 停止采集并等待尾部文本；超时保留已识别内容，允许继续编辑。 */
    fun stop() {
        if (!state.value.recording) return
        speech?.stop()
        change { it.copy(recording = false, finalizing = true, editor = true, speaking = false) }
        finishJob =
            viewModelScope.launch {
                delay(18000)
                speech?.cancel()
                change { it.copy(finalizing = false, error = "识别收尾超时，已保留当前文字") }
            }
    }

    /** 应用进入后台时停止录音，避免隐式后台采集。 */
    fun background() {
        if (state.value.recording) stop()
    }

    /** 上传附件并创建帖子或追加消息，失败保留 ID 和已完成上传供用户重试。 */
    fun send() {
        val d = state.value.draft
        if (state.value.busy || state.value.finalizing || d.text.isBlank() && d.images.isEmpty())
            return
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val api = MurmurApi(state.value.base, state.value.token)
                for (path in d.images) {
                    if (state.value.draft.uploads[path] == null) {
                        val id = api.upload(path)
                        draft(
                            state.value.draft.copy(
                                uploads = state.value.draft.uploads + (path to id)
                            )
                        )
                    }
                }
                val id = api.send(state.value.draft)
                draft(Draft())
                change { it.copy(editor = false, busy = false) }
                open(id)
            } catch (_: Exception) {
                change { it.copy(busy = false, error = "保存失败，草稿已保留；请检查连接后重试") }
            }
        }
    }

    /** 更新列表查询文本，点击搜索后发起请求。 */
    fun query(text: String) {
        change { it.copy(query = text) }
    }

    /** 切换照片筛选并重新读取第一页。 */
    fun filterImages(value: Boolean) {
        change { it.copy(imagesOnly = value) }
        history()
    }

    /** 从服务端读取列表，按 cursor 加载更多，不重复清空已有页。 */
    fun history(more: Boolean = false) {
        listJob?.cancel()
        change { it.copy(page = "history", busy = true) }
        listJob =
            viewModelScope.launch {
                try {
                    val page =
                        MurmurApi(state.value.base, state.value.token)
                            .list(
                                if (more) state.value.cursor else null,
                                state.value.query,
                                state.value.imagesOnly,
                            )
                    change {
                        it.copy(
                            posts =
                                if (more) (it.posts + page.items).distinctBy { p -> p.id }
                                else page.items,
                            cursor = page.nextCursor,
                            busy = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    change { it.copy(busy = false, error = "读取记录失败，请检查服务器设置") }
                }
            }
    }

    /** 打开已存在帖子，加载正文及私有图片链接。 */
    fun open(id: String) {
        change { it.copy(page = "detail", selected = id, busy = true) }
        viewModelScope.launch {
            try {
                val rows = MurmurApi(state.value.base, state.value.token).detail(id)
                change { it.copy(details = rows, busy = false) }
            } catch (_: Exception) {
                change { it.copy(busy = false, error = "帖子读取失败") }
            }
        }
    }

    /** 返回取景页，保留当前未发送草稿。 */
    fun capture() {
        change { it.copy(page = "capture") }
    }

    /** 为已存在帖子准备补充草稿；不丢弃其他未发送内容。 */
    fun reply() {
        if (state.value.draft.text.isNotBlank() || state.value.draft.images.isNotEmpty()) {
            editor(true)
            return
        }
        draft(Draft(postId = state.value.selected))
        editor(true)
    }

    /** 删除当前帖子后回到列表，不删除其他草稿。 */
    fun delete() {
        val id = state.value.selected ?: return
        viewModelScope.launch {
            try {
                MurmurApi(state.value.base, state.value.token).delete(id)
                history()
            } catch (_: Exception) {
                error("删除失败")
            }
        }
    }

    /** 释放当前录音及等待任务，不清空持久化草稿。 */
    override fun onCleared() {
        speech?.cancel()
        silentJob?.cancel()
        finishJob?.cancel()
    }
}
