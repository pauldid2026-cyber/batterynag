package com.batterynag.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ActivityCompat
import android.media.RingtoneManager

class MainActivity : Activity() {

    companion object {
        private const val TRIAL_DAYS = 30
        private const val PERM_REQUEST = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set native app layout
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        // Initialize trial tracking
        initTrialTracking()

        // Show track listing from jukebox
        showTrackListing()

        // Check battery and notify if low (no persistent receiver = no leak)
        checkBatteryAndNotify()

        // Setup vibration
        vibrateOnLaunch()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERM_REQUEST
            )
        }
    }

    private fun initTrialTracking() {
        val prefs = getSharedPreferences("battery_trial", MODE_PRIVATE)
        val firstLaunch = prefs.getLong("first_launch_timestamp", 0L)
        val currentTime = System.currentTimeMillis()

        // Check if this is the first launch ever
        val isFirstLaunch = firstLaunch == 0L

        if (isFirstLaunch) {
            // Record first launch timestamp
            prefs.edit().putLong("first_launch_timestamp", currentTime).apply()
            // Show 30-day trial
            showTrialBadge(currentTime)
        } else {
            // Check if still in trial period
            val trialEnd = firstLaunch + (TRIAL_DAYS * 24 * 60 * 60 * 1000L)
            if (currentTime < trialEnd) {
                // Still in trial - show trial badge
                showTrialBadge(firstLaunch)
            }
            // Trial expired or paid - ensure pay options are visible
        }
    }

    private fun showTrialBadge(firstLaunch: Long) {
        // Trial is active - 30 days from first launch
        @Suppress("UNUSED_VARIABLE")
        val badgeText = "30 days remaining"
        // In a native app, we could show this in the UI
        // For now, just log it
    }

    private fun showTrackListing() {
        // Load tracks from jukebox.json or default song
        // The jukebox has "Charge Your Phone" — displayed in activity_main.xml
    }

    private fun checkBatteryAndNotify() {
        // Query the sticky battery intent once instead of registering a leaking receiver.
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0) return
        val pct = level * 100 / scale
        if (pct <= 30) {
            // Delegate to the receiver helper so manifest + foreground paths stay in sync.
            BatteryReceiver().onReceive(this, sticky)
        }
    }

    private fun vibrateOnLaunch() {
        // Short vibration on app launch to confirm it's working
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (_: Exception) {
            // Ignore — vibration is best-effort.
        }
    }

    /**
     * Plays the bundled alert when battery is low.
     */
    fun playLowBatterySong() {
        try {
            val ringtone = RingtoneManager.getRingtone(
                this,
                Uri.parse("android.resource://" + packageName + "/" + R.raw.nag_alert)
            )
            ringtone?.play()
        } catch (_: Exception) {
            // Audio is best-effort; the notification + vibration already fired.
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onUpgradeClicked(view: android.view.View) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.songslike.com/battery+nag")))
    }
}
