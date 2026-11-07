package com.familyguard.child

import android.app.Application

/**
 * Ловит необработанные сбои и сохраняет текст ошибки, чтобы показать его
 * на экране при следующем открытии (для диагностики без компьютера).
 */
class FamilyGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                // commit() (синхронно): процесс сейчас будет убит, и асинхронный
                // apply() не успел бы записать текст ошибки на диск.
                getSharedPreferences("family_guard", MODE_PRIVATE).edit()
                    .putString("last_crash", sw.toString())
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
