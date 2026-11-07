package com.familyguard.child

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// Открытый режим пропажи: громкий сигнал и видимое сообщение. Ничего не снимает и не прячется.
class LostModeActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        ringOnly = intent.getBooleanExtra(EXTRA_RING_ONLY, false)
        val prefs = AppPrefs(this)
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { prefs.lostMessage }
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty().ifBlank { prefs.lostPhone }
        setContentView(buildContent(message, phone))
        startAlarm()
        // Быстрый сигнал сам затихает через 30 секунд.
        if (ringOnly) handler.postDelayed({ stopAlarmAndFinish() }, 30_000)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun buildContent(message: String, phone: String): LinearLayout {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFB3261E.toInt())
            setPadding(px(28), px(28), px(28), px(28))
        }
        root.addView(TextView(this).apply {
            text = if (ringOnly) "Звуковой сигнал" else "Телефон потерян"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 30f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (ringOnly) "Это сигнал поиска телефона от родителя."
                else (message.ifBlank { "Этот телефон под семейным контролем. Пожалуйста, верните его владельцу." })
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, px(16), 0, px(24))
        })
        if (!ringOnly && phone.isNotBlank()) {
            root.addView(Button(this).apply {
                text = "Позвонить владельцу"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(10) }
            })
        }
        root.addView(Button(this).apply {
            text = "Остановить сигнал"
            isAllCaps = false
            setOnClickListener { onStopPressed() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        return root
    }

    private fun onStopPressed() {
        val prefs = AppPrefs(this)
        // Быстрый сигнал глушится сразу; режим пропажи — только по PIN родителя, если он задан.
        if (ringOnly || !prefs.hasPin) { stopAlarmAndFinish(); return }
        val input = android.widget.EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN родителя"
        }
        AlertDialog.Builder(this)
            .setTitle("Остановить сигнал")
            .setView(input)
            .setPositiveButton("Остановить") { _, _ ->
                if (prefs.checkPin(input.text.toString().trim())) stopAlarmAndFinish()
                else Toast.makeText(this, "Неверный PIN", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startAlarm() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@LostModeActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun stopAlarmAndFinish() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
        if (!ringOnly) AppPrefs(this).lostActive = false
        finish()
    }

    override fun onDestroy() {
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_RING_ONLY = "ring_only"
    }
}
