package app.stayon

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Keeps the screen on by extending the system screen-off timeout, and shows an
 * ongoing notification so the user can turn that off again.
 *
 * A screen wake lock is also held, but on some phones (including this Xiaomi)
 * the system ignores it and still locks when the screen timeout elapses.
 */
class StayOnService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val reapplyTimeout: Runnable = Runnable {
        if (!isRunning) return@Runnable
        if (ScreenTimeout.apply(this)) {
            handler.postDelayed(reapplyTimeout, REAPPLY_MS)
        } else {
            Log.w(TAG, "screen timeout was lost")
            showError()
            shutdown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (stopRequested) {
                if (!tryEnterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                shutdown()
                return START_NOT_STICKY
            }
            if (!tryEnterForeground()) {
                showError()
                stopSelf()
                return START_NOT_STICKY
            }
            acquireWakeLock()
            if (!ScreenTimeout.apply(this)) {
                showError()
                shutdown()
                return START_NOT_STICKY
            }
            isRunning = true
            handler.removeCallbacks(reapplyTimeout)
            handler.postDelayed(reapplyTimeout, REAPPLY_MS)
            Log.i(TAG, "screen stay on")
            pushState()
            return START_STICKY
        } finally {
            starting = false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(reapplyTimeout)
        isRunning = false
        starting = false
        ScreenTimeout.restore(this)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        pushState()
        Log.i(TAG, "screen stay off")
        super.onDestroy()
        IdleExit.schedule()
    }

    private fun tryEnterForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Exception) {
            Log.e(TAG, "startForeground failed", t)
            false
        }
    }

    private fun shutdown() {
        handler.removeCallbacks(reapplyTimeout)
        isRunning = false
        ScreenTimeout.restore(this)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        pushState()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val level = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        if (!power.isWakeLockLevelSupported(level)) return
        try {
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(level or PowerManager.ON_AFTER_RELEASE, "app.stayon:screen")
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } catch (t: RuntimeException) {
            Log.w(TAG, "failed to acquire screen wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (t: RuntimeException) {
            Log.w(TAG, "failed to release screen wake lock", t)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_big)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPendingIntent())
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.notif_action_stop),
                    stopPendingIntent()
                ).build()
            )
            .build()
    }

    private fun showError() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.error_title))
            .setContentText(getString(R.string.error_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.error_text)))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, StayOnReceiver::class.java).setAction(ACTION_STOP)
        return PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pushState() {
        StayOnTileService.requestUpdate(this)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STOP = "app.stayon.action.STOP"
        const val ACTION_STATE_CHANGED = "app.stayon.action.STATE_CHANGED"

        private const val CHANNEL_ID = "stay_on_v1"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val REAPPLY_MS = 10_000L
        private const val TAG = "StayOn"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var starting: Boolean = false
            private set

        @Volatile
        private var stopRequested = false

        fun start(context: Context) {
            starting = true
            stopRequested = false
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StayOnService::class.java)
                )
            } catch (t: Exception) {
                starting = false
                throw t
            }
        }

        fun stop(context: Context) {
            starting = false
            stopRequested = true
            context.stopService(Intent(context, StayOnService::class.java))
        }
    }
}
