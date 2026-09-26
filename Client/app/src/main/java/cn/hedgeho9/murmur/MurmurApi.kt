/** 使用生成客户端访问帖子和图片接口；不在 UI 中维护第二份 HTTP 契约。 */
package cn.hedgeho9.murmur

import cn.hedgeho9.murmur.api.apis.ImagesApi
import cn.hedgeho9.murmur.api.apis.PostsApi
import cn.hedgeho9.murmur.api.infrastructure.ApiClient
import cn.hedgeho9.murmur.api.infrastructure.Serializer
import cn.hedgeho9.murmur.api.models.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response

/** 可展示消息，不包含原始供应商内部状态。 */
@Serializable
data class DisplayMessage(
    val id: String,
    val role: String,
    val text: String,
    val images: List<String>,
    val process: Boolean = false,
)

/** 同一次详情响应里的帖子与消息，避免重复读取产生不一致快照。 */
@Serializable data class DisplayPost(val post: Post, val messages: List<DisplayMessage>)

/** 接口仓储负责生成 DTO、上传字节和错误归因；重试由草稿保存的 ID 决定。 */
class MurmurApi(base: String, token: String) {
    companion object {
        // API 与签名上传共享连接池；认证拦截器仅添加到单个 API 客户端，不能进入 S3 上传请求。
        private val sharedTransport =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
    }

    private val transport = sharedTransport
    private val client =
        ApiClient(
                baseUrl = base,
                okHttpClientBuilder = transport.newBuilder(),
                authNames = arrayOf("bearerAuth"),
            )
            .setBearerToken(token)
    val posts = client.createService(PostsApi::class.java)
    private val images = client.createService(ImagesApi::class.java)
    private val codec = Serializer.kotlinxSerializationJson

    /** 将 HTTP 失败转换为可见错误，正文只读取稳定错误码，不输出认证信息。 */
    private fun <T> Response<T>.value(): T {
        if (!isSuccessful) throw ApiFailure(code())
        return body() ?: throw IllegalStateException("服务器返回空内容")
    }

    /** 上传图片并完成校验，返回资源 ID；字节上传不携带业务 API 的认证令牌。 */
    suspend fun upload(path: String): String =
        withContext(Dispatchers.IO) {
            val file = File(path)
            val upload =
                images
                    .createImageUpload(
                        UploadRequest(
                            UploadRequest.ContentType.imageSlashJpeg,
                            file.length().toInt(),
                        )
                    )
                    .value()
            val request =
                Request.Builder()
                    .url(upload.uploadUrl.toString())
                    .put(file.asRequestBody("image/jpeg".toMediaType()))
                    .build()
            transport.newCall(request).awaitResponse().use {
                if (!it.isSuccessful) throw IllegalStateException("图片上传失败（${it.code}）")
            }
            images.completeImageUpload(upload.image.id).value().id.toString()
        }

    /** 保存完整用户记录；冲突时读取已有记录，避免网络重试新增帖子或消息。 */
    suspend fun send(draft: Draft): String {
        val parts =
            buildList<UserContentPartsInner> {
                if (draft.text.isNotBlank())
                    add(UserContentPartsInner.TextWrapper(TextPart(TextPart.Type.text, draft.text)))
                draft.images.forEach { path ->
                    add(
                        UserContentPartsInner.ImageWrapper(
                            ImagePart(
                                ImagePart.Type.image,
                                UUID.fromString(requireNotNull(draft.uploads[path])),
                            )
                        )
                    )
                }
            }
        val content = UserContent(parts)
        if (draft.postId == null) {
            val response =
                posts.createPost(
                    CreatePostRequest(id = UUID.fromString(draft.id), content = content)
                )
            if (response.code() == 409) {
                val existing = posts.getPost(UUID.fromString(draft.id)).value()
                val first =
                    existing.messages.filterIsInstance<Message.UserWrapper>().firstOrNull()?.value
                if (first?.content != content) throw LocalConflict()
                return draft.id
            }
            return response.value().post.id.toString()
        }
        val response =
            posts.appendMessage(
                UUID.fromString(draft.postId),
                AppendMessageRequest(id = UUID.fromString(draft.id), content = content),
            )
        if (response.code() == 409) {
            val detail = posts.getPost(UUID.fromString(draft.postId)).value()
            val found =
                detail.messages.any {
                    (it as? Message.UserWrapper)?.value?.id?.toString() == draft.id
                }
            if (!found) throw IllegalStateException("消息 ID 冲突，请重新打开草稿")
        } else response.value()
        return draft.postId
    }

    /** 使用落盘票据恢复上传；已完成对象可直接确认，过期且未上传的票据重新申请。 */
    suspend fun uploadQueued(path: String, seq: Long, scope: String, db: NotesDatabase): String {
        val previous = withContext(Dispatchers.IO) { db.ticket(seq, path) }
        if (previous != null) {
            val ticket = codec.decodeFromString<UploadResponse>(previous.first)
            try {
                images.completeImageUpload(ticket.image.id).value()
                withContext(Dispatchers.IO) { db.media(scope, ticket.image.id.toString(), path) }
                return ticket.image.id.toString()
            } catch (e: ApiFailure) {
                if (e.status !in listOf(404, 409, 422)) throw StageFailure("确认图片", e)
            }
        }
        var ticket = previous?.let { codec.decodeFromString<UploadResponse>(it.first) }
        if (
            ticket == null ||
                System.currentTimeMillis() - (previous?.second ?: 0) >
                    ticket.expiresIn * 1000L - 30000
        ) {
            ticket =
                atStage("申请图片上传地址") {
                    images
                        .createImageUpload(
                            UploadRequest(
                                UploadRequest.ContentType.imageSlashJpeg,
                                File(path).length().toInt(),
                            )
                        )
                        .value()
                }
            val encoded = codec.encodeToString(ticket)
            withContext(Dispatchers.IO) { db.ticket(seq, path, encoded) }
        }
        val current = requireNotNull(ticket)
        atStage("上传图片") {
            withContext(Dispatchers.IO) {
                transport
                    .newCall(
                        Request.Builder()
                            .url(current.uploadUrl.toString())
                            .put(File(path).asRequestBody("image/jpeg".toMediaType()))
                            .build()
                    )
                    .awaitResponse()
                    .use { if (!it.isSuccessful) throw ApiFailure(it.code) }
            }
        }
        atStage("确认图片") { images.completeImageUpload(current.image.id).value() }
        withContext(Dispatchers.IO) { db.media(scope, current.image.id.toString(), path) }
        return current.image.id.toString()
    }

