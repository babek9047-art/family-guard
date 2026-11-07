package com.familyguard.child

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

/**
 * Главный экран (дашборд) в стиле Prey: статус защиты, состояние телефона,
 * экранное время, SOS и переходы к разделам. Настройки вынесены в отдельный экран.
 */
class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: AppPrefs

    private var siteInput: TextInputEditText? = null
    private var codeInput: TextInputEditText? = null
    private var nameInput: TextInputEditText? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { rebuild() }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val site = result.data?.getStringExtra(QrScanActivity.EXTRA_SITE).orEmpty()
            val code = result.data?.getStringExtra(QrScanActivity.EXTRA_CODE).orEmpty()
            if (site.isNotBlank()) siteInput?.setText(site)
            if (code.isNotBlank()) codeInput?.setText(code)
            if (site.isNotBlank() && code.isNotBlank()) {
                toast("Код из QR подставлен")
                pairDevice()
            } else {
                toast("QR-код не распознан")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) rebuild()
    }

    private fun rebuild() {
        val (scroll, column) = Ui.screen(this)
        if (prefs.isPaired) buildDashboard(column) else buildConnect(column)
        setContentView(scroll)
    }

    // --- Экран подключения (телефон ещё не привязан) -------------------------

    private fun buildConnect(column: LinearLayout) {
        column.addView(Ui.gradientHeader(this, "Family Guard", "Телефон ещё не подключён к сайту"))

        val (card, body) = Ui.filledCard(this)
        body.addView(Ui.title(this, "Подключение к сайту"))
        body.addView(Ui.hint(this, "Введите адрес вашего сайта, одноразовый код из панели WordPress и имя телефона. Или отсканируйте QR-код."))
        siteInput = inputField(body, "Адрес сайта (https://…)", prefs.siteUrl, InputType.TYPE_TEXT_VARIATION_URI)
        codeInput = inputField(body, "Код подключения", "", InputType.TYPE_CLASS_NUMBER)
        nameInput = inputField(body, "Имя телефона", prefs.deviceName, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        body.addView(Ui.primaryButton(this, "Подключить") { pairDevice() })
        body.addView(Ui.ghostButton(this, "Сканировать QR-код") {
            explain(
                "Сканирование QR",
                "Откроется камера. Наведите её на QR-код из панели WordPress — адрес сайта и код подставятся автоматически.",
                "Открыть камеру"
            ) { qrLauncher.launch(Intent(this, QrScanActivity::class.java)) }
        })
        column.addView(card)

        val (infoCard, infoBody) = Ui.filledCard(this)
        infoBody.addView(Ui.title(this, "Честно и открыто"))
        infoBody.addView(Ui.hint(this, "Ребёнок всегда видит постоянное уведомление о работе защиты. Снимок с камеры делается только после подтверждения на телефоне. Скрытой съёмки и чтения переписки нет."))
        column.addView(infoCard)
    }

    // --- Дашборд (телефон привязан) ------------------------------------------

    private fun buildDashboard(column: LinearLayout) {
        val on = prefs.protectionStarted && hasLocation()
        column.addView(Ui.gradientHeader(
            this,
            prefs.deviceName,
            if (on) "Защита активна" else "Защита приостановлена"
        ))

        // SOS
        val (sosCard, sosBody) = Ui.filledCard(this)
        sosBody.addView(Ui.title(this, "Экстренная помощь"))
        sosBody.addView(Ui.hint(this, "Нажмите, если нужна срочная помощь. Родителю придёт сигнал с текущим местоположением."))
        sosBody.addView(Ui.dangerButton(this, "SOS — позвать родителя") { confirmSos() })
        column.addView(sosCard)

        // Состояние
        val (stateCard, stateBody) = Ui.filledCard(this)
        stateBody.addView(Ui.title(this, "Состояние"))
        val battery = batteryPercent()
        if (battery >= 0) stateBody.addView(Ui.infoRow(this, "Заряд батареи", "$battery%"))
        if (hasUsageAccess()) {
            val todayMs = runCatching { AppUsageCollector.todayTotalMs(this) }.getOrDefault(0L)
            val limit = prefs.dailyLimitMinutes
            val usedMin = (todayMs / 60000L).toInt()
            stateBody.addView(Ui.infoRow(
                this, "Экран сегодня",
                if (limit > 0) "${Ui.formatMinutes(todayMs)} / $limit мин" else Ui.formatMinutes(todayMs)
            ))
            if (limit > 0) stateBody.addView(Ui.progressBar(this, usedMin.toFloat() / limit, usedMin > limit))
        }
        stateBody.addView(Ui.infoRow(this, "Интервал передачи", "${prefs.telemetryMinutes} мин"))
        column.addView(stateCard)

        // Разрешения
        val (permCard, permBody) = Ui.filledCard(this)
        permBody.addView(Ui.title(this, "Разрешения"))
        val loc = hasLocation()
        val usage = hasUsageAccess()
        permBody.addView(Ui.statusRow(this, if (loc) Ui.STATE_OK else Ui.STATE_BAD,
            if (loc) "Геолокация разрешена" else "Геолокация не разрешена"))
        permBody.addView(Ui.statusRow(this, if (usage) Ui.STATE_OK else Ui.STATE_BAD,
            if (usage) "Статистика приложений разрешена" else "Статистика приложений не разрешена"))
        permBody.addView(Ui.statusRow(this, Ui.STATE_OK, "Камера — только после подтверждения"))
        if (!loc || !usage) {
            permBody.addView(Ui.ghostButton(this, "Исправить разрешения") { fixPermissions(loc, usage) })
        }
        if (BuildConfig.APP_BLOCKING) {
            permBody.addView(Ui.ghostButton(this, "Блокировка приложений") {
                explain(
                    "Служба специальных возможностей",
                    "Нужна только для закрытия приложений из списка ограничений. Приложение не читает экран и не вводит текст за вас.",
                    "Открыть настройки"
                ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        }
        column.addView(permCard)

        // Запуск / остановка
        if (on) {
            column.addView(Ui.ghostButton(this, "Остановить защиту") { stopMonitoring() })
        } else {
            column.addView(Ui.primaryButton(this, "Запустить защиту") { startMonitoring() })
        }

        // Навигация
        val (navCard, navBody) = Ui.filledCard(this)
        navBody.addView(Ui.title(this, "Разделы"))
        navBody.addView(Ui.ghostButton(this, "Экранное время по приложениям") {
            startActivity(Intent(this, ScreenTimeActivity::class.java))
        })
        navBody.addView(Ui.ghostButton(this, "Настройки") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        column.addView(navCard)
    }

    // --- SOS -----------------------------------------------------------------

    private fun confirmSos() {
        AlertDialog.Builder(this)
            .setTitle("Отправить SOS?")
            .setMessage("Родителю придёт сигнал тревоги с текущим местоположением телефона.")
            .setPositiveButton("Отправить") { _, _ -> sendSos() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendSos() {
        if (hasLocation()) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(this).lastLocation
                    .addOnSuccessListener { loc -> postSos(loc?.latitude, loc?.longitude) }
                    .addOnFailureListener { postSos(null, null) }
            }.onFailure { postSos(null, null) }
        } else {
            postSos(null, null)
        }
    }

    private fun postSos(lat: Double?, lon: Double?) {
        toast("Отправляю сигнал SOS…")
        executor.execute {
            runCatching { ApiClient(this).sendEvent("sos", lat, lon) }
                .onSuccess { runOnUiThread { toast("Сигнал SOS отправлен родителю") } }
                .onFailure { error -> runOnUiThread { toast(error.message ?: "Не удалось отправить SOS") } }
        }
    }

    // --- Подключение ---------------------------------------------------------

    private fun pairDevice() {
        val site = siteInput?.text?.toString()?.trim()?.trimEnd('/').orEmpty()
        val code = codeInput?.text?.toString()?.trim().orEmpty()
        val name = nameInput?.text?.toString()?.trim().orEmpty().ifBlank { Build.MODEL }
        if (!site.startsWith("https://") || code.length < 4) {
            toast("Введите HTTPS-адрес сайта и код подключения")
            return
        }
        executor.execute {
            runCatching { ApiClient(this).pair(site, code, name) }
                .onSuccess { result ->
                    prefs.siteUrl = site
                    prefs.deviceId = result.deviceId
                    prefs.deviceToken = result.token
                    prefs.deviceName = name
                    runOnUiThread {
                        toast("Телефон подключён")
                        requestRuntimePermissions()
                        rebuild()
                    }
                }
                .onFailure { error -> runOnUiThread { toast(error.message ?: "Не удалось подключиться") } }
        }
    }

    // --- Разрешения ----------------------------------------------------------

    private fun fixPermissions(loc: Boolean, usage: Boolean) {
        if (!loc) {
            explain(
                "Геолокация и уведомления",
                "Нужно, чтобы показывать родителю местоположение телефона и выводить постоянное уведомление о работе защиты. Выберите «Разрешить всегда».",
                "Разрешить"
            ) { requestRuntimePermissions() }
        } else if (!usage) {
            explain(
                "Доступ к статистике приложений",
                "Нужно, чтобы считать экранное время по приложениям (например, YouTube). Содержимое переписки не читается.",
                "Открыть настройки"
            ) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
        if (Build.VERSION.SDK_INT >= 30 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Для постоянной геолокации выберите «Разрешить всегда» в настройках")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // --- Защита --------------------------------------------------------------

    private fun startMonitoring() {
        if (!prefs.isPaired) {
            toast("Сначала подключите телефон к сайту")
            return
        }
        if (!hasLocation()) {
            toast("Сначала разрешите геолокацию")
            requestRuntimePermissions()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java))
        prefs.protectionStarted = true
        toast("Защита запущена")
        rebuild()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitoringService::class.java))
        prefs.protectionStarted = false
        toast("Защита остановлена")
        rebuild()
    }

    // --- Утилиты -------------------------------------------------------------

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val manager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            manager.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            manager.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun batteryPercent(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun inputField(parent: LinearLayout, hintText: String, value: String, inputType: Int): TextInputEditText {
        val til = TextInputLayout(parent.context).apply {
            hint = hintText
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(parent.context, 10) }
        }
        val edit = TextInputEditText(til.context).apply {
            setText(value)
            setSingleLine(true)
            this.inputType = if (inputType and InputType.TYPE_CLASS_NUMBER != 0) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or inputType
            }
        }
        til.addView(edit)
        parent.addView(til)
        return edit
    }

    private fun explain(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirm) { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
