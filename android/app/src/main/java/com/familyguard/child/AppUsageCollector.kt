package com.familyguard.child

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

object AppUsageCollector {
    fun last24Hours(context: Context): List<AppUsage> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.HOURS.toMillis(24)
        val packageManager = context.packageManager
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
            .asSequence()
            .filter { it.totalTimeInForeground > 0 }
            .map { stats ->
                val label = runCatching {
                    val info = packageManager.getApplicationInfo(stats.packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(stats.packageName)
                AppUsage(stats.packageName, label, stats.totalTimeInForeground)
            }
            .sortedByDescending { it.foregroundMs }
            .take(100)
            .toList()
    }

    // Суммарное время на экране с начала текущего дня.
    fun todayTotalMs(context: Context): Long {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        val end = System.currentTimeMillis()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
            .sumOf { it.totalTimeInForeground.coerceAtLeast(0) }
    }
}
