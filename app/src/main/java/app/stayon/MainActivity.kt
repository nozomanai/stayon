package app.stayon

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import app.stayon.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var updating = false
    private var awaitingWrite = false
    private var leftForSettings = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notif_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        binding.addTileButton.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        binding.addTileButton.setOnClickListener { addTile() }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.permissionButton.setOnClickListener { openWriteSettings() }
        binding.keepSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked) turnOn() else StayOnService.stop(this)
        }
        binding.versionText.text = getString(R.string.version_label, appVersion())
        maybeConsumePermissionRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeConsumePermissionRequest(intent)
    }

    override fun onPause() {
        if (awaitingWrite) leftForSettings = true
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        IdleExit.activityVisible = true
        IdleExit.cancel()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(StayOnService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        if (awaitingWrite && leftForSettings) {
            awaitingWrite = false
            leftForSettings = false
            if (ScreenTimeout.canWrite(this)) {
                turnOn()
            } else {
                Toast.makeText(this, R.string.permission_missing, Toast.LENGTH_LONG).show()
            }
        }
        if (!StayOnService.isRunning && !StayOnService.starting) {
            ScreenTimeout.restore(this)
        }
        IdleExit.defer = false
        render()
        renderPermission()
        renderBattery()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!StayOnService.isRunning && !StayOnService.starting && !awaitingWrite && !isChangingConfigurations) {
            finishAndRemoveTask()
        }
    }

    override fun onStop() {
        IdleExit.activityVisible = false
        unregisterReceiver(stateReceiver)
        super.onStop()
        IdleExit.schedule()
    }

    private fun render() {
        val on = StayOnService.isRunning
        updating = true
        binding.keepSwitch.isChecked = on
        updating = false
        binding.statusText.setText(if (on) R.string.status_on else R.string.status_off)
        binding.statusDetail.setText(if (on) R.string.status_detail_on else R.string.status_detail_off)
        val orb = ContextCompat.getColor(this, if (on) R.color.accent else R.color.orb_off)
        val icon = ContextCompat.getColor(this, if (on) R.color.on_accent else R.color.icon_off)
        ViewCompat.setBackgroundTintList(binding.statusOrb, ColorStateList.valueOf(orb))
        ImageViewCompat.setImageTintList(binding.statusIcon, ColorStateList.valueOf(icon))
    }

    private fun turnOn() {
        if (!ScreenTimeout.canWrite(this)) {
            openWriteSettings()
            return
        }
        try {
            StayOnService.start(this)
            maybeAskNotificationPermission()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_text, Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun maybeConsumePermissionRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_NEED_PERMISSION, false) != true) return
        intent.removeExtra(EXTRA_NEED_PERMISSION)
        if (!ScreenTimeout.canWrite(this)) {
            openWriteSettings()
        } else if (!StayOnService.isRunning) {
            turnOn()
        }
    }

    private fun openWriteSettings() {
        awaitingWrite = true
        leftForSettings = false
        IdleExit.defer = true
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        awaitingWrite = false
        IdleExit.defer = false
        Toast.makeText(this, R.string.permission_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun renderPermission() {
        val allowed = ScreenTimeout.canWrite(this)
        binding.permissionButton.isEnabled = !allowed
        binding.permissionButton.setText(
            if (allowed) R.string.permission_done else R.string.permission_button
        )
    }

    private fun renderBattery() {
        val ignoring = isIgnoringBattery()
        binding.batteryButton.isEnabled = !ignoring
        binding.batteryButton.setText(if (ignoring) R.string.battery_done else R.string.battery_button)
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIF, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(StatusBarManager::class.java) ?: return
        try {
            manager.requestAddTileService(
                ComponentName(this, StayOnTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_exists
                    else -> R.string.tile_not_added
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.tile_not_added, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) return
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(request)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.battery_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isIgnoringBattery(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun appVersion(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }

    companion object {
        const val EXTRA_NEED_PERMISSION = "app.stayon.extra.NEED_PERMISSION"
        private const val PREFS = "stayon"
        private const val KEY_ASKED_NOTIF = "asked_notif"
    }
}
