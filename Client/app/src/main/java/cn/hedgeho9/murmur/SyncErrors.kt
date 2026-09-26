/** 将网络与上传阶段转成可读错误，不显示令牌、签名链接或服务器原始响应。 */
package cn.hedgeho9.murmur

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** HTTP 响应失败，保存状态码用于决定后台是否自动重试。 */
class ApiFailure(val status: Int) : IOException("HTTP $status")

/** 为失败附加具体操作阶段，不包含请求内容。 */
class StageFailure(val stage: String, cause: Throwable) : IOException(stage, cause)

/** 保留用户可修正的问题；不让后台静默覆盖冲突内容。 */
class LocalConflict : Exception("帖子 ID 冲突，请检查云端内容")

/** 递归解释阶段与网络原因，只生成受控的中文消息。 */
fun syncError(error: Throwable): String =
    when (error) {
        is StageFailure -> "${error.stage}：${syncError(error.cause ?: error)}"
        is ApiFailure ->
            when (error.status) {
                401 -> "访问令牌无效或已过期（401）"
                404 -> "目标记录在当前服务器不存在（404）"
                403 -> "没有访问权限（403）"
                413 -> "图片或请求超过大小限制（413）"
                422 -> "内容或图片不符合服务器限制（422）"
                409 -> "数据冲突（409）"
                else -> "服务器返回 HTTP ${error.status}"
            }
        is java.io.FileNotFoundException -> "本地图片文件不存在，笔记仍保留"
        is SocketTimeoutException -> "网络超时，请检查连接后重试"
        is UnknownHostException -> "域名无法解析，请检查地址和网络"
        is SSLException -> "HTTPS 证书或安全连接失败"
        is LocalConflict -> "同一帖子 ID 的云端内容不同，未覆盖"
        is IOException -> "网络连接中断或不可达"
        else -> "处理失败，请重试"
    }

/** 网络及服务端暂时错误可自动重试；鉴权、验证、冲突等待用户处理。 */
fun retryable(error: Throwable): Boolean =
    when (error) {
        is StageFailure -> error.cause?.let(::retryable) ?: false
        is ApiFailure -> error.status == 408 || error.status == 429 || error.status >= 500
        is SSLException,
        is java.io.FileNotFoundException -> false
        is IOException -> true
        else -> false
    }

/** 记录操作阶段并继续传播协程取消，不将取消误记成上传失败。 */
suspend fun <T> atStage(name: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw StageFailure(name, e)
    }
