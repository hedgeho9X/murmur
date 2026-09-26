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
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/** 客户端页面状态；录音临时状态不写入帖子数据库。 */
data class UiState(
    val draft: Draft = Draft(),
    val editor: Boolean = false,
    val editingMessage: DisplayMessage? = null,
    val editingText: String = "",
    val recording: Boolean = false,
    val finalizing: Boolean = false,
    val busy: Boolean = false,
    val level: Float = 0f,
    val speaking: Boolean = false,
    val captionVisible: Boolean = false,
    val liveText: String = "",
    val page: String = "capture",
    val query: String = "",
    val listLoading: Boolean = false,
    val listError: Boolean = false,
    val detailLoading: Boolean = false,
    val tagSuggestions: List<cn.hedgeho9.murmur.api.models.TagSuggestion> = emptyList(),
    val selectedTags: List<String> = emptyList(),
    val posts: List<Post> = emptyList(),
    val cursor: String? = null,
    val syncStates: Map<String, String> = emptyMap(),
    val syncError: String? = null,
    val details: List<DisplayMessage> = emptyList(),
    val selected: String? = null,
    val error: String? = null,
    val base: String = "",
    val token: String = "",
)

/** 单用户应用状态，网络任务在协程中执行，失败保留原始草稿。 */
class MurmurModel(application: Application) : AndroidViewModel(application) {
    private val store = DraftStore(application)
    private val database = NotesDatabase.get(application)
    private var localLimit = 40
    private val mutable = MutableStateFlow(UiState(base = store.baseUrl(), token = store.token()))
    val state = mutable.asStateFlow()
    private var speech: SpeechRecorder? = null
    private var detailJob: Job? = null
    private var tagJob: Job? = null
    private var silentJob: Job? = null
    private var finishJob: Job? = null

