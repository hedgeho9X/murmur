package cn.hedgeho9.murmur.api.apis

import cn.hedgeho9.murmur.api.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import cn.hedgeho9.murmur.api.models.ErrorResponse
import cn.hedgeho9.murmur.api.models.Image
import cn.hedgeho9.murmur.api.models.ImageUrl
import cn.hedgeho9.murmur.api.models.UploadRequest
import cn.hedgeho9.murmur.api.models.UploadResponse

interface ImagesApi {
    /**
     * POST api/v1/images/{id}/complete
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
     * @return [Image]
     */
    @POST("api/v1/images/{id}/complete")
    suspend fun completeImageUpload(@Path("id") id: java.util.UUID): Response<Image>

    /**
     * POST api/v1/images/uploads
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
     * @param uploadRequest 
     * @return [UploadResponse]
     */
    @POST("api/v1/images/uploads")
    suspend fun createImageUpload(@Body uploadRequest: UploadRequest): Response<UploadResponse>

    /**
     * GET api/v1/images/{id}/url
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
     * @return [ImageUrl]
     */
    @GET("api/v1/images/{id}/url")
    suspend fun getImageUrl(@Path("id") id: java.util.UUID): Response<ImageUrl>

}
