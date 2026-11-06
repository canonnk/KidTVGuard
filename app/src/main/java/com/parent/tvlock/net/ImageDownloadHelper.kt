package com.parent.tvlock.net

import android.content.Context
import com.parent.tvlock.util.L
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 几何/物理题干图片下载器（本地离线缓存）
 */
class ImageDownloadHelper(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val IMAGE_DIR_NAME = "question_images"

        @Volatile
        private var instance: ImageDownloadHelper? = null

        fun getInstance(context: Context): ImageDownloadHelper {
            return instance ?: synchronized(this) {
                instance ?: ImageDownloadHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 获取或创建本地图片存储目录
     */
    val imageDir: File
        get() {
            val dir = File(context.filesDir, IMAGE_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    /**
     * 根据 URL 计算安全的本地文件名称（MD5哈希 + 后缀）
     */
    fun getLocalFileNameForUrl(url: String): String {
        val extension = try {
            val lastPath = url.substringBefore('?').substringAfterLast('/', "")
            if (lastPath.contains('.')) {
                "." + lastPath.substringAfterLast('.')
            } else {
                ".png"
            }
        } catch (e: Exception) {
            ".png"
        }

        val hash = try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }

        return "$hash$extension"
    }

    /**
     * 同步下载图片并保存到本地（必须在子线程或协程中调用）
     * @param url 图片网络地址
     * @return 本地文件绝对路径，下载失败返回 null
     */
    fun downloadImage(url: String): String? {
        if (url.isBlank()) return null

        val fileName = getLocalFileNameForUrl(url)
        val targetFile = File(imageDir, fileName)

        // 若本地已存在且非空，直接复用
        if (targetFile.exists() && targetFile.length() > 0) {
            L.d("Image already cached locally: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        }

        L.i("Downloading image from: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    L.w("Failed to download image, HTTP status: ${response.code}")
                    return null
                }

                val body = response.body ?: return null
                val tempFile = File(imageDir, "$fileName.tmp")

                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { input ->
                        input.copyTo(fos)
                    }
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (targetFile.exists()) targetFile.delete()
                    val renameSuccess = tempFile.renameTo(targetFile)
                    if (renameSuccess) {
                        L.i("Image downloaded successfully: ${targetFile.absolutePath}")
                        targetFile.absolutePath
                    } else {
                        L.w("Failed to rename temp image file: ${tempFile.absolutePath}")
                        null
                    }
                } else {
                    tempFile.delete()
                    null
                }
            }
        } catch (e: Exception) {
            L.e("Exception while downloading image: $url", tr = e)
            null
        }
    }
}
