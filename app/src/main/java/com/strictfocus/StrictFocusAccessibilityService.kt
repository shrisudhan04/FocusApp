package com.strictfocus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrictFocusAccessibilityService : AccessibilityService() {

    // Keep accessibility actions on the main thread.
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastRedirectAt = 0L
    private var redirectInProgress = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Listen for app/window transitions.
        if (
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // Ignore StrictFocus itself and Android System UI.
        if (
            pkg == packageName ||
            pkg == "com.android.systemui"
        ) {
            return
        }

        if (redirectInProgress) return

        scope.launch {
            // Read the active lock rule using IO.
            val locked = withContext(Dispatchers.IO) {
                StrictLockManager.isLocked(
                    this@StrictFocusAccessibilityService,
                    pkg
                )
            }

            if (!locked) return@launch

            val now = System.currentTimeMillis()

            // Prevent repeated redirects caused by multiple accessibility events.
            if (now - lastRedirectAt < 1000L) {
                return@launch
            }

            lastRedirectAt = now
            redirectInProgress = true

            try {
                // These actions run on the main thread.
                performGlobalAction(GLOBAL_ACTION_HOME)

                val intent = Intent(
                    this@StrictFocusAccessibilityService,
                    StrictBlockActivity::class.java
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

                startActivity(intent)

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                redirectInProgress = false
            }
        }
    }

    override fun onInterrupt() {
        // No action required.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}