    /** 解析尚未获得服务器消息 ID 的本地首条笔记，后续编辑仍使用原帖子 ID。 */
    suspend fun messageId(postId: String, id: String): String {
        if (!id.startsWith("local:")) return id
        val messages =
            posts
                .getPost(UUID.fromString(postId))
                .value()
                .messages
                .filterIsInstance<Message.UserWrapper>()
        return if (id.removePrefix("local:") == postId) messages.first().value.id.toString()
        else
            messages
                .first { it.value.id.toString() == id.removePrefix("local:") }
                .value
                .id
                .toString()
    }

    /** 将私有图片下载到应用缓存文件；完成后原子改名，不缓存签名链接作为离线图片。 */
    suspend fun downloadImage(url: String, target: File) =
        withContext(Dispatchers.IO) {
            val temporary = File(target.path + ".${newId()}.part")
            try {
                transport.newCall(Request.Builder().url(url).build()).awaitResponse().use { response
                    ->
                    if (!response.isSuccessful) throw ApiFailure(response.code)
                    temporary.outputStream().use { output ->
                        requireNotNull(response.body).byteStream().use { it.copyTo(output) }
                    }
                }
                check(temporary.renameTo(target))
            } finally {
                temporary.delete()
            }
        }

    /** 通过生成的鉴权接口测量往返时间，包含连接及服务器响应，不是 ICMP ping。 */
    suspend fun probe(): String {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            kotlinx.coroutines.withTimeout(10000) { posts.listPosts(limit = 1).value() }
            "连接正常 · 鉴权通过 · API ${android.os.SystemClock.elapsedRealtime()-start}ms"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "API 测试超过 10 秒"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "${syncError(e)} · ${android.os.SystemClock.elapsedRealtime()-start}ms"
        }
    }

    /** 解析消息正文与图片，工具过程归为可折叠内容，不改写持久化消息。 */
    suspend fun detail(
        id: String,
        localImage: suspend (String, String) -> String = { _, url -> url },
    ): DisplayPost =
        withContext(Dispatchers.Default) {
            val detail = posts.getPost(UUID.fromString(id)).value()
            val rows =
                detail.messages.map { message ->
                    val item = codec.encodeToJsonElement(message).jsonObject
                    val role = item.getValue("role").jsonPrimitive.content
                    val parts = item.getValue("content").jsonObject.getValue("parts").jsonArray
                    val imageUrls = coroutineScope {
                        parts
                            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
                            .map { part ->
                                async {
                                    val imageId =
                                        part.jsonObject.getValue("image_id").jsonPrimitive.content
                                    val url =
                                        images
                                            .getImageUrl(
                                                UUID.fromString(
                                                    part.jsonObject
                                                        .getValue("image_id")
                                                        .jsonPrimitive
                                                        .content
                                                )
                                            )
                                            .value()
                                            .url
                                            .toString()
                                    localImage(imageId, url)
                                }
                            }
                            .awaitAll()
                    }
                    val text =
                        parts.joinToString("\n") {
                            val p = it.jsonObject
                            when (p["type"]?.jsonPrimitive?.content) {
                                "text" -> p["text"]?.jsonPrimitive?.content.orEmpty()
                                "tool_call" ->
                                    "${p["name"]?.jsonPrimitive?.content} ${p["arguments"]}"
                                "json" -> p["data"].toString()
                                else -> ""
                            }
                        }
                    DisplayMessage(
                        item.getValue("id").jsonPrimitive.content,
                        role,
                        text,
                        imageUrls,
                        role == "tool" ||
                            parts.any {
                                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_call"
                            },
                    )
                }
            DisplayPost(detail.post, rows)
        }

    /** 覆盖已发布用户笔记的文字，图片保持不变，标签由服务端重新提取。 */
    suspend fun editMessage(postId: String, messageId: String, text: String) {
        posts
            .editMessage(
                UUID.fromString(postId),
                UUID.fromString(messageId),
                EditMessageRequest(text),
            )
            .value()
    }

    /** 分页获取帖子列表，调用方决定何时加载下一页。 */
    suspend fun list(cursor: String? = null, query: String = ""): PostPage =
        posts.listPosts(20, cursor, query.ifBlank { null }).value()

    /** 获取标签补全，返回由 OpenAPI 生成的 DTO。 */
    suspend fun suggestTags(prefix: String): List<TagSuggestion> =
        posts.suggestTags(prefix).value().items

    /** 删除帖子及附件关系；对象删除由后端异步重试。 */
    suspend fun delete(id: String) {
        val r = posts.deletePost(UUID.fromString(id))
        if (!r.isSuccessful && r.code() != 404) throw ApiFailure(r.code())
    }
}
