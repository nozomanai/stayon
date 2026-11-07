package app.stayon

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Leaves no cached process while screen-on is disabled. Xiaomi counts that
 * cached process toward background memory, often around 100MB, even though
 * the app is not doing any work.
 */
internal object IdleExit {
    private const val TAG = "StayOn"
    private const val DELAY_MS = 600L

    private val handler = Handler(Looper.getMainLooper())
    private val exit = Runnable { exitNow() }

    @Volatile
    var activityVisible: Boolean = false

    @Volatile
    var tileBound: Boolean = false

    /** Set while a system settings page we opened is still in front. */
    @Volatile
    var defer: Boolean = false

    fun cancel() {
        handler.removeCallbacks(exit)
    }

    fun schedule() {
        handler.removeCallbacks(exit)
        handler.postDelayed(exit, DELAY_MS)
    }

    private fun exitNow() {
        if (StayOnService.isRunning || StayOnService.starting) return
        if (activityVisible || tileBound || defer) return
        Log.i(TAG, "screen stay is off, exiting process")
        Process.killProcess(Process.myPid())
    }
}
