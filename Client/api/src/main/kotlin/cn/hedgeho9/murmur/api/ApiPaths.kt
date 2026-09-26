/** 由 OpenAPI 自动生成的接口路径，不手工修改。 */
package cn.hedgeho9.murmur.api

object ApiPaths {
    const val STREAM_ASR = "/api/v1/asr/stream"
    const val EDIT_MESSAGE = "/api/v1/posts/{id}/messages/{messageId}"
    const val SUGGEST_TAGS = "/api/v1/tags"
    const val APPEND_MESSAGE = "/api/v1/posts/{id}/messages"
    const val CREATE_POST = "/api/v1/posts"
    const val LIST_POSTS = "/api/v1/posts"
    const val GET_POST = "/api/v1/posts/{id}"
    const val RENAME_POST = "/api/v1/posts/{id}"
    const val DELETE_POST = "/api/v1/posts/{id}"
    const val CREATE_IMAGE_UPLOAD = "/api/v1/images/uploads"
    const val COMPLETE_IMAGE_UPLOAD = "/api/v1/images/{id}/complete"
    const val GET_IMAGE_URL = "/api/v1/images/{id}/url"
}
