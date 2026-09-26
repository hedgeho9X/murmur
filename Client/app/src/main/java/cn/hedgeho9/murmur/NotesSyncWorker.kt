/** 持久后台同步：联网约束、串行队列、分阶段错误与增量缓存，不修改在线 ASR 协议。 */
package cn.hedgeho9.murmur

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 调度持久化同步；进程退出后由系统恢复，系统省电策略可能延迟执行。 */
object NotesSync {
    val mutex = Mutex()

    /** 用户保存或重试触发一次联网同步；追加任务避免工作结束边界丢失新操作。 */
    fun schedule(context: Context, immediate: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<NotesSyncWorker>()
                .setInputData(workDataOf("resetFailures" to immediate))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (immediate) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "notes-sync",
                if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
    }

    /** 周期拉取其他设备的变化；不保证严格每十五分钟触发。 */
    fun periodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<NotesSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("notes-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** 一次工作固定当前服务器与凭据；切换配置不会把运行中的请求发到另一个服务器。 */
class NotesSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    /** 先发送本地队列，再分页拉取远端，失败保留所有尚未确认的本地数据。 */
    override suspend fun doWork(): Result =
        NotesSync.mutex.withLock {
            withContext(Dispatchers.IO) {
                val config = DraftStore(applicationContext)
                val base = config.baseUrl()
                val token = config.token()
                if (token.isBlank()) return@withContext Result.success()
                val scope = connectionScope(base)
                val db = NotesDatabase.get(applicationContext)
                if (inputData.getBoolean("resetFailures", false)) db.retry(scope)
                val api = MurmurApi(base, token)
                var transient = false
                var pullError: String? = null
                val blockedPosts = mutableSetOf<String>()
                val queued = db.queue(scope)
                val deletes =
                    queued.filter { it.change.kind == "delete" }.associate { it.postId to it.seq }
                for (row in queued) {
                    ensureActive()
                    if (deletes[row.postId]?.let { it != row.seq } == true) continue
                    if (
                        row.change.kind != "delete" && (row.blocked || row.postId in blockedPosts)
                    ) {
                        blockedPosts.add(row.postId)
                        continue
                    }
                    try {
                        when (row.change.kind) {
                            "create",
                            "append" -> {
                                var draft = requireNotNull(row.change.draft)
                                for (path in draft.images) {
                                    val imageId = api.uploadQueued(path, row.seq, scope, db)
                                    draft = draft.copy(uploads = draft.uploads + (path to imageId))
                                }
                                atStage("保存云端笔记") { api.send(draft) }
                            }
                            "edit" ->
                                atStage("同步文字修改") {
                                    api.editMessage(
                                        row.postId,
                                        api.messageId(
                                            row.postId,
                                            requireNotNull(row.change.messageId),
                                        ),
                                        row.change.text,
                                    )
                                }
                            "delete" -> atStage("同步删除") { api.delete(row.postId) }
                            else -> error("Unsupported local operation")
                        }
                        if (row.change.kind == "delete")
                            db.acknowledgeThrough(scope, row.postId, row.seq)
                        else db.acknowledge(row.seq)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val retry = retryable(e)
                        db.failed(row.seq, syncError(e), !retry)
                        blockedPosts.add(row.postId)
                        transient = transient || retry
                    }
                }
                try {
                    val seen = mutableSetOf<String>()
                    var cursor: String? = null
                    do {
                        ensureActive()
                        val page = api.list(cursor)
                        for (post in page.items) {
                            val id = post.id.toString()
                            seen.add(id)
                            val cached = db.get(scope, id)
                            if (db.queue(scope).any { it.postId == id }) continue
                            if (
                                cached?.post?.updatedAt != post.updatedAt ||
                                    cached.messages.any { m ->
                                        m.images.any { it.startsWith("http") }
                                    }
                            ) {
                                val downloads = linkedMapOf<String, String>()
                                val detail =
                                    api.detail(id) { imageId, url ->
                                        db.media(scope, imageId)?.takeIf { File(it).exists() }
                                            ?: url.also { downloads[imageId] = it }
                                    }
                                db.cache(scope, detail.post, detail)
                                val paths = mutableMapOf<String, String>()
                                for ((imageId, url) in downloads) {
                                    try {
                                        val directory =
                                            File(applicationContext.filesDir, "synced/$scope")
                                                .apply { mkdirs() }
                                        val file = File(directory, "$imageId.image")
                                        api.downloadImage(url, file)
                                        db.media(scope, imageId, file.absolutePath)
                                        paths[url] = file.absolutePath
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        transient = transient || retryable(e)
                                        pullError = "图片缓存：${syncError(e)}"
                                    }
                                }
                                db.cache(
                                    scope,
                                    detail.post,
                                    detail.copy(
                                        messages =
                                            detail.messages.map { m ->
                                                m.copy(images = m.images.map { paths[it] ?: it })
                                            }
                                    ),
                                )
                            }
                        }
                        cursor = page.nextCursor
                    } while (cursor != null)
                    db.reconcile(scope, seen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    transient = transient || retryable(e)
                    pullError = "获取云端笔记：${syncError(e)}"
                }
                db.syncStatus(scope, pullError)
                if (transient) Result.retry() else Result.success()
            }
        }
}
