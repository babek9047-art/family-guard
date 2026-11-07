package com.familyguard.child

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Приветственный онбординг: 3 слайда. Показывается один раз.
 * В конце отмечает onboardingSeen и открывает главный экран.
 */
class OnboardingActivity : AppCompatActivity() {

    private val slides = listOf(
        Triple("Family Guard", "Спокойствие за ребёнка", "Знайте, что ребёнок в безопасности: местоположение, экранное время и поиск телефона."),
        Triple("Честно и открыто", "Без скрытой слежки", "Ребёнок всегда видит, что защита включена. Снимок с камеры — только после подтверждения на телефоне."),
        Triple("Поиск телефона", "Если потерялся", "Громкий сигнал, сообщение на экран и кнопка SOS, чтобы быстро найти телефон и связаться.")
    )

    private var index = 0
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val (title, subtitle, body) = slides[index]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Верхний фирменный блок с логотипом.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.PRIMARY)
            setPadding(dp(24), dp(72), dp(24), dp(72))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(R.drawable.ic_shield)
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
        })

        // Нижняя часть: заголовок, текст, точки, кнопка.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        bottom.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.TEXT)
        })
        bottom.addView(TextView(this).apply {
            text = subtitle
            textSize = 16f
            setTextColor(Ui.PRIMARY_DARK)
            setPadding(0, dp(4), 0, dp(10))
        })
        bottom.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setTextColor(Ui.TEXT2)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        bottom.addView(dots())
        val last = index == slides.size - 1
        bottom.addView(Ui.primaryButton(this, if (last) "Начать" else "Далее") {
            if (last) finishOnboarding() else { index++; render() }
        })
        if (!last) {
            bottom.addView(Ui.textButton(this, "Пропустить", Ui.TEXT2) { finishOnboarding() })
        }
        root.addView(bottom)
        setContentView(root)
    }

    private fun dots(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(14))
        }
        for (i in slides.indices) {
            row.addView(View(this).apply {
                val size = dp(8)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    leftMargin = dp(4); rightMargin = dp(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == index) Ui.PRIMARY else Ui.OFF)
                }
            })
        }
        return row
    }

    private fun finishOnboarding() {
        AppPrefs(this).onboardingSeen = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
