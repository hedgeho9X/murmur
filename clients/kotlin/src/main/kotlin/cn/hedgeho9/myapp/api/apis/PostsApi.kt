package cn.hedgeho9.myapp.api.apis

import cn.hedgeho9.myapp.api.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import cn.hedgeho9.myapp.api.models.CreatePostRequest
import cn.hedgeho9.myapp.api.models.CreatedPost
import cn.hedgeho9.myapp.api.models.ErrorResponse
import cn.hedgeho9.myapp.api.models.Post
import cn.hedgeho9.myapp.api.models.PostDetail
import cn.hedgeho9.myapp.api.models.PostPage
import cn.hedgeho9.myapp.api.models.RenamePostRequest

interface PostsApi {
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
     *  - 410: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param idempotencyKey 
     * @param createPostRequest 
     * @return [CreatedPost]
     */
    @POST("api/v1/posts")
    suspend fun createPost(@Header("Idempotency-Key") idempotencyKey: kotlin.String, @Body createPostRequest: CreatePostRequest): Response<CreatedPost>

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
     *  - 410: Error
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
     * GET api/v1/posts/{id}
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 410: Error
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
     * GET api/v1/posts
     * 
     * 
     * Responses:
     *  - 200: Success
     *  - 400: Error
     *  - 401: Error
     *  - 404: Error
     *  - 409: Error
     *  - 410: Error
     *  - 413: Error
     *  - 422: Error
     *  - 500: Error
     *  - 503: Error
     *
     * @param limit  (optional, default to 20)
     * @param cursor  (optional)
     * @return [PostPage]
     */
    @GET("api/v1/posts")
    suspend fun listPosts(@Query("limit") limit: kotlin.Int? = 20, @Query("cursor") cursor: kotlin.String? = null): Response<PostPage>

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
     *  - 410: Error
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

}
