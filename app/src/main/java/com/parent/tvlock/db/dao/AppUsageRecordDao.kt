package com.parent.tvlock.db.dao

import android.content.ContentValues
import android.database.Cursor
import com.parent.tvlock.db.AppDatabaseHelper
import com.parent.tvlock.db.model.AppUsageRecord
import com.parent.tvlock.db.model.AppUsageSummary
import com.parent.tvlock.util.DateTimeUtils

/**
 * 电视应用活动记录数据访问对象
 */
class AppUsageRecordDao(private val dbHelper: AppDatabaseHelper) {

    /**
     * 插入单次应用使用流水
     */
    fun insert(record: AppUsageRecord): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("packageName", record.packageName)
            put("appName", record.appName)
            put("startTime", record.startTime)
            put("endTime", record.endTime)
            put("durationSeconds", record.durationSeconds)
            put("recordDate", record.recordDate)
        }
        return db.insert(AppDatabaseHelper.TABLE_APP_USAGE_RECORDS, null, values)
    }

    /**
     * 查询指定日期的所有活动流水（按开始时间倒序）
     */
    fun getRecordsByDate(date: String): List<AppUsageRecord> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AppUsageRecord>()
        val cursor = db.rawQuery(
            "SELECT * FROM ${AppDatabaseHelper.TABLE_APP_USAGE_RECORDS} WHERE recordDate = ? ORDER BY startTime DESC",
            arrayOf(date)
        )
        while (cursor.moveToNext()) {
            list.add(cursor.toAppUsageRecord())
        }
        cursor.close()
        return list
    }

    /**
     * 按指定日期汇总各 App 的使用时长和启动频次（时长从大到小排序）
     */
    fun getDailyUsageSummary(date: String): List<AppUsageSummary> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AppUsageSummary>()
        val sql = """
            SELECT 
                packageName,
                appName,
                SUM(durationSeconds) AS totalDuration,
                COUNT(*) AS launchCount
            FROM ${AppDatabaseHelper.TABLE_APP_USAGE_RECORDS}
            WHERE recordDate = ?
            GROUP BY packageName, appName
            ORDER BY totalDuration DESC
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(date))
        while (cursor.moveToNext()) {
            val summary = AppUsageSummary(
                packageName = cursor.getString(cursor.getColumnIndexOrThrow("packageName")),
                appName = cursor.getString(cursor.getColumnIndexOrThrow("appName")),
                totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow("totalDuration")),
                launchCount = cursor.getInt(cursor.getColumnIndexOrThrow("launchCount"))
            )
            list.add(summary)
        }
        cursor.close()
        return list
    }

    /**
     * 获取今天使用电视应用的总秒数
     */
    fun getTodayTotalUsageSeconds(): Long {
        val today = DateTimeUtils.getTodayDate()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT SUM(durationSeconds) FROM ${AppDatabaseHelper.TABLE_APP_USAGE_RECORDS} WHERE recordDate = ?",
            arrayOf(today)
        )
        val total = if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        cursor.close()
        return total
    }

    /**
     * 查询所有记录过的日期列表（由近到远）
     */
    fun getAllRecordedDates(): List<String> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<String>()
        val cursor = db.rawQuery(
            "SELECT DISTINCT recordDate FROM ${AppDatabaseHelper.TABLE_APP_USAGE_RECORDS} ORDER BY recordDate DESC",
            null
        )
        while (cursor.moveToNext()) {
            list.add(cursor.getString(0))
        }
        cursor.close()
        return list
    }

    private fun Cursor.toAppUsageRecord(): AppUsageRecord {
        return AppUsageRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            packageName = getString(getColumnIndexOrThrow("packageName")),
            appName = getString(getColumnIndexOrThrow("appName")),
            startTime = getLong(getColumnIndexOrThrow("startTime")),
            endTime = getLong(getColumnIndexOrThrow("endTime")),
            durationSeconds = getLong(getColumnIndexOrThrow("durationSeconds")),
            recordDate = getString(getColumnIndexOrThrow("recordDate"))
        )
    }
}
