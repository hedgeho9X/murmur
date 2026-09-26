/** 使用真实 Android SQLite 验证离线队列、重开读取与并发确认边界，不调用生产 API。 */
package cn.hedgeho9.murmur

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith

/** 每项测试使用独立连接分区，清理仅触及该测试的数据。 */
@RunWith(AndroidJUnit4::class)
class NotesDatabaseTest {
    private val db = NotesDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private val scope = "test-${newId()}"

    /** 移除当前测试的队列和缓存，不清空应用笔记或连接信息。 */
    @After
    fun clean() {
        db.queue(scope).forEach { db.acknowledge(it.seq) }
        db.reconcile(scope, emptySet())
    }

    /** 保存多条离线笔记后，另一个 SQLite 连接仍能读到完整缓存和操作快照。 */
    @Test
    fun durableQueueSurvivesReopen() {
        val a = Draft(text = "第一条 #work")
        val b = Draft(text = "第二条")
        db.create(scope, a)
        db.create(scope, b)
        db.create(scope, a)
        Assert.assertEquals(2, db.queue(scope).size)
        SQLiteDatabase.openDatabase(db.readableDatabase.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { other ->
                other.rawQuery("SELECT count(*) FROM notes WHERE scope=?", arrayOf(scope)).use {
                    it.moveToFirst()
                    Assert.assertEquals(2, it.getInt(0))
                }
            }
        Assert.assertEquals(a.id, db.list(scope, "#work", 20).single().id.toString())
        Assert.assertEquals("第一条 #work", db.get(scope, a.id)!!.messages.single().text)
    }

    /** 确认旧创建请求不会删除随后排队的编辑，迟到远端响应不能覆盖最新正文。 */
    @Test
    fun acknowledgementPreservesNewerEdits() {
        val draft = Draft(text = "old #work")
        db.create(scope, draft)
        val original = db.get(scope, draft.id)!!
        val create = db.queue(scope).single()
        db.edit(scope, draft.id, original.messages.single().id, "new #life")
        Assert.assertEquals("old #work", db.queue(scope).first().change.draft!!.text)
        db.acknowledge(create.seq)
        db.cache(scope, original.post, original)
        Assert.assertEquals("new #life", db.get(scope, draft.id)!!.messages.single().text)
        Assert.assertEquals(1, db.queue(scope).size)
        Assert.assertEquals(listOf("life"), db.get(scope, draft.id)!!.post.tags)
    }

    /** 墓碑阻止旧查询复活删除内容；其他服务器分区看不到本地数据。 */
    @Test
    fun deletionAndConnectionIsolation() {
        val d = Draft(text = "private")
        db.create(scope, d)
        val original = db.get(scope, d.id)!!
        db.delete(scope, d.id)
        db.cache(scope, original.post, original)
        Assert.assertTrue(db.list(scope, "", 20).isEmpty())
        Assert.assertNull(db.get("other-$scope", d.id))
        db.reconcile(scope, emptySet())
        Assert.assertEquals(2, db.queue(scope).size)
    }

    /** 删除确认只清理被删除帖子的先前任务，不误删其他离线笔记。 */
    @Test
    fun deletionAcknowledgesOnlyItsOwnQueue() {
        val first = Draft(text = "discard")
        val second = Draft(text = "keep")
        db.create(scope, first)
        db.create(scope, second)
        db.delete(scope, first.id)
        val last = db.queue(scope).last()
        db.acknowledgeThrough(scope, first.id, last.seq)
        Assert.assertEquals(listOf(second.id), db.queue(scope).map { it.postId })
    }

    /** 详情响应没有 preview 时仍从正文生成列表摘要，并保留本地创建时间。 */
    @Test
    fun cachedDetailsKeepPreviewAndLocalCreationTime() {
        val d = Draft(text = "offline text")
        db.create(scope, d)
        val cached = db.get(scope, d.id)!!
        db.acknowledge(db.queue(scope).single().seq)
        val remote = cached.post.copy(preview = null, createdAt = "2030-01-01T00:00:00Z")
        db.cache(scope, remote, cached.copy(post = remote))
        val row = db.list(scope, "", 10).single()
        Assert.assertEquals("offline text", row.preview)
        Assert.assertEquals(cached.post.createdAt, row.createdAt)
    }

    /** 旧服务器补充草稿另存时保留后续编辑与图片票据，避免再次上传或丢内容。 */
    @Test
    fun missingAppendCanBecomeNewNote() {
        val parent = newId()
        val draft = Draft(postId = parent, text = "original", images = listOf("/local/photo.jpg"))
        db.create(scope, draft)
        val first = db.queue(scope).single()
        db.ticket(first.seq, "/local/photo.jpg", "ticket")
        db.failed(first.seq, "保存云端笔记：服务器返回 HTTP 404", true)
        db.edit(scope, parent, "local:${draft.id}", "latest")
        val target = db.recoverMissingAppend(scope, parent)
        Assert.assertEquals(draft.id, target)
        Assert.assertNull(db.get(scope, parent))
        Assert.assertEquals("latest", db.get(scope, target)!!.messages.single().text)
        Assert.assertTrue(db.queue(scope).all { it.postId == target && !it.blocked })
        Assert.assertEquals("create", db.queue(scope).first().change.kind)
        Assert.assertEquals("ticket", db.ticket(first.seq, "/local/photo.jpg")!!.first)
    }

    /** 相同草稿 ID、不同正文不能被当作重复点击吞掉。 */
    @Test
    fun conflictingDraftIsNotDiscarded() {
        val d = Draft(text = "first")
        db.create(scope, d)
        Assert.assertThrows(LocalConflict::class.java) {
            db.create(scope, d.copy(text = "different"))
        }
        Assert.assertEquals("first", db.get(scope, d.id)!!.messages.single().text)
    }
}
