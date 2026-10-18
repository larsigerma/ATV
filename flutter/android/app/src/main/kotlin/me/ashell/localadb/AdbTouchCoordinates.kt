package me.ashell.localadb

import android.os.Handler
import android.os.Looper
import me.ashell.SCREEN_INFO
import me.ashell.nZW99cdXQ0COhB2o

/** Read-only geometry adapter; it never changes capture dimensions or service state. */
object AdbTouchCoordinates {
    @JvmStatic fun scale(): Int = SCREEN_INFO.scale.coerceAtLeast(1)

    @JvmStatic fun releaseAccessibilityTouch() {
        Handler(Looper.getMainLooper()).post {
            nZW99cdXQ0COhB2o.ctx?.suspendRemoteTouchForAdb()
        }
    }
}
