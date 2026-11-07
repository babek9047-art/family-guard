package com.familyguard.child

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Единый визуальный язык приложения (дизайн-система из ТЗ).
 * Все экраны строятся этими помощниками, чтобы выглядеть как один продукт.
 */
object Ui {
    // Цвета дизайн-системы (светлая тема).
    const val PRIMARY = 0xFF2563EB.toInt()
    const val PRIMARY_DARK = 0xFF1E40AF.toInt()
    const val SUCCESS = 0xFF16A34A.toInt()
    const val WARNING = 0xFFF59E0B.toInt()
    const val DANGER = 0xFFDC2626.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val BG = 0xFFF1F5F9.toInt()
    const val TEXT = 0xFF0F172A.toInt()
    const val TEXT2 = 0xFF64748B.toInt()
    const val BORDER = 0xFFE2E8F0.toInt()
    const val TONAL = 0xFFE8EEFE.toInt()
    const val OFF = 0xFFB4BBC8.toInt()

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /** Прокручиваемый экран: возвращает ScrollView и вертикальный контейнер внутри него. */
    fun screen(ctx: Context): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 28))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(column)
        }
        return scroll to column
    }

    /** Шапка с градиентом в стиле Prey: крупный заголовок и подпись. */
    fun gradientHeader(ctx: Context, title: String, subtitle: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(PRIMARY, PRIMARY_DARK)
            ).apply { cornerRadius = dp(ctx, 20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 16) }
        }
        box.addView(TextView(ctx).apply {
            text = title
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 14f
            setTextColor(0xFFDCE6FF.toInt())
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        return box
    }

    fun card(ctx: Context): MaterialCardView = MaterialCardView(ctx).apply {
        radius = dp(ctx, 18).toFloat()
        cardElevation = dp(ctx, 2).toFloat()
        setCardBackgroundColor(SURFACE)
        useCompatPadding = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12) }
    }

    fun cardBody(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
    }

    /** Карточка с готовым телом: возвращает (карточка, тело) для наполнения. */
    fun filledCard(ctx: Context): Pair<MaterialCardView, LinearLayout> {
        val cv = card(ctx)
        val body = cardBody(ctx)
        cv.addView(body)
        return cv to body
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    fun hint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEXT2)
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 10))
    }

    private fun baseButton(ctx: Context, label: String, action: () -> Unit): MaterialButton =
        MaterialButton(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            cornerRadius = dp(ctx, 14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
        }

    fun primaryButton(ctx: Context, label: String, action: () -> Unit): MaterialButton =
        baseButton(ctx, label, action).apply {
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            setTextColor(Color.WHITE)
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
        }

    fun ghostButton(ctx: Context, label: String, action: () -> Unit): MaterialButton =
        baseButton(ctx, label, action).apply {
            backgroundTintList = ColorStateList.valueOf(TONAL)
            setTextColor(PRIMARY_DARK)
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
        }

    fun dangerButton(ctx: Context, label: String, action: () -> Unit): MaterialButton =
        baseButton(ctx, label, action).apply {
            backgroundTintList = ColorStateList.valueOf(DANGER)
            setTextColor(Color.WHITE)
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
        }

    fun textButton(ctx: Context, label: String, color: Int, action: () -> Unit): MaterialButton =
        baseButton(ctx, label, action).apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(color)
        }

    /** Строка статуса: цветная точка + текст. */
    fun statusRow(ctx: Context, state: Int, text: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        val size = dp(ctx, 11)
        val color = when (state) {
            STATE_OK -> SUCCESS
            STATE_WARN -> WARNING
            STATE_BAD -> DANGER
            else -> OFF
        }
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(ctx, 10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        })
        row.addView(TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(TEXT)
        })
        return row
    }

    /** Строка «подпись — значение». */
    fun infoRow(ctx: Context, label: String, value: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 7), 0, dp(ctx, 7))
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(TEXT2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
        })
        return row
    }

    /** Горизонтальная полоса-индикатор (например, доля лимита экрана). */
    fun progressBar(ctx: Context, fraction: Float, over: Boolean): View {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            ).apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 4) }
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8).toFloat()
                setColor(BORDER)
            }
        }
        val clamped = fraction.coerceIn(0f, 1f)
        track.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, clamped)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8).toFloat()
                setColor(if (over) DANGER else PRIMARY)
            }
        })
        if (clamped < 1f) {
            track.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - clamped)
            })
        }
        return track
    }

    fun formatMinutes(ms: Long): String {
        val minutes = (ms / 60000L).toInt()
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }

    const val STATE_OK = 0
    const val STATE_WARN = 1
    const val STATE_BAD = 2
    const val STATE_OFF = 3
}
