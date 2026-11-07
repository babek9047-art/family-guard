package com.familyguard.child

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// Открытый администратор устройства: пока включён, Android не даёт удалить приложение
// напрямую. Активация и отключение видны ребёнку и подтверждаются им.
class FamilyGuardDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Если выключить защиту от удаления, приложение Family Guard можно будет удалить с телефона."
}
