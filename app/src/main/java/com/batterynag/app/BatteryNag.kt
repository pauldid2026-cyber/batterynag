package com.batterynag.app

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
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
    private const val ALERT_URL = "https://open.songslike.com/battery-nag-api/index.php/alert"
    private const val ALERT_PATH = "/battery-nag-api/index.php/alert"
    private const val CLIENT_API_KEY = "batterynag-public-client"
    private const val SNOOZE_UNTIL = "snooze_until"
    private const val CHANNEL = "battery_warning"
    private const val REQ = 1001
    private const val CALL_REQ = 1002
    private const val PHONE_PREFS = "battery_nag_phone"
    private const val PHONE_KEY = "call_number"
    private const val CALL_BUCKET = "call_bucket"
    private const val CALL_COUNT = "call_count"
    private const val CALL_RETRY_MS = 45_000L
    private const val CALLS_PER_DECREMENT = 2
    private val thresholds = listOf(30, 20, 15, 10, 5)
    private val emailExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var nagPlayer: MediaPlayer? = null

    fun checkCurrentBattery(c: Context) {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0) evaluate(c, level * 100 / scale)
    }

    fun evaluate(c: Context, p: Int) {
        if (isCharging(c)) {
            resetThresholds(c)
            stop(c)
            return
        }
        if (p > 30) {
            stop(c)
            resetThresholds(c)
            return
        }
        if (isSnoozed(c)) return
        maybeCall(c, p)
        sendThresholdEmail(c, p)
        notify(c, p)
        playNag(c)
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
                val body = JSONObject().put("email", email).put("threshold", threshold).put("percent", p).toString()
                val signed = RequestSigner.headers(BuildConfig.SIGNING_SECRET, "POST", ALERT_PATH, body)

                connection = (URL(ALERT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-BatteryNag-Key", CLIENT_API_KEY)
                    signed.forEach { (name, value) -> setRequestProperty(name, value) }
                }
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

    /**
     * Plays the bundled warning track. Falls back to the beep if the
     * track is missing or cannot be decoded.
     *
     * Playback stops as soon as the phone is plugged in or snoozed.
     */
    private fun playNag(c: Context) {
        if (isSnoozed(c) || isCharging(c)) return
        stopNag()
        try {
            val fd = c.resources.openRawResourceFd(R.raw.nag_alert)
            if (fd == null) {
                tone(c)
                return
            }
            val mp = MediaPlayer()
            fd.use {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mp.isLooping = false
            mp.setOnPreparedListener { player ->
                try {
                    player.start()
                } catch (_: Exception) {
                }
            }
            mp.setOnCompletionListener { player -> releasePlayer(player) }
            mp.setOnErrorListener { player, _, _ ->
                releasePlayer(player)
                tone(c)
                true
            }
            nagPlayer = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            nagPlayer = null
            tone(c)
        }
    }

    /** Stops and releases a player if it is still playing. */
    private fun releasePlayer(player: MediaPlayer) {
        if (nagPlayer === player) nagPlayer = null
        try {
            player.release()
        } catch (_: Exception) {
        }
    }

    /** Stops the warning track if it is playing. Safe to call repeatedly. */
    fun stopNag() {
        val player = nagPlayer ?: return
        nagPlayer = null
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }

    fun tone(c: Context) {
        if (isSnoozed(c) || isCharging(c)) return
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            Handler(Looper.getMainLooper()).postDelayed({ release() }, 500)
        }
    }

    fun snooze(c: Context, h: Int) {
        c.getSharedPreferences(PREFS, 0).edit().putLong(SNOOZE_UNTIL, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(h.toLong())).apply()
        stop(c)
        schedule(c, TimeUnit.HOURS.toMillis(h.toLong()))
    }

    fun isSnoozed(c: Context) = System.currentTimeMillis() < c.getSharedPreferences(PREFS, 0).getLong(SNOOZE_UNTIL, 0)

    fun isCharging(c: Context): Boolean {
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun resetThresholds(c: Context) {
        c.getSharedPreferences(PREFS, 0).edit()
            .remove("sent_thresholds")
            .remove(CALL_BUCKET)
            .remove(CALL_COUNT)
            .apply()
        cancelCallRetry(c)
    }

    /**
     * Places two calls for every new 5% battery decrement.
     *
     * A decrement is a drop into a lower 5% bucket (30, 25, 20, 15, 10, 5).
     * The first call is made immediately; the second is scheduled a short
     * time later by the retry alarm so the two calls are not back to back.
     */
    private fun maybeCall(c: Context, p: Int) {
        val prefs = c.getSharedPreferences(PREFS, 0)
        val number = c.getSharedPreferences(PHONE_PREFS, 0)
            .getString(PHONE_KEY, null)?.trim().orEmpty()
        if (number.isEmpty()) return

        val bucket = if (p >= 5) (p / 5) * 5 else 0
        val stored = prefs.getInt(CALL_BUCKET, -1)
        var count = prefs.getInt(CALL_COUNT, 0)

        // Entering a lower bucket starts a fresh pair of calls.
        if (stored == -1 || bucket < stored) {
            count = 0
            prefs.edit().putInt(CALL_BUCKET, bucket).putInt(CALL_COUNT, 0).apply()
        }

        if (count >= CALLS_PER_DECREMENT) return
        if (!placeCall(c, number)) return

        count++
        prefs.edit().putInt(CALL_COUNT, count).apply()
        if (count < CALLS_PER_DECREMENT) scheduleCallRetry(c)
    }

    private fun placeCall(c: Context, number: String): Boolean {
        if (Build.VERSION.SDK_INT >= 23 &&
            c.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val digits = number.filter { it.isDigit() }
        val normalized = if (number.trimStart().startsWith("+")) "+" + digits else digits
        if (normalized.isEmpty() || normalized == "+") return false
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleCallRetry(c: Context) {
        try {
            val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = callPendingIntent(c)
            alarm.cancel(pending)
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CALL_RETRY_MS,
                pending
            )
        } catch (_: Exception) {
        }
    }

    private fun cancelCallRetry(c: Context) {
        try {
            val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(callPendingIntent(c))
        } catch (_: Exception) {
        }
    }

    private fun callPendingIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            CALL_REQ,
            Intent(c, ToneReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun stop(c: Context) {
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(c, REQ, Intent(c, ToneReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        cancelCallRetry(c)
        stopNag()
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(42)
    }

    private fun notify(c: Context, p: Int) {
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val vibration = longArrayOf(0, 500, 250, 500)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Battery warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = vibration
            }
            manager.createNotificationChannel(channel)
        }

        val title = "Battery Nag - battery at $p%"
        val body = "All out of love playing - you need to charge your battery, or snooze."

        // Tapping the body opens the app, where all snooze options live.
        val openApp = PendingIntent.getActivity(
            c,
            0,
            Intent(c, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager.notify(42, NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(vibration)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "SNOOZE 1 HOUR", snoozeAction(c, 1, 2001))
            .addAction(0, "SNOOZE 2 HOURS", snoozeAction(c, 2, 2002))
            .build())
    }

    private fun snoozeAction(c: Context, hours: Int, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c,
            requestCode,
            Intent(c, SnoozeReceiver::class.java).putExtra(SnoozeReceiver.EXTRA_HOURS, hours),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
