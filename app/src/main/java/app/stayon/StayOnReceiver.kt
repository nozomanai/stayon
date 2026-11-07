package app.stayon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StayOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == StayOnService.ACTION_STOP) {
            StayOnService.stop(context)
        }
    }
}
