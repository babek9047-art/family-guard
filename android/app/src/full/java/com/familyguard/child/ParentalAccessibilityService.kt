package com.familyguard.child

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class ParentalAccessibilityService : AccessibilityService() {
    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val openedPackage = event.packageName?.toString() ?: return
        if (openedPackage == packageName || openedPackage == "com.android.systemui") return
        if (openedPackage !in AppPrefs(this).blockedPackages) return

        val now = System.currentTimeMillis()
        if (lastBlockedPackage == openedPackage && now - lastBlockedAt < 1500) return
        lastBlockedPackage = openedPackage
        lastBlockedAt = now
        startActivity(Intent(this, BlockedActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockedActivity.EXTRA_PACKAGE, openedPackage))
    }

    override fun onInterrupt() = Unit
}
