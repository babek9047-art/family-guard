package com.familyguard.child

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val prefs = AppPrefs(context)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun pair(siteUrl: String, pairingCode: String, deviceName: String): PairResult {
        require(siteUrl.startsWith("https://")) { "Адрес сайта должен начинаться с https://" }
        val cleanUrl = siteUrl.trimEnd('/')
        val body = JSONObject()
            .put("pairing_code", pairingCode.trim())
            .put("device_name", deviceName.trim())
            .put("platform", "android")
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("model", android.os.Build.MODEL)
            .put("app_version", BuildConfig.VERSION_NAME)
            .toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$cleanUrl/wp-json/family-guard/v1/pair")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(raw, "Ошибка подключения: ${response.code}"))
            val json = JSONObject(raw)
            return PairResult(json.getString("device_id"), json.getString("token"))
        }
    }

    fun uploadTelemetry(payload: JSONObject) {
        executeJson("telemetry", "POST", payload)
    }

    /** Разовое событие с телефона (например, SOS от ребёнка). Координаты необязательны. */
    fun sendEvent(type: String, latitude: Double?, longitude: Double?) {
        val payload = JSONObject().put("type", type)
        if (latitude != null && longitude != null) {
            payload.put("latitude", latitude).put("longitude", longitude)
        }
        executeJson("event", "POST", payload)
    }

    fun fetchCommands(): List<RemoteCommand> {
        val raw = executeJson("commands", "GET", null)
        val array = JSONObject(raw).optJSONArray("commands") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(RemoteCommand(item.getLong("id"), item.getString("type"), item.optJSONObject("payload")?.toString() ?: "{}"))
            }
        }
    }

    fun completeCommand(commandId: Long, status: String, message: String = "") {
        val payload = JSONObject().put("status", status).put("message", message)
        executeJson("commands/$commandId/result", "POST", payload)
    }

    fun uploadSnapshot(commandId: Long, file: File): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("command_id", commandId.toString())
            .addFormDataPart("snapshot", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = authorizedRequest("snapshot").post(requestBody).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(raw, "Не удалось отправить снимок: ${response.code}"))
            return JSONObject(raw).optString("url")
        }
    }

    private fun executeJson(path: String, method: String, payload: JSONObject?): String {
        val builder = authorizedRequest(path)
        if (method == "POST") builder.post((payload ?: JSONObject()).toString().toRequestBody(jsonType))
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(raw, "Ошибка сервера: ${response.code}"))
            return raw
        }
    }

    private fun authorizedRequest(path: String): Request.Builder {
        check(prefs.isPaired) { "Устройство ещё не подключено" }
        return Request.Builder()
            .url("${prefs.siteUrl}/wp-json/family-guard/v1/$path")
            .header("Authorization", "Bearer ${prefs.deviceToken}")
            .header("X-Family-Guard-Device", prefs.deviceId)
    }

    private fun readError(raw: String, fallback: String): String = runCatching {
        JSONObject(raw).optString("message", fallback)
    }.getOrDefault(fallback)
}
