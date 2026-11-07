package com.familyguard.child

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MonitoringService : Service() {
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var api: ApiClient
    private lateinit var prefs: AppPrefs
    private val started = AtomicBoolean(false)
    private val notifiedCommands = Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var latitude: Double? = null
    @Volatile private var longitude: Double? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        api = ApiClient(this)
        createChannels()
        startForeground(MONITORING_NOTIFICATION, monitoringNotification())
        scheduler = Executors.newScheduledThreadPool(2)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started.compareAndSet(false, true)) {
            val minutes = prefs.telemetryMinutes.toLong()
            scheduler.scheduleWithFixedDelay(::pollSafely, 2, 60, TimeUnit.SECONDS)
            scheduler.execute(::telemetrySafely)
            scheduler.scheduleWithFixedDelay(::telemetrySafely, minutes, minutes, TimeUnit.MINUTES)
        }
        return START_STICKY
    }

    private fun pollSafely() {
        if (!prefs.isPaired) return
        runCatching { api.fetchCommands() }.onSuccess { commands ->
            commands.forEach { command -> runCatching { handleCommand(command) } }
        }
        // В режиме пропажи шлём местоположение чаще — на каждом опросе команд.
        if (prefs.lostActive) runCatching { telemetrySafely() }
    }

    private fun handleCommand(command: RemoteCommand) {
        when (command.type) {
            "camera_snapshot" -> {
                if (notifiedCommands.add(command.id)) showCameraRequest(command.id)
            }
            "lost_mode" -> runCatching {
                val json = JSONObject(command.payload)
                val active = json.optBoolean("active", false)
                prefs.lostActive = active
                if (active) {
                    prefs.lostMessage = json.optString("message")
                    prefs.lostPhone = json.optString("phone")
                    showLostAlert(false, prefs.lostMessage, prefs.lostPhone)
                    telemetrySafely()
                } else {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(LOST_NOTIFICATION)
                }
                api.completeCommand(command.id, "completed", if (active) "Режим пропажи включён" else "Режим пропажи выключен")
            }.onFailure { api.completeCommand(command.id, "failed", it.message.orEmpty()) }
            "ring" -> {
                showLostAlert(true, "", "")
                api.completeCommand(command.id, "completed", "Сигнал подан")
            }
            "set_blocked_apps" -> if (!BuildConfig.APP_BLOCKING) {
                api.completeCommand(command.id, "failed", "Блокировка приложений недоступна в этой версии")
            } else runCatching {
                val array = JSONObject(command.payload).optJSONArray("packages") ?: JSONArray()
                prefs.blockedPackages = buildSet {
                    for (i in 0 until array.length()) add(array.getString(i))
                }
                api.completeCommand(command.id, "completed", "Ограничения обновлены")
            }.onFailure { api.completeCommand(command.id, "failed", it.message.orEmpty()) }
            else -> api.completeCommand(command.id, "failed", "Неизвестная команда")
        }
    }

    private fun telemetrySafely() {
        if (!prefs.isPaired) return
        updateLastLocation()
        runCatching {
            val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val usage = JSONArray()
            AppUsageCollector.last24Hours(this).forEach { item ->
                usage.put(JSONObject()
                    .put("package", item.packageName)
                    .put("label", item.label)
                    .put("foreground_ms", item.foregroundMs))
            }
            val screenToday = AppUsageCollector.todayTotalMs(this)
            val payload = JSONObject()
                .put("battery", battery)
                .put("device_time", System.currentTimeMillis())
                .put("apps", usage)
                .put("permissions", currentPermissions())
                .put("screen_today_ms", screenToday)
                .put("daily_limit_minutes", prefs.dailyLimitMinutes)
                .put("interval_minutes", prefs.telemetryMinutes)
                .put("app_version", BuildConfig.VERSION_NAME)
            latitude?.let { payload.put("latitude", it) }
            longitude?.let { payload.put("longitude", it) }
            api.uploadTelemetry(payload)
            checkDailyLimit(screenToday)
        }
    }

    private fun currentPermissions(): JSONObject {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val usageOk = runCatching {
            val manager = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            manager.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        val batteryOk = runCatching {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        return JSONObject()
            .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            .put("background_location",
                Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            .put("usage", usageOk)
            .put("notifications",
                Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            .put("battery_unrestricted", batteryOk)
    }

    private fun checkDailyLimit(screenTodayMs: Long) {
        val limit = prefs.dailyLimitMinutes
        if (limit <= 0) return
        if (screenTodayMs < limit * 60_000L) return
        val calendar = java.util.Calendar.getInstance()
        val today = calendar.get(java.util.Calendar.YEAR) * 1000 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
        if (prefs.limitNotifiedDay == today) return
        prefs.limitNotifiedDay = today
        val notification = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(com.familyguard.child.R.drawable.ic_shield)
            .setContentTitle("Дневной лимит экрана достигнут")
            .setContentText("Сегодня телефон использовался $limit минут и больше. Родитель видит это в панели.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(LIMIT_NOTIFICATION, notification)
    }

    private fun updateLastLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        runCatching {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        latitude = location.latitude
                        longitude = location.longitude
                    }
                }
        }
    }

    private fun monitoringNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(com.familyguard.child.R.drawable.ic_shield)
            .setContentTitle("Family Guard включён")
            .setContentText("Геолокация и статистика передаются родителю")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showCameraRequest(commandId: Long) {
        val intent = Intent(this, CameraConsentActivity::class.java)
            .putExtra(CameraConsentActivity.EXTRA_COMMAND_ID, commandId)
        val pending = PendingIntent.getActivity(
            this, commandId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(com.familyguard.child.R.drawable.ic_shield)
            .setContentTitle("Родитель запрашивает снимок")
            .setContentText("Нажмите, чтобы открыть камеру и решить, отправлять ли снимок")
            .setContentIntent(pending)
            .addAction(0, "Открыть камеру", pending)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(CAMERA_NOTIFICATION_BASE + commandId.toInt(), notification)
    }

    private fun showLostAlert(ringOnly: Boolean, message: String, phone: String) {
        val intent = Intent(this, LostModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LostModeActivity.EXTRA_RING_ONLY, ringOnly)
            .putExtra(LostModeActivity.EXTRA_MESSAGE, message)
            .putExtra(LostModeActivity.EXTRA_PHONE, phone)
        val pending = PendingIntent.getActivity(
            this, if (ringOnly) 31 else 30, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(com.familyguard.child.R.drawable.ic_shield)
            .setContentTitle(if (ringOnly) "Сигнал поиска телефона" else "Телефон в режиме пропажи")
            .setContentText(if (ringOnly) "Нажмите, чтобы остановить сигнал" else "Нажмите, чтобы открыть сообщение владельца")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setOngoing(!ringOnly)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(LOST_NOTIFICATION, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_MONITORING,
            "Работа родительского контроля",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Постоянное уведомление о передаче данных родителю" })
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_REQUESTS,
            "Запросы родителя",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Видимые запросы снимка с камеры" })
    }

    override fun onDestroy() {
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_MONITORING = "family_guard_monitoring"
        private const val CHANNEL_REQUESTS = "family_guard_requests"
        private const val MONITORING_NOTIFICATION = 1001
        private const val LIMIT_NOTIFICATION = 1002
        private const val LOST_NOTIFICATION = 1003
        const val CAMERA_NOTIFICATION_BASE = 20000
    }
}
