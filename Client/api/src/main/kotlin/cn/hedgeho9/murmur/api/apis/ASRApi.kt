package cn.hedgeho9.murmur.api.apis

import cn.hedgeho9.murmur.api.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import cn.hedgeho9.murmur.api.models.AsrCredentials
import cn.hedgeho9.murmur.api.models.ErrorResponse

interface ASRApi {
    /**
     * POST api/v1/asr/credentials
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
     * @return [AsrCredentials]
     */
    @POST("api/v1/asr/credentials")
    suspend fun createAsrCredentials(): Response<AsrCredentials>

    /**
     * GET api/v1/asr/stream
     * 
     * WebSocket: start {format:pcm_s16le,sample_rate:16000,channels:1} → ready → binary PCM → finish → transcript/completed. Transcript fields: segment_id,text,is_final. Error: type&#x3D;error,code,message.
     * Responses:
     *  - 101: WebSocket upgrade
     *  - 503: ASR is not configured or concurrent session limit reached
     *
     * @return [Unit]
     */
    @GET("api/v1/asr/stream")
    suspend fun streamAsr(): Response<Unit>

}
