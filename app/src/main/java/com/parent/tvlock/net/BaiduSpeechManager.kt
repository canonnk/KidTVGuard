package com.parent.tvlock.net

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 百度短语音识别（极速版 REST API）统一管理器
 *
 * 官方文档：https://cloud.baidu.com/doc/SPEECH/s/Tldjm0i4c
 *
 * 核心功能：
 * 1. OAuth2 Client Credentials 换取 Access Token，内存缓存 30 天；
 * 2. 接收 RAM 中的 16kHz PCM 字节流，内存转换为 Base64 直送极速版识别接口；
 * 3. 智能兼容旧版 Android TV 系统 SSL 证书环境，保障老电视请求通畅。
 */
class BaiduSpeechManager private constructor(private val context: Context) {

    private val preferenceManager = PreferenceManager.getInstance(context)
    private val gson = Gson()

    private val client: OkHttpClient = createCompatibleClient()

    // 内存 Token 缓存
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    companion object {
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        // 短语音极速版识别接口
        private const val PRO_ASR_URL = "https://vop.baidu.com/pro_api"
        // 短语音标准版兜底接口
        private const val SERVER_ASR_URL = "https://vop.baidu.com/server_api"

        @Volatile
        private var instance: BaiduSpeechManager? = null

        fun getInstance(context: Context): BaiduSpeechManager {
            return instance ?: synchronized(this) {
                instance ?: BaiduSpeechManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 获取或刷新 Access Token
     */
    suspend fun getAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken != null && now < tokenExpiresAt - 60_000L) {
            return@withContext cachedToken
        }

        val apiKey = preferenceManager.baiduAsrApiKey.trim()
        val secretKey = preferenceManager.baiduAsrSecretKey.trim()

        if (apiKey.isBlank() || secretKey.isBlank()) {
            L.w("BaiduSpeechManager: API Key or Secret Key is blank!")
            return@withContext null
        }

        val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("access_token")) {
                        val token = json.get("access_token").asString
                        val expiresIn = if (json.has("expires_in")) json.get("expires_in").asLong else 2592000L
                        cachedToken = token
                        tokenExpiresAt = now + expiresIn * 1000L
                        L.i("BaiduSpeechManager: Successfully acquired access_token (expires in ${expiresIn}s)")
                        return@withContext token
                    } else {
                        val error = json.get("error")?.asString ?: "Unknown error"
                        val errorDesc = json.get("error_description")?.asString ?: ""
                        L.e("BaiduSpeechManager: Failed to get token: $error, $errorDesc")
                    }
                } else {
                    L.e("BaiduSpeechManager: Token request HTTP error ${response.code}: $body")
                }
            }
        } catch (e: Exception) {
            L.e("BaiduSpeechManager: Exception getting token", tr = e)
        }
        return@withContext null
    }

    /**
     * 调用百度短语音识别接口识别内存音频
     * @param pcmBytes 16kHz 16bit 单声道 PCM 裸音频数据（纯内存，无文件）
     * @return 识别出的文本列表，首项为最佳结果；若识别失败返回 null
     */
    suspend fun recognizeSpeech(pcmBytes: ByteArray): List<String>? = withContext(Dispatchers.IO) {
        if (pcmBytes.isEmpty()) {
            L.w("BaiduSpeechManager: Empty PCM bytes received for recognition.")
            return@withContext null
        }

        val token = getAccessToken() ?: return@withContext null
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val cuid = generateCuid()

        // 构造极速版请求参数体
        val payload = JsonObject().apply {
            addProperty("format", "pcm")
            addProperty("rate", 16000)
            addProperty("channel", 1)
            addProperty("cuid", cuid)
            addProperty("token", token)
            addProperty("speech", base64Audio)
            addProperty("len", pcmBytes.size)
            addProperty("dev_pid", 80001) // 80001 为极速版普通话（含简单英文）
        }

        val jsonBody = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestUrl = "$PRO_ASR_URL?access_token=$token"

        val request = Request.Builder()
            .url(requestUrl)
            .post(jsonBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val resJson = JsonParser.parseString(body).asJsonObject
                    val errNo = if (resJson.has("err_no")) resJson.get("err_no").asInt else -1
                    if (errNo == 0 && resJson.has("result")) {
                        val resultArray = resJson.getAsJsonArray("result")
                        val list = mutableListOf<String>()
                        for (element in resultArray) {
                            list.add(element.asString)
                        }
                        L.i("BaiduSpeechManager: Recognition successful: $list")
                        return@withContext list
                    } else {
                        val errMsg = if (resJson.has("err_msg")) resJson.get("err_msg").asString else "Unknown"
                        L.w("BaiduSpeechManager: ASR returned error code $errNo: $errMsg")
                        // 若极速版因模型未开通等原因报错，尝试标准版 fallback
                        if (errNo == 3302 || errNo == 3307 || errNo == 3310) {
                            return@withContext fallbackToStandardAsr(payload, token)
                        }
                    }
                } else {
                    L.e("BaiduSpeechManager: HTTP error ${response.code} during ASR: $body")
                }
            }
        } catch (e: Exception) {
            L.e("BaiduSpeechManager: Exception during speech recognition", tr = e)
        }
        return@withContext null
    }

    /**
     * 标准版短语音识别兜底（dev_pid: 1537 普通话输入法模型）
     */
    private fun fallbackToStandardAsr(originalPayload: JsonObject, token: String): List<String>? {
        L.i("BaiduSpeechManager: Attempting fallback to standard ASR server_api...")
        originalPayload.addProperty("dev_pid", 1537)
        val jsonBody = gson.toJson(originalPayload).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(SERVER_ASR_URL)
            .post(jsonBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val resJson = JsonParser.parseString(body).asJsonObject
                    val errNo = if (resJson.has("err_no")) resJson.get("err_no").asInt else -1
                    if (errNo == 0 && resJson.has("result")) {
                        val list = mutableListOf<String>()
                        for (el in resJson.getAsJsonArray("result")) {
                            list.add(el.asString)
                        }
                        L.i("BaiduSpeechManager: Standard fallback ASR successful: $list")
                        list
                    } else null
                } else null
            }
        } catch (e: Exception) {
            L.e("BaiduSpeechManager: Exception in standard ASR fallback", tr = e)
            null
        }
    }

    /**
     * 测试百度语音配置连通性
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val token = getAccessToken(forceRefresh = true)
        if (token != null) {
            Result.success("百度语音鉴权成功！Token 有效，服务正常。")
        } else {
            Result.failure(Exception("鉴权失败，请检查 API Key 和 Secret Key 是否正确无误，或网络是否通畅。"))
        }
    }

    private fun generateCuid(): String {
        return try {
            "tv_" + Build.SERIAL.take(16).ifBlank { UUID.randomUUID().toString().replace("-", "").take(16) }
        } catch (e: Exception) {
            "tv_device_default"
        }
    }

    private fun createCompatibleClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
