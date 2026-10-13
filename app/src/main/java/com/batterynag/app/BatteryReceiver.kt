package com.batterynag.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class BatteryReceiver : BroadcastReceiver() {

    private val CHANNEL_ID = "battery_nag_low_alert"
    private val NOTIFICATION_ID = 2001

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        var level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        var scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            // BOOT_COMPLETED / POWER_* intents carry no level — query sticky battery state.
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        }
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale

        // Vibrate + notify at 30% or below
        if (pct <= 30) {
            vibrate(context)
            showLowBatteryNotification(context, pct)
        }
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 500, 500, 500, 500, 500),
                        intArrayOf(0, 255, 0, 255, 0, 255, 0),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (_: Exception) {
            // Vibrator unavailable — nag continues via notification.
        }
    }

    private fun showLowBatteryNotification(context: Context, pct: Int) {
        // Create notification channel (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Nag Low Alert",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Battery Nag - Low Battery")
            .setContentText("Battery at $pct% - Song will play alert!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip silently.
        }
    }
}