    init {
        try {
            mutable.value = mutable.value.copy(draft = store.load())
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "草稿文件无法读取，请保留文件并检查存储")
        }
        viewModelScope.launch {
            val oldScope =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        (state.value.base.trimEnd('/') + "\n" + state.value.token).toByteArray()
                    )
                    .joinToString("") { "%02x".format(it) }
            withContext(Dispatchers.IO) { database.adoptConnectionScope(oldScope, scopeKey()) }
            database.changes.collectLatest { refreshLocal() }
        }
        NotesSync.periodic(application)
        NotesSync.schedule(application)
    }

    /** 当前数据分区只依赖连接身份，不存储明文凭据。 */
    private fun scopeKey() = connectionScope(state.value.base)

    /** 从 SQLite 更新显示；异步完成时重新核对连接，避免切换服务器串数据。 */
    private suspend fun refreshLocal() {
        val current = state.value
        val scope = scopeKey()
        val limit = localLimit
        val rows = withContext(Dispatchers.IO) { database.list(scope, current.query, limit + 1) }
        val status = withContext(Dispatchers.IO) { database.statuses(scope) }
        val lastError = withContext(Dispatchers.IO) { database.syncStatus(scope) }
        val detail =
            current.selected?.let { withContext(Dispatchers.IO) { database.get(scope, it) } }
        if (scope != scopeKey() || current.query != state.value.query || limit != localLimit) return
        change {
            it.copy(
                posts = rows.take(limit),
                cursor = if (rows.size > limit) "local:$limit" else null,
                syncStates = status,
                syncError = lastError,
                details =
                    if (it.page == "detail" && it.selected == current.selected && detail != null)
                        detail.messages
                    else it.details,
                selectedTags =
                    if (it.page == "detail" && it.selected == current.selected && detail != null)
                        detail.post.tags
                    else it.selectedTags,
            )
        }
    }

    /** 手动重试保留的离线操作，并触发一次远端刷新。 */
    fun retrySync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.retry(scopeKey()) }
            NotesSync.schedule(getApplication(), immediate = true)
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
        if (state.value.editingMessage != null) change { it.copy(editingText = text) }
        else draft(state.value.draft.copy(text = text))
    }

    /** 打开或收起共用编辑弹层；收尾阶段不允许手动覆盖转写。 */
    fun editor(open: Boolean) {
        change { it.copy(editor = open, editingMessage = null, editingText = "") }
    }

    /** 打开已发布用户笔记的文字编辑，不覆盖尚未发送的新草稿。 */
    fun editPublished(message: DisplayMessage) {
        if (message.role != "user" || state.value.busy) return
        change { it.copy(editor = true, editingMessage = message, editingText = message.text) }
    }

    /** 修改先写本地数据库与队列，立即返回详情，云端失败不丢失编辑。 */
    private fun savePublished() {
        val current = state.value
        val message = current.editingMessage ?: return
        val id = current.selected ?: return
        if (current.busy) return
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.edit(
                        connectionScope(current.base),
                        id,
                        message.id,
                        current.editingText,
                    )
                }
                change {
                    it.copy(editor = false, editingMessage = null, editingText = "", busy = false)
                }
                refreshLocal()
                NotesSync.schedule(getApplication())
            } catch (_: Exception) {
                change { it.copy(busy = false, error = "本地保存失败，修改仍保留在编辑框中") }
            }
        }
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
            detailJob?.cancel()
            tagJob?.cancel()
            change {
                it.copy(
                    base = store.baseUrl(),
                    token = token,
                    posts = emptyList(),
                    details = emptyList(),
                    cursor = null,
                    selected = null,
                    syncStates = emptyMap(),
                    syncError = null,
                    query = "",
                    tagSuggestions = emptyList(),
                    selectedTags = emptyList(),
                )
            }
            viewModelScope.launch { refreshLocal() }
            NotesSync.schedule(getApplication(), immediate = true)
        } catch (_: Exception) {
            error("请填写有效的 HTTP(S) 地址")
        }
    }

    /** 导入图片到应用私有目录并转为有界 JPEG，避免临时 URI 权限失效。 */
    fun addPhoto(uri: Uri) {
        addPhotos(listOf(uri))
    }

    /** 相机 JPEG 原样进入私有草稿目录，保留像素与 EXIF；仅预览解码，不重编码原件。 */
    fun addCapturedPhoto(file: File, shutterAt: Long) {
        if (state.value.busy) return
        change { it.copy(busy = true) }
        viewModelScope.launch {
            var oversized = false
            try {
                val path =
                    withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        android.util.Log.d(
                            "MurmurCapture",
                            "size=${bounds.outWidth}x${bounds.outHeight}",
                        )
                        check(bounds.outWidth > 0 && bounds.outHeight > 0)
                        oversized =
                            file.length() > 10 * 1024 * 1024 ||
                                bounds.outWidth.toLong() * bounds.outHeight > 25_000_000
                        val target = File(getApplication<Application>().filesDir, "${newId()}.jpg")
                        if (!file.renameTo(target)) {
                            file.copyTo(target)
                            file.delete()
                        }
                        target.absolutePath
                    }
                run {
                    CaptureTiming.track(path, shutterAt)
                    val context = getApplication<Application>()
                    val decoded =
                        context.imageLoader.execute(
                            coil.request.ImageRequest.Builder(context)
                                .data(File(path))
                                .size(context.resources.displayMetrics.widthPixels)
                                .precision(coil.size.Precision.INEXACT)
                                .build()
                        )
                    if (decoded is coil.request.ErrorResult) throw decoded.throwable
                    val prepared = state.value.draft.copy(images = state.value.draft.images + path)
                    withContext(Dispatchers.IO) { store.save(prepared) }
                    change { it.copy(draft = prepared) }
                    // 只记录耗时，照片内容和文件路径不进入日志。
                    android.util.Log.d(
                        "MurmurCapture",
                        "draftReadyMs=${android.os.SystemClock.elapsedRealtime()-shutterAt}",
                    )
                }
                if (oversized) error("原图已保留；照片超出服务器上传限制，未自动压缩")
            } catch (_: Exception) {
                error("照片保存失败")
            } finally {
                change { it.copy(busy = false) }
            }
        }
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
                level = 0f,
                captionVisible = false,
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

    /** 本地保存与队列提交成功后才清空草稿；不等待上传或远端创建。 */
    fun send() {
        if (state.value.editingMessage != null) {
            savePublished()
            return
        }
        val current = state.value
        val d = current.draft
        if (current.busy || current.finalizing || (d.text.isBlank() && d.images.isEmpty())) return
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val next = Draft()
                withContext(Dispatchers.IO) {
                    database.create(connectionScope(current.base), d)
                    store.save(next)
                }
                change { it.copy(draft = next, editor = false, busy = false) }
                open(d.postId ?: d.id)
                NotesSync.schedule(getApplication())
            } catch (_: Exception) {
                change { it.copy(busy = false, error = "本地保存失败，草稿未清除") }
            }
        }
    }

    /** 更新列表查询文本，点击搜索后发起请求。 */
    fun query(text: String) {
        change { it.copy(query = text) }
        if (text.startsWith("#")) suggestTags(text.drop(1))
        else {
            tagJob?.cancel()
            change { it.copy(tagSuggestions = emptyList()) }
        }
    }

    /** 防抖获取真实已有标签，取消旧请求避免补全结果串线。 */
    fun suggestTags(prefix: String) {
        tagJob?.cancel()
        tagJob =
            viewModelScope.launch {
                delay(180)
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            database
                                .list(scopeKey(), "", Int.MAX_VALUE)
                                .flatMap { it.tags }
                                .groupingBy { it }
                                .eachCount()
                                .filterKeys { it.startsWith(prefix.trim().lowercase()) }
                                .entries
                                .sortedWith(
                                    compareByDescending<Map.Entry<String, Int>> { it.value }
                                        .thenBy { it.key }
                                )
                                .take(12)
                                .map {
                                    cn.hedgeho9.murmur.api.models.TagSuggestion(it.key, it.value)
                                }
                        }
                    change { it.copy(tagSuggestions = result) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    change { it.copy(tagSuggestions = emptyList()) }
                }
            }
    }

    /** 把标签补全项作为精确搜索条件，关闭候选列表。 */
    fun searchTag(tag: String) {
        tagJob?.cancel()
        change { it.copy(query = "#" + tag, tagSuggestions = emptyList()) }
        history()
    }

    /** 列表先从本地读取，后台独立刷新，不等待网络才能显示。 */
    fun showHistory() {
        detailJob?.cancel()
        change { it.copy(page = "history", detailLoading = false) }
        viewModelScope.launch { refreshLocal() }
        NotesSync.schedule(getApplication())
    }

    /** 本地按页增加可见条目；重新查询同时请求一次后台同步。 */
    fun history(more: Boolean = false) {
        if (more) localLimit += 40 else localLimit = 40
        change {
            it.copy(page = "history", listLoading = true, listError = false, detailLoading = false)
        }
        viewModelScope.launch {
            try {
                refreshLocal()
            } finally {
                change { it.copy(listLoading = false) }
            }
        }
        if (!more) NotesSync.schedule(getApplication())
    }

    /** 详情优先使用完整本地缓存；缺失时取回并保存，不覆盖待同步修改。 */
    fun open(id: String) {
        detailJob?.cancel()
        val scope = scopeKey()
        val base = state.value.base
        val token = state.value.token
        change {
            it.copy(
                page = "detail",
                selected = id,
                details = emptyList(),
                selectedTags = emptyList(),
                detailLoading = true,
            )
        }
        detailJob =
            viewModelScope.launch {
                try {
                    val cached = withContext(Dispatchers.IO) { database.get(scope, id) }
                    if (cached != null)
                        change {
                            it.copy(
                                details = cached.messages,
                                selectedTags = cached.post.tags,
                                detailLoading = false,
                            )
                        }
                    else {
                        val detail = MurmurApi(base, token).detail(id)
                        withContext(Dispatchers.IO) { database.cache(scope, detail.post, detail) }
                        if (scope == scopeKey())
                            change {
                                it.copy(
                                    details = detail.messages,
                                    selectedTags = detail.post.tags,
                                    detailLoading = false,
                                )
                            }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    change { it.copy(detailLoading = false, error = "详情尚未缓存：${syncError(e)}") }
                }
            }
    }

    /** 返回取景页并取消详情读取，保留未发送草稿和列表位置。 */
    fun capture() {
        detailJob?.cancel()
        change { it.copy(page = "capture", detailLoading = false) }
    }

    /** 删除先在本地隐藏并排队，网络失败时仍保留删除任务。 */
    fun delete() {
        val id = state.value.selected ?: return
        val scope = scopeKey()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { database.delete(scope, id) }
                showHistory()
                NotesSync.schedule(getApplication())
            } catch (_: Exception) {
                error("本地删除失败，请重试")
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
