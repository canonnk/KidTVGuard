package com.parent.tvlock.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.dao.QuestionDao
import com.parent.tvlock.db.model.Question
import com.parent.tvlock.util.L
import com.parent.tvlock.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 题库同步结果实体
 */
data class SyncResult(
    val totalReceived: Int,
    val savedCount: Int,
    val downloadedImages: Int
)

/**
 * 题库联网同步引擎与离线兜底加载器
 */
class SyncManager(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val questionDao = QuestionDao(dbHelper)
    private val imageDownloadHelper = ImageDownloadHelper.getInstance(context)
    private val preferenceManager = PreferenceManager.getInstance(context)
    private val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_ASSET_FILE = "default_questions.json"

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 检测本地数据库，若为空则自动从 assets/default_questions.json 导入初始题库
     * @return 导入的题目数量
     */
    suspend fun loadDefaultQuestionsIfNeeded(): Int = withContext(Dispatchers.IO) {
        val currentCount = questionDao.getCount()
        if (currentCount > 0) {
            L.d("Database already has $currentCount questions, skipping default import.")
            return@withContext 0
        }

        L.i("Database is empty, loading default questions from assets...")
        try {
            context.assets.open(DEFAULT_ASSET_FILE).use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<Question>>() {}.type
                    val list: List<Question> = gson.fromJson(reader, type)
                    if (list.isNotEmpty()) {
                        val inserted = questionDao.batchInsert(list)
                        L.i("Loaded $inserted default questions into SQLite.")
                        return@withContext inserted
                    }
                }
            }
        } catch (e: Exception) {
            L.e("Failed to load default questions from assets", tr = e)
        }
        return@withContext 0
    }

    /**
     * 联网同步题库
     * @param serverUrl 目标接口地址，若为空则使用家长设置中的默认 URL
     */
    suspend fun syncQuestions(serverUrl: String = preferenceManager.syncServerUrl): Result<SyncResult> =
        withContext(Dispatchers.IO) {
            val targetUrl = serverUrl.ifBlank { preferenceManager.syncServerUrl }
            L.i("Starting question sync from: $targetUrl")

            val request = Request.Builder()
                .url(targetUrl)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "HTTP error during question sync: code ${response.code}"
                        L.w(errMsg)
                        return@withContext Result.failure(Exception(errMsg))
                    }

                    val bodyStr = response.body?.string()
                    if (bodyStr.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("Empty response body received"))
                    }

                    // 解析 JSON（支持根节点为数组，或者包含 "questions" 键的对象）
                    val parsedQuestions = parseQuestionsJson(bodyStr)
                    if (parsedQuestions.isEmpty()) {
                        return@withContext Result.success(SyncResult(0, 0, 0))
                    }

                    var downloadedImages = 0
                    val questionsToSave = mutableListOf<Question>()

                    for (rawQ in parsedQuestions) {
                        var localImagePath = rawQ.localImagePath
                        val imageUrl = rawQ.questionImageUrl

                        // 若包含网络图片，尝试下载或使用已有的本地缓存
                        if (!imageUrl.isNullOrBlank()) {
                            val cachedPath = imageDownloadHelper.downloadImage(imageUrl)
                            if (cachedPath != null) {
                                localImagePath = cachedPath
                                downloadedImages++
                            }
                        }

                        questionsToSave.add(
                            rawQ.copy(localImagePath = localImagePath)
                        )
                    }

                    // 批量保存/更新到本地 SQLite
                    val saved = questionDao.batchInsertOrUpdate(questionsToSave)
                    preferenceManager.lastSyncTime = System.currentTimeMillis()

                    L.i("Sync completed: received ${parsedQuestions.size}, saved $saved, downloaded $downloadedImages images.")
                    return@withContext Result.success(
                        SyncResult(
                            totalReceived = parsedQuestions.size,
                            savedCount = saved,
                            downloadedImages = downloadedImages
                        )
                    )
                }
            } catch (e: Exception) {
                L.e("Exception during question sync", tr = e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * 兼容解析顶层 JSON Array 或包含 "questions" / "data" 属性的 JSON Object
     */
    private fun parseQuestionsJson(jsonStr: String): List<Question> {
        return try {
            val jsonElement = JsonParser.parseString(jsonStr)
            val listType = object : TypeToken<List<Question>>() {}.type

            when {
                jsonElement.isJsonArray -> {
                    gson.fromJson<List<Question>>(jsonElement, listType) ?: emptyList()
                }
                jsonElement.isJsonObject -> {
                    val obj = jsonElement.asJsonObject
                    when {
                        obj.has("questions") && obj.get("questions").isJsonArray -> {
                            gson.fromJson<List<Question>>(obj.get("questions"), listType) ?: emptyList()
                        }
                        obj.has("data") && obj.get("data").isJsonArray -> {
                            gson.fromJson<List<Question>>(obj.get("data"), listType) ?: emptyList()
                        }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            L.e("Failed to parse questions JSON: $jsonStr", tr = e)
            emptyList()
        }
    }
}
