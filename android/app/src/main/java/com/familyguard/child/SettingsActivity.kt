package com.familyguard.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Экран «Настройки»: интервал передачи, дневной лимит экрана, PIN родителя,
 * работа в фоне, защита от удаления и отключение телефона от сайта.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: AppPrefs

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { rebuild() }

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
        column.addView(Ui.gradientHeader(this, "Настройки", "Хранятся на телефоне — менять их на сайте не нужно"))

        // Передача данных
        val (c1, b1) = Ui.filledCard(this)
        b1.addView(Ui.title(this, "Передача данных"))
        val intervalInput = inputField(b1, "Как часто отправлять данные, минут (5–120)", prefs.telemetryMinutes.toString())
        b1.addView(Ui.ghostButton(this, "Сохранить интервал") {
            val value = intervalInput.text.toString().trim().toIntOrNull()
            if (value == null || value < 5 || value > 120) toast("Введите число от 5 до 120")
            else { prefs.telemetryMinutes = value; toast("Интервал сохранён: $value мин. Перезапустите защиту, чтобы применить.") }
        })
        column.addView(c1)

        // Экранное время
        val (c2, b2) = Ui.filledCard(this)
        b2.addView(Ui.title(this, "Экранное время"))
        val limitInput = inputField(b2, "Дневной лимит экрана, минут (0 — выключен)", prefs.dailyLimitMinutes.toString())
        b2.addView(Ui.ghostButton(this, "Сохранить лимит") {
            val value = limitInput.text.toString().trim().toIntOrNull()
            if (value == null || value < 0 || value > 1440) toast("Введите число от 0 до 1440")
            else { prefs.dailyLimitMinutes = value; toast(if (value == 0) "Лимит выключен" else "Лимит сохранён: $value мин") }
        })
        column.addView(c2)

        // Защита и доступ
        val (c3, b3) = Ui.filledCard(this)
        b3.addView(Ui.title(this, "Защита и доступ"))
        b3.addView(Ui.ghostButton(this, if (prefs.hasPin) "Изменить или убрать PIN" else "Задать PIN родителя") { showPinSetupDialog() })
        b3.addView(Ui.ghostButton(this, "Не отключать в фоне (батарея)") {
            explain(
                "Работа в фоне",
                "Чтобы телефон не останавливал Family Guard ради экономии батареи, разрешите приложению работать без ограничений. На Xiaomi, Samsung и Huawei это важно для стабильной передачи данных.",
                "Открыть настройку"
            ) { requestIgnoreBatteryOptimizations() }
        })
        b3.addView(Ui.ghostButton(this, if (isUninstallProtectionOn()) "Выключить защиту от удаления" else "Защита от удаления (по PIN)") {
            toggleUninstallProtection()
        })
        column.addView(c3)

        // Телефон
        val (c4, b4) = Ui.filledCard(this)
        b4.addView(Ui.title(this, "Телефон"))
        b4.addView(Ui.infoRow(this, "Версия приложения", BuildConfig.VERSION_NAME))
        b4.addView(Ui.infoRow(this, "Сайт", prefs.siteUrl.ifBlank { "—" }))
        b4.addView(Ui.hint(this, "Камера — только после подтверждения на телефоне. Скрытой съёмки и прослушки нет."))
        b4.addView(Ui.dangerButton(this, "Отключить этот телефон от сайта") { confirmDisconnect() })
        column.addView(c4)

        column.addView(Ui.textButton(this, "Назад", Ui.PRIMARY_DARK) { finish() })
        setContentView(scroll)
    }

    // --- Защита от удаления --------------------------------------------------

    private fun deviceAdminComponent() = ComponentName(this, FamilyGuardDeviceAdmin::class.java)

    private fun isUninstallProtectionOn(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(deviceAdminComponent())
    }

    private fun toggleUninstallProtection() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = deviceAdminComponent()
        if (dpm.isAdminActive(component)) {
            val disable = {
                runCatching { dpm.removeActiveAdmin(component) }
                rebuild()
                toast("Защита от удаления выключена")
            }
            if (!prefs.hasPin) { disable(); return }
            val input = pinInput()
            AlertDialog.Builder(this)
                .setTitle("Выключить защиту от удаления")
                .setView(input)
                .setPositiveButton("Выключить") { _, _ ->
                    if (prefs.checkPin(input.text.toString().trim())) disable() else toast("Неверный PIN")
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        if (!prefs.hasPin) {
            toast("Сначала задайте PIN родителя — он нужен, чтобы выключать защиту")
            return
        }
        explain(
            "Защита от удаления",
            "Пока она включена, приложение нельзя удалить обычным способом. Выключить её в приложении можно только по PIN.\n\nВажно: в системных настройках администратора устройства защиту можно отключить без PIN — это ограничение Android. Ребёнок также может сбросить телефон к заводским настройкам.",
            "Включить"
        ) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Family Guard: защита приложения от удаления с телефона.")
            adminLauncher.launch(intent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // --- PIN и отключение ----------------------------------------------------

    private fun showPinSetupDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4–8 цифр, пусто — убрать PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("PIN родителя")
            .setMessage("PIN защищает отключение телефона от сайта и снятие защиты от удаления.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val pin = input.text.toString().trim()
                when {
                    pin.isEmpty() -> { prefs.setPin(""); toast("PIN убран") }
                    pin.length < 4 -> toast("PIN должен быть от 4 цифр")
                    else -> { prefs.setPin(pin); toast("PIN сохранён") }
                }
                rebuild()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDisconnect() {
        if (!prefs.hasPin) {
            explain("Отключить телефон?", "Защита остановится и телефон отвяжется от сайта. Продолжить?", "Отключить") { doDisconnect() }
            return
        }
        val input = pinInput()
        AlertDialog.Builder(this)
            .setTitle("Введите PIN")
            .setView(input)
            .setPositiveButton("Отключить") { _, _ ->
                if (prefs.checkPin(input.text.toString().trim())) doDisconnect() else toast("Неверный PIN")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doDisconnect() {
        stopService(Intent(this, MonitoringService::class.java))
        prefs.protectionStarted = false
        prefs.clearPairing()
        toast("Телефон отключён от сайта")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // --- Помощники -----------------------------------------------------------

    private fun pinInput(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "PIN родителя"
    }

    private fun inputField(parent: LinearLayout, hintText: String, value: String): TextInputEditText {
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
            inputType = InputType.TYPE_CLASS_NUMBER
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
}
