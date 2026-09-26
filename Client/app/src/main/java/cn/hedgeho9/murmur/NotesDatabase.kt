/** SQLite 笔记缓存与持久化发送队列；事务保护本地修改，服务器连接之间隔离数据。 */
package cn.hedgeho9.murmur

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.hedgeho9.murmur.api.infrastructure.Serializer
import cn.hedgeho9.murmur.api.models.Post
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 队列项保存不可变操作快照；附件完成记录单独更新，重试使用相同帖子 ID。 */
@Serializable
data class PendingChange(
    val kind: String,
    val draft: Draft? = null,
    val messageId: String? = null,
    val text: String = "",
)

/** 数据库队列行，seq 决定同一帖子的操作顺序。 */
data class QueueRow(
    val seq: Long,
    val postId: String,
    val change: PendingChange,
    val blocked: Boolean,
)

/** 本应用服务端使用单一访问令牌；以服务器地址划分本地数据，修正令牌不改变数据归属。 */
fun connectionScope(base: String): String =
    MessageDigest.getInstance("SHA-256").digest(base.trimEnd('/').toByteArray()).joinToString("") {
        "%02x".format(it)
    }

/** 应用进程共享数据库；调用者在 IO 调度器执行读写，网络请求不持有数据库事务。 */
class NotesDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "notes.db", null, 1) {
    companion object {
        @Volatile private var instance: NotesDatabase? = null

        /** 复用数据库连接及变更通知，避免多个 ViewModel 各持有一套缓存。 */
        fun get(context: Context): NotesDatabase =
            instance
                ?: synchronized(this) {
                    instance ?: NotesDatabase(context.applicationContext).also { instance = it }
                }
    }

    val changes = MutableStateFlow(0L)
    private val codec = Serializer.kotlinxSerializationJson

