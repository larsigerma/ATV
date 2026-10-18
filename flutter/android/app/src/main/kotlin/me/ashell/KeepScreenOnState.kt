package me.ashell

import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.annotation.Keep
import pkg2230.ClsFx9V0S

/** Shared by the visible Activity and the existing background floating window. */
@Keep
object KeepScreenOnState {
    private external fun hasRemoteConnection(): Boolean

    fun shouldKeepScreenOn(): Boolean {
        // This existing bridge also ensures libashell is loaded before our JNI call.
        return when (ClsFx9V0S.OCpC4h8m("keep-screen-on")) {
            "never" -> false
            "during-controlled" -> hasRemoteConnection()
            else -> DFm8Y8iMScvB2YDw.isReady
        }
    }

    fun applyTo(window: Window) {
        val on = shouldKeepScreenOn()
        if ((window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0) == on) return
        if (on) window.addFlags(FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(FLAG_KEEP_SCREEN_ON)
    }
}
