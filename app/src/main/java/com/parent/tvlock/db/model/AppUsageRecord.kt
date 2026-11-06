package com.parent.tvlock.db.model

import java.io.Serializable

/**
 * 电视应用活动使用流水实体
 */
data class AppUsageRecord(
    val id: Long = 0,
    val packageName: String,             // 应用包名（如 com.ktcp.tv.video）
    val appName: String,                 // 应用名称（如 腾讯视频TV）
    val startTime: Long,                 // 开启时间戳
    val endTime: Long,                   // 退出或切出时间戳
    val durationSeconds: Long,           // 使用时长（秒）
    val recordDate: String               // 日期（yyyy-MM-dd）
) : Serializable

/**
 * 电视应用使用统计聚合实体（按 App 汇总）
 */
data class AppUsageSummary(
    val packageName: String,
    val appName: String,
    val totalDurationSeconds: Long,
    val launchCount: Int
) : Serializable
