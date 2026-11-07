package com.familyguard.child

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BlockedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = runCatching {
            val info = packageManager.getApplicationInfo(blockedPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(blockedPackage)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@BlockedActivity).apply {
                text = "Доступ к приложению ограничен"
                textSize = 25f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@BlockedActivity).apply {
                text = "$label временно заблокирован родительским контролем."
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 24)
            })
            addView(Button(this@BlockedActivity).apply {
                text = "На главный экран"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    finish()
                }
            })
        })
    }

    companion object { const val EXTRA_PACKAGE = "blocked_package" }
}
