package com.parent.tvlock.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.parent.tvlock.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 电视端专用纯内存图片加载器
 *
 * 核心设计原则：
 * 1. 【零磁盘占用】：绝不在本地闪存（ROM）写入任何缓存文件，彻底避免占用电视有限空间；
 * 2. 【按需内存解码】：仅在展示题目时从远程 URL 下载图片流直接解码至 RAM；
 * 3. 【LRU 内存淘汰】：动态分配堆内存的 1/8（限制在 4MB~20MB），多题翻页时自动淘汰旧 Bitmap；
 * 4. 【抗 OOM 采样缩放】：根据 TV 控件尺寸（inSampleSize）等比解码，防止大尺寸网络图片引发内存溢出；
 * 5. 【防止异步错位】：绑定 View Tag，切题或重绘时自动丢弃已过期的网络回调。
 */
class MemoryImageLoader private constructor(private val context: Context) {

    private val client: OkHttpClient = createCompatibleClient()

    private fun createCompatibleClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    // 内存缓存大小：取当前运行最大堆内存的 1/8，介于 4MB 至 20MB 之间
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(4 * 1024, 20 * 1024)

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    companion object {
        @Volatile
        private var instance: MemoryImageLoader? = null

        fun getInstance(context: Context): MemoryImageLoader {
            return instance ?: synchronized(this) {
                instance ?: MemoryImageLoader(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 异步加载图片至目标 ImageView（零磁盘缓存）
     *
     * @param scope 界面绑定的 CoroutineScope（退出或重置时随之取消）
     * @param url 图片地址（网络 URL、本地路径或 asset 协议）
     * @param targetView 目标 ImageView
     * @param reqWidth 期望最大像素宽（用于采样缩放）
     * @param reqHeight 期望最大像素高
     * @param onComplete 加载完成回调（true 成功，false 失败）
     */
    fun loadImage(
        scope: CoroutineScope,
        url: String?,
        targetView: ImageView,
        reqWidth: Int = 400,
        reqHeight: Int = 300,
        onComplete: ((Boolean) -> Unit)? = null
    ): Job? {
        if (url.isNullOrBlank()) {
            targetView.setImageDrawable(null)
            targetView.visibility = View.GONE
            onComplete?.invoke(false)
            return null
        }

        // 绑定 tag 防止 View 复用或连续切题引发的错位
        targetView.tag = url

        // 1. 命中 RAM 内存缓存直接渲染
        val cached = memoryCache.get(url)
        if (cached != null && !cached.isRecycled) {
            targetView.setImageBitmap(cached)
            targetView.visibility = View.VISIBLE
            onComplete?.invoke(true)
            return null
        }

        // 2. 异步下载流并直接在内存中解码
        return scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                fetchAndDecodeBitmap(url, reqWidth, reqHeight)
            }

            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                if (targetView.tag == url) {
                    targetView.setImageBitmap(bitmap)
                    targetView.visibility = View.VISIBLE
                    onComplete?.invoke(true)
                }
            } else {
                if (targetView.tag == url) {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * 从远程或本地读取二进制流并进行内存采样解码
     */
    private fun fetchAndDecodeBitmap(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bytes: ByteArray? = when {
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            L.w("MemoryImageLoader: HTTP error ${response.code} for $url")
                            null
                        } else {
                            response.body?.bytes()
                        }
                    }
                }
                url.startsWith("file:///android_asset/", ignoreCase = true) -> {
                    val assetPath = url.substring("file:///android_asset/".length)
                    context.assets.open(assetPath).use { it.readBytes() }
                }
                url.startsWith("asset://", ignoreCase = true) -> {
                    val assetPath = url.substring("asset://".length)
                    context.assets.open(assetPath).use { it.readBytes() }
                }
                url.startsWith("/") || url.startsWith("file://", ignoreCase = true) -> {
                    val filePath = if (url.startsWith("file://")) url.substring(7) else url
                    File(filePath).takeIf { it.exists() }?.readBytes()
                }
                else -> null
            }

            if (bytes == null || bytes.isEmpty()) return null

            decodeSampledBitmap(bytes, reqWidth, reqHeight)
        } catch (e: Exception) {
            L.e("MemoryImageLoader: failed to fetch/decode image from $url", tr = e)
            null
        }
    }

    /**
     * 抗 OOM 采样解码
     */
    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * 计算最优采样比率（必须是 2 的幂）
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * 清空内存缓存（低内存告警时调用）
     */
    fun clearMemory() {
        memoryCache.evictAll()
    }
}
