/** 本地草稿、图片和连接配置持久化；API 令牌使用 Android Keystore 加密，不写入 APK。 */
package cn.hedgeho9.murmur

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 可恢复的编辑状态；新帖子 ID 在创建草稿时固定，已上传附件 ID 可用于失败重试。 */
@Serializable
data class Draft(
    val id: String = newId(),
    val postId: String? = null,
    val text: String = "",
    val images: List<String> = emptyList(),
    val uploads: Map<String, String> = emptyMap(),
)

/** 返回含毫秒时间与随机位的 UUIDv7；生成后必须保存在草稿而不是每次重试重算。 */
fun newId(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    var t = System.currentTimeMillis()
    for (i in 5 downTo 0) {
        bytes[i] = t.toByte()
        t = t ushr 8
    }
    bytes[6] = ((bytes[6].toInt() and 15) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 0x80).toByte()
    val b = java.nio.ByteBuffer.wrap(bytes)
    return UUID(b.long, b.long).toString()
}

/** 在应用私有目录中原子保存单份活跃草稿，连接令牌单独加密。 */
class DraftStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = AtomicFile(File(context.filesDir, "draft.json"))
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    /** 读取草稿；没有文件时创建新草稿，损坏数据保留原文件并报告异常。 */
    fun load(): Draft =
        if (file.baseFile.exists())
            json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
        else Draft()

    /** 原子写入草稿，失败时恢复原文件，避免进程中断写坏 JSON。 */
    fun save(draft: Draft) {
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(draft).toByteArray())
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    /** 返回固定连接地址，初始值用于 adb reverse 的本地开发链路。 */
    fun baseUrl(): String = prefs.getString("url", "http://127.0.0.1:8787/")!!

    /** 通过 Keystore 解密 API 令牌；密钥丢失时要求用户重新配置。 */
    fun token(): String {
        val stored = prefs.getString("token", null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        } catch (_: Exception) {
            ""
        }
    }

    /** 校验 HTTP 地址并加密保存令牌，不发送网络请求。 */
    fun configure(url: String, token: String) {
        require(url.startsWith("https://") || url.startsWith("http://"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray())
        prefs
            .edit()
            .putString("url", url.trimEnd('/') + "/")
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }

    /** 获取应用专属不可导出的 AES 密钥，首次使用时生成。 */
    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("murmur-api", null) as? javax.crypto.SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            "murmur-api",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }
}
