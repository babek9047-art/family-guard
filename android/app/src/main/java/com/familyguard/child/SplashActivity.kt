package com.familyguard.child

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Короткая заставка: логотип на фирменном фоне, затем переход —
 * к онбордингу (первый запуск) или на главный экран.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("family_guard", Context.MODE_PRIVATE)
        val crash = prefs.getString("last_crash", null)
        if (!crash.isNullOrBlank()) {
            prefs.edit().remove("last_crash").apply()
            setContentView(buildCrashView(crash))
            return
        }
        setContentView(buildSplash())
        Handler(Looper.getMainLooper()).postDelayed({ route() }, 750)
    }

    private fun buildCrashView(trace: String): ScrollView {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        box.addView(TextView(this).apply {
            text = "Ошибка запуска — покажите этот текст разработчику"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFDC2626.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        box.addView(TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(0xFF0F172A.toInt())
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        })
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(box)
        }
    }

    private fun route() {
        val prefs = AppPrefs(this)
        val next = if (prefs.onboardingSeen) MainActivity::class.java else OnboardingActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    private fun buildSplash(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(ImageView(this@SplashActivity).apply {
                setImageResource(R.drawable.ic_shield)
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
            addView(TextView(this@SplashActivity).apply {
                text = "Family Guard"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, dp(14), 0, 0)
            })
            addView(TextView(this@SplashActivity).apply {
                text = "Спокойствие за ребёнка"
                textSize = 14f
                setTextColor(0xFFDCE6FF.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
    }
}
