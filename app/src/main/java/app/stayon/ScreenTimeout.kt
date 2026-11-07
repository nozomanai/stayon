package app.stayon

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Extends the system screen-off timer while Stay On is enabled, then writes the
 * previous value back. A screen wake lock is not enough on this phone: the system
 * still locks when [Settings.System.SCREEN_OFF_TIMEOUT] elapses.
 */
internal object ScreenTimeout {
    private const val PREFS = "stayon"
    private const val KEY_SAVED = "saved_screen_off_timeout"
    private const val KEY_HOLDING = "holding_screen_off_timeout"
    private const val TAG = "StayOn"

    /** Seven days. Long enough to stay on, and still inside the settings int range. */
    private const val KEEP_TIMEOUT_MS = 7 * 24 * 60 * 60 * 1000

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    fun apply(context: Context): Boolean {
        if (!canWrite(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val resolver = context.contentResolver
        if (!prefs.getBoolean(KEY_HOLDING, false)) {
            val current = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
            } catch (_: Settings.SettingNotFoundException) {
                60_000
            }
            val saved = if (current == KEEP_TIMEOUT_MS) 120_000 else current
            prefs.edit().putInt(KEY_SAVED, saved).putBoolean(KEY_HOLDING, true).apply()
        }
        return try {
            val wrote = Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                KEEP_TIMEOUT_MS
            )
            val applied = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)
            Log.i(TAG, "screen timeout set to $applied")
            wrote && applied == KEEP_TIMEOUT_MS
        } catch (t: SecurityException) {
            Log.w(TAG, "failed to write screen timeout", t)
            false
        }
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HOLDING, false)) return
        if (!canWrite(context)) {
            Log.w(TAG, "screen timeout still extended; modify-settings permission is missing")
            return
        }
        val saved = prefs.getInt(KEY_SAVED, 120_000).coerceAtLeast(15_000)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                saved
            )
            prefs.edit().putBoolean(KEY_HOLDING, false).apply()
            Log.i(TAG, "screen timeout restored to $saved")
        } catch (t: SecurityException) {
            Log.w(TAG, "failed to restore screen timeout", t)
        }
    }
}
