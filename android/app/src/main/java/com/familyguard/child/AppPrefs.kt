package com.familyguard.child

import android.content.Context
import org.json.JSONArray

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("family_guard", Context.MODE_PRIVATE)

    var siteUrl: String
        get() = prefs.getString("site_url", "") ?: ""
        set(value) = prefs.edit().putString("site_url", value.trimEnd('/')).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(value) = prefs.edit().putString("device_token", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString("device_name", value).apply()

    var blockedPackages: Set<String>
        get() {
            val raw = prefs.getString("blocked_packages", "[]") ?: "[]"
            return runCatching {
                val json = JSONArray(raw)
                buildSet { for (i in 0 until json.length()) add(json.getString(i)) }
            }.getOrDefault(emptySet())
        }
        set(value) {
            val json = JSONArray()
            value.filter { it.isNotBlank() }.sorted().forEach(json::put)
            prefs.edit().putString("blocked_packages", json.toString()).apply()
        }

    // Встроенная настройка на телефоне: как часто отправлять данные (5–120 минут).
    var telemetryMinutes: Int
        get() = prefs.getInt("telemetry_minutes", 5).coerceIn(5, 120)
        set(value) = prefs.edit().putInt("telemetry_minutes", value.coerceIn(5, 120)).apply()

    // Дневной лимит экрана в минутах, 0 — выключен.
    var dailyLimitMinutes: Int
        get() = prefs.getInt("daily_limit_minutes", 0).coerceIn(0, 1440)
        set(value) = prefs.edit().putInt("daily_limit_minutes", value.coerceIn(0, 1440)).apply()

    // Дата (yyyy-ddd), когда ребёнку уже показали предупреждение о лимите.
    var limitNotifiedDay: Int
        get() = prefs.getInt("limit_notified_day", -1)
        set(value) = prefs.edit().putInt("limit_notified_day", value).apply()

    // Защита запущена пользователем (для статуса на главном экране).
    var protectionStarted: Boolean
        get() = prefs.getBoolean("protection_started", false)
        set(value) = prefs.edit().putBoolean("protection_started", value).apply()

    // Режим пропажи (антивор): громкий сигнал и сообщение на весь экран.
    var lostActive: Boolean
        get() = prefs.getBoolean("lost_active", false)
        set(value) = prefs.edit().putBoolean("lost_active", value).apply()

    var lostMessage: String
        get() = prefs.getString("lost_message", "") ?: ""
        set(value) = prefs.edit().putString("lost_message", value).apply()

    var lostPhone: String
        get() = prefs.getString("lost_phone", "") ?: ""
        set(value) = prefs.edit().putString("lost_phone", value).apply()

    val hasPin: Boolean
        get() = !prefs.getString("parent_pin", "").isNullOrBlank()

    fun setPin(pin: String) {
        if (pin.isBlank()) prefs.edit().remove("parent_pin").apply()
        else prefs.edit().putString("parent_pin", sha256(pin)).apply()
    }

    fun checkPin(pin: String): Boolean =
        prefs.getString("parent_pin", "").orEmpty() == sha256(pin)

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    val isPaired: Boolean
        get() = siteUrl.startsWith("https://") && deviceId.isNotBlank() && deviceToken.isNotBlank()

    fun clearPairing() {
        prefs.edit().remove("device_id").remove("device_token").apply()
    }
}
