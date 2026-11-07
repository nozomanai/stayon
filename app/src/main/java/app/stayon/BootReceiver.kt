package app.stayon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * If the process is killed or the app is updated while the timer is extended,
 * put the previous screen timeout back once Stay On is no longer running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!StayOnService.isRunning) {
                    ScreenTimeout.restore(context)
                    IdleExit.schedule()
                }
            }
        }
    }
}
