/** 验证生成客户端的多态 JSON 编解码和真实 Hono HTTP 契约，不修改生成代码。 */
package cn.hedgeho9.murmur.contract

import cn.hedgeho9.murmur.api.apis.PostsApi
import cn.hedgeho9.murmur.api.infrastructure.ApiClient
import cn.hedgeho9.murmur.api.infrastructure.Serializer
import cn.hedgeho9.murmur.api.models.*
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/** 连接本地开发服务验证调用链，仅创建并清理本测试产生的帖子。 */
class ContractTest : ShouldSpec() {
    init {
        should("round-trip text, tool calls, arbitrary JSON and image references") {
            val codec = Serializer.kotlinxSerializationJson
            val id = "d69f48c1-9ddf-45b6-946a-6892108bb0d0"
            val samples = listOf(
                """{"id":"$id","post_id":"$id","turn_id":"$id","role":"assistant","content":{"parts":[{"type":"text","text":"checking"},{"type":"tool_call","id":"call_1","name":"search","arguments":{"query":"中文","limit":3,"nested":{"enabled":true},"values":[null,1]}}],"finish_reason":"tool_call"},"tool_call_id":null,"created_at":"2026-09-26T00:00:00.000Z"}""",
                """{"id":"$id","post_id":"$id","turn_id":"$id","role":"tool","content":{"parts":[{"type":"json","data":{"items":[1,true,null]}},{"type":"image","image_id":"$id"}],"is_error":false},"tool_call_id":"call_1","created_at":"2026-09-26T00:00:00.000Z"}"""
            )
            for (sample in samples) {
                val decoded = codec.decodeFromString<Message>(sample)
                codec.parseToJsonElement(codec.encodeToString(decoded)) shouldBe codec.parseToJsonElement(sample)
            }
        }
        should("create, reject duplicate UUID, fetch and delete through generated Retrofit APIs") {
            runBlocking {
                val token = requireNotNull(System.getenv("API_TOKEN"))
                val api = ApiClient(baseUrl = "http://127.0.0.1:${System.getenv("PORT") ?: "8787"}/", authNames = arrayOf("bearerAuth")).setBearerToken(token).createService(PostsApi::class.java)
                val postId = "01993240-1000-7000-8000-" + UUID.randomUUID().toString().replace("-", "").takeLast(12)
                val request = Serializer.kotlinxSerializationJson.decodeFromString<CreatePostRequest>("""{"id":"$postId","content":{"parts":[{"type":"text","text":"Kotlin integration acceptance"}]}}""")
                val response = api.createPost(request)
                response.code() shouldBe 201
                val result = requireNotNull(response.body())
                try {
                    api.createPost(request).code() shouldBe 409
                    val detail = requireNotNull(api.getPost(result.post.id).body())
                    detail.messages.size shouldBe 1
                    (detail.messages.first() as Message.UserWrapper).value.id shouldBe result.message.id
                } finally {
                    api.deletePost(result.post.id).code() shouldBe 204
                }
                api.getPost(result.post.id).code() shouldBe 404
            }
        }
    }
}
