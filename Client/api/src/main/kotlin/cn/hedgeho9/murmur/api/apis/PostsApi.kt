package cn.hedgeho9.murmur.api.apis

import cn.hedgeho9.murmur.api.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import cn.hedgeho9.murmur.api.models.AppendMessageRequest
import cn.hedgeho9.murmur.api.models.CreatePostRequest
import cn.hedgeho9.murmur.api.models.CreatedPost
import cn.hedgeho9.murmur.api.models.EditMessageRequest
import cn.hedgeho9.murmur.api.models.ErrorResponse
import cn.hedgeho9.murmur.api.models.Post
import cn.hedgeho9.murmur.api.models.PostDetail
import cn.hedgeho9.murmur.api.models.PostPage
import cn.hedgeho9.murmur.api.models.RenamePostRequest
import cn.hedgeho9.murmur.api.models.TagSuggestions
import cn.hedgeho9.murmur.api.models.UserMessage

interface PostsApi {
    /**
     * POST api/v1/posts/{id}/messages
     * 
     * 
     * Responses:
     *  - 201: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param id 
     * @param appendMessageRequest 
     * @return [UserMessage]
     */
    @POST("api/v1/posts/{id}/messages")
    suspend fun appendMessage(@Path("id") id: java.util.UUID, @Body appendMessageRequest: AppendMessageRequest): Response<UserMessage>

    /**
     * POST api/v1/posts
     * 
     * 
     * Responses:
     *  - 201: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param createPostRequest 
     * @return [CreatedPost]
     */
    @POST("api/v1/posts")
    suspend fun createPost(@Body createPostRequest: CreatePostRequest): Response<CreatedPost>

    /**
     * DELETE api/v1/posts/{id}
     * 
     * 
     * Responses:
     *  - 204: Deleted; repeated deletion succeeds. S3 cleanup is asynchronous.
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param id 
     * @return [Unit]
     */
    @DELETE("api/v1/posts/{id}")
    suspend fun deletePost(@Path("id") id: java.util.UUID): Response<Unit>

    /**
     * PATCH api/v1/posts/{id}/messages/{messageId}
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param id 
     * @param messageId 
     * @param editMessageRequest 
     * @return [UserMessage]
     */
    @PATCH("api/v1/posts/{id}/messages/{messageId}")
    suspend fun editMessage(@Path("id") id: java.util.UUID, @Path("messageId") messageId: java.util.UUID, @Body editMessageRequest: EditMessageRequest): Response<UserMessage>

    /**
     * GET api/v1/posts/{id}
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param id 
     * @return [PostDetail]
     */
    @GET("api/v1/posts/{id}")
    suspend fun getPost(@Path("id") id: java.util.UUID): Response<PostDetail>


    /**
    * enum for parameter imagesOnly
    */
    @Serializable
    enum class ImagesOnlyListPosts(val value: kotlin.String) {
        @SerialName(value = "true") `true`("true"),
        @SerialName(value = "false") `false`("false")
    }

    /**
     * GET api/v1/posts
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param limit  (optional, default to 20)
     * @param cursor  (optional)
     * @param q  (optional)
     * @param imagesOnly  (optional)
     * @return [PostPage]
     */
    @GET("api/v1/posts")
    suspend fun listPosts(@Query("limit") limit: kotlin.Int? = 20, @Query("cursor") cursor: kotlin.String? = null, @Query("q") q: kotlin.String? = null, @Query("images_only") imagesOnly: ImagesOnlyListPosts? = null): Response<PostPage>

    /**
     * PATCH api/v1/posts/{id}
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param id 
     * @param renamePostRequest 
     * @return [Post]
     */
    @PATCH("api/v1/posts/{id}")
    suspend fun renamePost(@Path("id") id: java.util.UUID, @Body renamePostRequest: RenamePostRequest): Response<Post>

    /**
     * GET api/v1/tags
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param prefix  (optional, default to "")
     * @return [TagSuggestions]
     */
    @GET("api/v1/tags")
    suspend fun suggestTags(@Query("prefix") prefix: kotlin.String? = ""): Response<TagSuggestions>

}
