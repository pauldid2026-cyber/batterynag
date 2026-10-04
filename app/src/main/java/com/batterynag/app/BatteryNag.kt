package com.batterynag.app

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BatteryNag {
    private const val PREFS = "battery_nag"
    private const val EMAIL_PREFS = "battery_nag_email"
    private const val EMAIL_KEY = "registered_email"
    private const val ALERT_URL = "https://battery-nag-email-service-etk83m.v2.appdeploy.ai/api/alert"
    private const val SNOOZE_UNTIL = "snooze_until"
    private const val CHANNEL = "battery_warning"
    private const val REQ = 1001
    private val thresholds = listOf(30, 20, 15, 10, 5)
    private val emailExecutor = Executors.newSingleThreadExecutor()

    fun checkCurrentBattery(c: Context) {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0) evaluate(c, level * 100 / scale)
    }

    fun evaluate(c: Context, p: Int) {
        if (p > 30) {
            stop(c)
            c.getSharedPreferences(PREFS, 0).edit().remove("sent_thresholds").apply()
            return
        }
        if (isSnoozed(c)) return
        sendThresholdEmail(c, p)
        notify(c, p)
        val delay = when {
            p <= 5 -> 15000L
            p <= 10 -> 30000L
            p <= 15 -> 60000L
            p <= 20 -> 120000L
            p <= 25 -> 300000L
            else -> 600000L
        }
        schedule(c, delay)
    }

    private fun sendThresholdEmail(c: Context, p: Int) {
        val prefs = c.getSharedPreferences(PREFS, 0)
        val sent = prefs.getStringSet("sent_thresholds", emptySet())?.toSet() ?: emptySet()
        val threshold = thresholds.filter { p <= it && !sent.contains(it.toString()) }.minOrNull() ?: return
        val email = c.getSharedPreferences(EMAIL_PREFS, 0).getString(EMAIL_KEY, null)?.trim().orEmpty()
        if (email.isBlank()) return
        emailExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ALERT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                val body = JSONObject().put("email", email).put("threshold", threshold).put("percent", p).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode in 200..299) {
                    val updated = prefs.getStringSet("sent_thresholds", emptySet())?.toMutableSet() ?: mutableSetOf()
                    thresholds.filter { it >= threshold }.forEach { updated.add(it.toString()) }
                    prefs.edit().putStringSet("sent_thresholds", updated).apply()
                }
            } catch (_: Exception) {
                // Keep the threshold unmarked so a later check can retry.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun schedule(c: Context, delay: Long) {
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(c, REQ, Intent(c, ToneReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending)
    }

    fun tone(c: Context) {
        if (isSnoozed(c)) return
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            Handler(Looper.getMainLooper()).postDelayed({ release() }, 500)
        }
    }

    fun snooze(c: Context, h: Int) {
        c.getSharedPreferences(PREFS, 0).edit().putLong(SNOOZE_UNTIL, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(h.toLong())).apply()
        stop(c)
    }

    fun isSnoozed(c: Context) = System.currentTimeMillis() < c.getSharedPreferences(PREFS, 0).getLong(SNOOZE_UNTIL, 0)

    fun stop(c: Context) {
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(c, REQ, Intent(c, ToneReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
    }

    private fun notify(c: Context, p: Int) {
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Battery warnings", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(42, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_low_battery).setContentTitle("Battery Nag").setContentText("Battery is at " + p + "%. Plug me in.").setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true).build())
    }
}