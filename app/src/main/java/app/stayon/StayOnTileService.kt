package app.stayon

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class StayOnTileService : TileService() {

    override fun onBind(intent: Intent): IBinder {
        IdleExit.tileBound = true
        IdleExit.cancel()
        return super.onBind(intent)!!
    }

    override fun onUnbind(intent: Intent): Boolean {
        IdleExit.tileBound = false
        val result = super.onUnbind(intent)
        IdleExit.schedule()
        return result
    }

    override fun onStartListening() {
        IdleExit.tileBound = true
        IdleExit.cancel()
        if (!StayOnService.isRunning && !StayOnService.starting) {
            ScreenTimeout.restore(this)
        }
        refreshTile(StayOnService.isRunning)
    }

    override fun onStopListening() {
        IdleExit.tileBound = false
        super.onStopListening()
        IdleExit.schedule()
    }

    override fun onTileAdded() {
        refreshTile(StayOnService.isRunning)
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        val enable = !StayOnService.isRunning
        if (enable && !ScreenTimeout.canWrite(this)) {
            openApp()
            refreshTile(false)
            return
        }
        try {
            if (enable) StayOnService.start(this) else StayOnService.stop(this)
            refreshTile(enable)
        } catch (t: Exception) {
            Log.w(TAG, "toggle failed", t)
            refreshTile(StayOnService.isRunning)
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_NEED_PERMISSION, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                3,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.label = getString(R.string.tile_label)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "StayOnTile"

        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StayOnTileService::class.java))
            } catch (t: Exception) {
                Log.w(TAG, "tile update failed", t)
            }
        }
    }
}
