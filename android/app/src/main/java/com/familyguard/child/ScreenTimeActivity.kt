package com.familyguard.child

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «Экранное время по приложениям»: сколько времени ушло на каждое
 * приложение за сутки (видно YouTube, игры и прочее). Только чтение статистики,
 * без доступа к содержимому приложений.
 */
class ScreenTimeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val (scroll, column) = Ui.screen(this)
        column.addView(Ui.gradientHeader(this, "Экранное время", "За последние 24 часа"))

        if (!hasUsageAccess()) {
            val (card, body) = Ui.filledCard(this)
            body.addView(Ui.title(this, "Нужен доступ к статистике"))
            body.addView(Ui.hint(this, "Чтобы видеть время по приложениям, включите доступ к статистике использования для Family Guard."))
            body.addView(Ui.primaryButton(this, "Открыть настройки") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            })
            column.addView(card)
            setContentView(scroll)
            return
        }

        val todayMs = runCatching { AppUsageCollector.todayTotalMs(this) }.getOrDefault(0L)
        val apps = runCatching { AppUsageCollector.last24Hours(this) }.getOrDefault(emptyList())

        val (totalCard, totalBody) = Ui.filledCard(this)
        totalBody.addView(Ui.title(this, "Всего сегодня"))
        totalBody.addView(Ui.infoRow(this, "На экране", Ui.formatMinutes(todayMs)))
        column.addView(totalCard)

        val (listCard, listBody) = Ui.filledCard(this)
        listBody.addView(Ui.title(this, "По приложениям"))
        if (apps.isEmpty()) {
            listBody.addView(Ui.hint(this, "Пока нет данных об использовании приложений."))
        } else {
            val max = apps.first().foregroundMs.coerceAtLeast(1L)
            apps.take(30).forEach { app ->
                listBody.addView(Ui.infoRow(this, app.label, Ui.formatMinutes(app.foregroundMs)))
                listBody.addView(Ui.progressBar(this, app.foregroundMs.toFloat() / max, false))
            }
        }
        column.addView(listCard)

        column.addView(Ui.textButton(this, "Назад", Ui.PRIMARY_DARK) { finish() })
        setContentView(scroll)
    }

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
}
