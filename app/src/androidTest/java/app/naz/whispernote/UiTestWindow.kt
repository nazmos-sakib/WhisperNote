package app.naz.whispernote

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Make only the instrumentation fixture visible; do not dismiss or disable the device lock. */
fun ComponentActivity.showUiTestWindow() {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
