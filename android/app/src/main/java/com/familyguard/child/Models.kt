package com.familyguard.child

data class PairResult(val deviceId: String, val token: String)

data class RemoteCommand(
    val id: Long,
    val type: String,
    val payload: String
)

data class AppUsage(
    val packageName: String,
    val label: String,
    val foregroundMs: Long
)