    /** 首次建表，同时记录笔记、操作快照、上传票据与下载文件映射。 */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes(scope TEXT NOT NULL,id TEXT NOT NULL,summary TEXT NOT NULL,detail TEXT,created INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))"
        )
        db.execSQL(
            "CREATE TABLE outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,post_id TEXT NOT NULL,payload TEXT NOT NULL,error TEXT,blocked INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX outbox_scope ON outbox(scope,seq)")
        db.execSQL(
            "CREATE TABLE uploads(seq INTEGER NOT NULL,path TEXT NOT NULL,ticket TEXT NOT NULL,created INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(seq,path))"
        )
        db.execSQL("CREATE TABLE sync_status(scope TEXT PRIMARY KEY,message TEXT)")
        db.execSQL(
            "CREATE TABLE media(scope TEXT NOT NULL,image_id TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(scope,image_id))"
        )
    }

    /** 升级必须提供明确迁移，不允许通过删表升级丢弃离线笔记。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing notes database migration")
    }

    /** 在单个事务内执行变更，提交后通知界面重新读取。 */
    @Synchronized
    private fun mutate(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            action(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
    }

    /** 保存列表摘要与已知详情；仅供已获得事务的内部路径调用。 */
    private fun put(db: SQLiteDatabase, scope: String, post: Post, detail: DisplayPost?) {
        val created =
            db.rawQuery(
                    "SELECT summary FROM notes WHERE scope=? AND id=?",
                    arrayOf(scope, post.id.toString()),
                )
                .use {
                    if (it.moveToFirst()) codec.decodeFromString<Post>(it.getString(0)).createdAt
                    else post.createdAt
                }
        val summary =
            post.copy(
                createdAt = created,
                preview =
                    post.preview
                        ?: detail
                            ?.messages
                            ?.filter { it.role == "user" }
                            ?.joinToString(" ") { it.text }
                            ?.take(160),
            )
        val v =
            ContentValues().apply {
                put("scope", scope)
                put("id", post.id.toString())
                put("summary", codec.encodeToString(summary))
                put("created", Instant.parse(created).toEpochMilli())
                put("deleted", 0)
                if (detail != null) put("detail", codec.encodeToString(detail.copy(post = summary)))
            }
        if (db.update("notes", v, "scope=? AND id=?", arrayOf(scope, post.id.toString())) == 0)
            db.insertOrThrow("notes", null, v)
    }

    /** 追加一项持久化操作；同一帖子后续修改不覆盖正在发送的快照。 */
    private fun enqueue(db: SQLiteDatabase, scope: String, id: String, change: PendingChange) {
        db.insertOrThrow(
            "outbox",
            null,
            ContentValues().apply {
                put("scope", scope)
                put("post_id", id)
                put("payload", codec.encodeToString(change))
            },
        )
    }

    /** 笔记和发送队列一起落盘；重复点击同一草稿不会产生第二条创建操作。 */
    fun create(scope: String, draft: Draft) {
        mutate { db ->
            val id = draft.postId ?: draft.id
            val existing = get(scope, id)
            if (existing != null && draft.postId == null) {
                if (
                    existing.messages.firstOrNull()?.text != draft.text ||
                        existing.messages.firstOrNull()?.images != draft.images
                )
                    throw LocalConflict()
                return@mutate
            }
            val now = Instant.now().toString()
            val post =
                Post(
                    id = UUID.fromString(id),
                    title = null,
                    tags = localTags(draft.text),
                    createdAt = now,
                    updatedAt = now,
                    preview = draft.text.take(160),
                )
            val message = DisplayMessage("local:${draft.id}", "user", draft.text, draft.images)
            val rows = (existing?.messages ?: emptyList()) + message
            val summary =
                if (existing != null)
                    existing.post.copy(
                        tags = rows.flatMap { localTags(it.text) }.distinct(),
                        preview = rows.joinToString(" ") { it.text }.take(160),
                        updatedAt = now,
                    )
                else post
            put(db, scope, summary, DisplayPost(summary, rows))
            enqueue(
                db,
                scope,
                id,
                PendingChange(if (draft.postId == null) "create" else "append", draft = draft),
            )
        }
    }

    /** 本地覆盖正文并排队，仍保留已有图片，不保留历史版本用于展示。 */
    fun edit(scope: String, id: String, messageId: String, text: String) {
        mutate { db ->
            val cached = get(scope, id) ?: error("笔记尚未缓存，请先联网打开")
            val rows = cached.messages.map { if (it.id == messageId) it.copy(text = text) else it }
            require(rows.any { it.id == messageId }) { "找不到待编辑笔记" }
            val tags = rows.filter { it.role == "user" }.flatMap { localTags(it.text) }.distinct()
            val post =
                cached.post.copy(
                    tags = tags,
                    preview =
                        rows.filter { it.role == "user" }.joinToString(" ") { it.text }.take(160),
                    updatedAt = Instant.now().toString(),
                )
            put(db, scope, post, DisplayPost(post, rows))
            enqueue(db, scope, id, PendingChange("edit", messageId = messageId, text = text))
        }
    }

    /** 删除先写入本地墓碑与队列，避免旧列表响应把笔记重新显示出来。 */
    fun delete(scope: String, id: String) {
        mutate { db ->
            db.execSQL("UPDATE notes SET deleted=1 WHERE scope=? AND id=?", arrayOf(scope, id))
            enqueue(db, scope, id, PendingChange("delete"))
        }
    }

    /** 读取完整缓存；没有详情时返回 null。 */
    @Synchronized
    fun get(scope: String, id: String): DisplayPost? =
        readableDatabase
            .rawQuery(
                "SELECT detail FROM notes WHERE scope=? AND id=? AND deleted=0",
                arrayOf(scope, id),
            )
            .use {
                if (it.moveToFirst() && !it.isNull(0))
                    codec.decodeFromString<DisplayPost>(it.getString(0))
                else null
            }

    /** 按当前搜索条件查询本地摘要，不依赖网络。 */
    @Synchronized
    fun list(scope: String, query: String, limit: Int): List<Post> =
        readableDatabase
            .rawQuery(
                "SELECT summary,detail FROM notes WHERE scope=? AND deleted=0 ORDER BY created DESC,id DESC",
                arrayOf(scope),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val p = codec.decodeFromString<Post>(c.getString(0))
                        val text =
                            if (c.isNull(1)) p.preview.orEmpty()
                            else
                                codec
                                    .decodeFromString<DisplayPost>(c.getString(1))
                                    .messages
                                    .joinToString(" ") { it.text }
                        if (
                            query.isBlank() ||
                                (if (query.startsWith("#"))
                                    query.drop(1).trim().lowercase() in p.tags
                                else
                                    text.contains(query, true) ||
                                        p.title.orEmpty().contains(query, true))
                        )
                            add(if (p.preview == null) p.copy(preview = text.take(160)) else p)
                    }
                }
            }

    /** 缓存远端内容前检查待发送操作和墓碑，避免覆盖本地未同步修改。 */
    fun cache(scope: String, post: Post, detail: DisplayPost? = null) {
        mutate { db ->
            val pending =
                db.rawQuery(
                        "SELECT 1 FROM outbox WHERE scope=? AND post_id=? LIMIT 1",
                        arrayOf(scope, post.id.toString()),
                    )
                    .use { it.moveToFirst() }
            val deleted =
                db.rawQuery(
                        "SELECT deleted FROM notes WHERE scope=? AND id=?",
                        arrayOf(scope, post.id.toString()),
                    )
                    .use { it.moveToFirst() && it.getInt(0) == 1 }
            if (!pending && !deleted) put(db, scope, post, detail)
        }
    }

    /** 全量列表成功完成后移除远端已删除的干净缓存，保留所有本地待同步笔记。 */
    fun reconcile(scope: String, ids: Set<String>) {
        mutate { db ->
            val existing =
                db.rawQuery(
                        "SELECT id FROM notes WHERE scope=? AND NOT EXISTS(SELECT 1 FROM outbox WHERE outbox.scope=notes.scope AND outbox.post_id=notes.id)",
                        arrayOf(scope),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            existing
                .filter { it !in ids }
                .forEach { db.delete("notes", "scope=? AND id=?", arrayOf(scope, it)) }
        }
    }

    /** 按顺序读取发送队列，包括需要人工重试的失败项。 */
    @Synchronized
    fun queue(scope: String): List<QueueRow> =
        readableDatabase
            .rawQuery(
                "SELECT seq,post_id,payload,blocked FROM outbox WHERE scope=? ORDER BY seq",
                arrayOf(scope),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        QueueRow(
                            c.getLong(0),
                            c.getString(1),
                            codec.decodeFromString(c.getString(2)),
                            c.getInt(3) == 1,
                        )
                    )
                }
            }

    /** 返回每条笔记最早一项操作的待同步或失败状态。 */
    @Synchronized
    fun statuses(scope: String): Map<String, String> =
        readableDatabase
            .rawQuery("SELECT post_id,error FROM outbox WHERE scope=? ORDER BY seq", arrayOf(scope))
            .use { c ->
                buildMap {
                    while (c.moveToNext()) if (!containsKey(c.getString(0)))
                        put(c.getString(0), if (c.isNull(1)) "待同步" else c.getString(1))
                }
            }

    /** 删除成功后清除该帖截至删除操作的队列；不清除其他帖子或后来的操作。 */
    fun acknowledgeThrough(scope: String, id: String, seq: Long) {
        mutate { db ->
            db.execSQL(
                "DELETE FROM uploads WHERE seq IN(SELECT seq FROM outbox WHERE scope=? AND post_id=? AND seq<=?)",
                arrayOf(scope, id, seq),
            )
            db.execSQL(
                "DELETE FROM outbox WHERE scope=? AND post_id=? AND seq<=?",
                arrayOf(scope, id, seq),
            )
        }
    }

    /** 成功确认只删除对应序号，不能清掉发送期间新产生的修改。 */
    fun acknowledge(seq: Long) {
        mutate { db ->
            db.delete("uploads", "seq=?", arrayOf(seq.toString()))
            db.delete("outbox", "seq=?", arrayOf(seq.toString()))
        }
    }

    /** 保留失败详情，永久错误等待用户修正配置或手动重试。 */
    fun failed(seq: Long, message: String, blocked: Boolean) {
        mutate { db ->
            db.execSQL(
                "UPDATE outbox SET error=?,blocked=? WHERE seq=?",
                arrayOf(message, if (blocked) 1 else 0, seq),
            )
        }
    }

    /** 用户主动重试时解除当前连接的失败阻塞。 */
    fun retry(scope: String) {
        mutate { db ->
            db.execSQL("UPDATE outbox SET error=NULL,blocked=0 WHERE scope=?", arrayOf(scope))
        }
    }

    /** 缓存后台拉取失败等全局同步状态，便于离线时解释为什么尚未更新。 */
    fun syncStatus(scope: String, message: String?) {
        mutate { db ->
            db.insertWithOnConflict(
                "sync_status",
                null,
                ContentValues().apply {
                    put("scope", scope)
                    put("message", message)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** 读取当前连接最近一次同步错误，不暴露凭据。 */
    @Synchronized
    fun syncStatus(scope: String): String? =
        readableDatabase
            .rawQuery("SELECT message FROM sync_status WHERE scope=?", arrayOf(scope))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    /** 把同一服务器的既有连接分区归并到空目标分区，拒绝覆盖已有目标笔记。 */
    fun adoptConnectionScope(source: String, target: String) {
        if (source == target) return
        mutate { db ->
            val occupied =
                db.rawQuery(
                        "SELECT 1 FROM notes WHERE scope=? UNION ALL SELECT 1 FROM outbox WHERE scope=? LIMIT 1",
                        arrayOf(target, target),
                    )
                    .use { it.moveToFirst() }
            if (!occupied) {
                listOf("notes", "outbox", "sync_status").forEach { table ->
                    db.execSQL("UPDATE $table SET scope=? WHERE scope=?", arrayOf(target, source))
                }
                db.execSQL(
                    "UPDATE OR IGNORE media SET scope=? WHERE scope=?",
                    arrayOf(target, source),
                )
            }
        }
    }

    /** 上传票据在网络传输前落盘，重启后可先确认已成功上传的对象。 */
    fun ticket(seq: Long, path: String, ticket: String) {
        mutate { db ->
            db.insertWithOnConflict(
                "uploads",
                null,
                ContentValues().apply {
                    put("seq", seq)
                    put("path", path)
                    put("ticket", ticket)
                    put("created", System.currentTimeMillis())
                    put("completed", 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** 读取上传票据及其创建时间，不在日志中输出签名 URL。 */
    @Synchronized
    fun ticket(seq: Long, path: String): Pair<String, Long>? =
        readableDatabase
            .rawQuery(
                "SELECT ticket,created FROM uploads WHERE seq=? AND path=?",
                arrayOf(seq.toString(), path),
            )
            .use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }

    /** 保存远端图片对应的本地原件或下载文件，支持离线查看。 */
    fun media(scope: String, id: String, path: String) {
        mutate { db ->
            db.insertWithOnConflict(
                "media",
                null,
                ContentValues().apply {
                    put("scope", scope)
                    put("image_id", id)
                    put("path", path)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** 读取图片文件映射；文件是否仍存在由调用方检查。 */
    @Synchronized
    fun media(scope: String, id: String): String? =
        readableDatabase
            .rawQuery("SELECT path FROM media WHERE scope=? AND image_id=?", arrayOf(scope, id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
}

/** 草稿离线标签用于即时展示；云端同步后以服务端解析结果校正。 */
fun localTags(text: String): List<String> =
    Regex("(?:^|[\\s（(，,。！？!?；;：:])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/-]*)")
        .findAll(text.replace(Regex("```[\\s\\S]*?(?:```|$)|`[^`\\n]*`"), " "))
        .map {
            java.text.Normalizer.normalize(it.groupValues[1], java.text.Normalizer.Form.NFKC)
                .lowercase()
        }
        .filter { it.length <= 32 }
        .distinct()
        .toList()
