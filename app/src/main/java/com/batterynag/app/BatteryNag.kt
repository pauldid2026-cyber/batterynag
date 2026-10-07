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
    private const val ALERT_URL = "https://open.songslike.com/battery-nag-api/index.php/alert"
    private const val ALERT_PATH = "/battery-nag-api/index.php/alert"
    private const val CLIENT_API_KEY = "batterynag-public-client"
    private const val SNOOZE_UNTIL = "snooze_until"
    private const val LAST_EVAL = "last_eval"
    private const val CHANNEL = "battery_warning"
    private const val REQ = 1001
    private const val CALL_REQ = 1002
    private const val PHONE_PREFS = "battery_nag_phone"
    private const val PHONE_KEY = "call_number"
    private const val CALL_BUCKET = "call_bucket"
    private const val CALL_COUNT = "call_count"
    private const val CALL_RETRY_MS = 45_000L
    private const val CALLS_PER_DECREMENT = 2
    private const val CALL_CHANNEL = "battery_call"
    private const val CALL_FULLSCREEN_REQ = 3001
    const val CALL_NOTIF_ID = 43
    const val TEST_NOTIF_ID = 44
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
        val charging = isCharging(c)
        val snoozed = isSnoozed(c)
        // Leave a trace of the most recent check, so a phone that stayed
        // silent can be told apart from one where this never ran at all.
        recordEval(c, p, charging, snoozed)
        if (charging) {
            resetThresholds(c)
            stop(c)
            return
        }
        if (p > 30) {
            stop(c)
            resetThresholds(c)
            return
        }
        if (snoozed) return
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
     * The track loops, so a nag runs continuously instead of falling
     * silent after one pass. A non-null [nagPlayer] therefore means a run
     * is still going, and that run is left alone - restarting on every nag
     * cycle cut the song off and replayed the opening seconds forever
     * instead of letting it play.
     *
     * Playback starts once and repeats until the phone is plugged in or
     * snoozed, at which point the player is released immediately.
     */
    private fun playNag(c: Context, force: Boolean = false) {
        if (force) {
            // The self test restarts playback so the track is heard from
            // the beginning even if a run is already going.
            stopNag()
        } else {
            if (isSnoozed(c) || isCharging(c)) return
            if (nagPlayer != null) return
        }
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
            mp.isLooping = true
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

    /**
     * Hands the outgoing call to the system.
     *
     * Android 10 and later refuse to start an activity from a background
     * receiver, so the alarm itself cannot dial - that path is blocked
     * silently, with no exception to catch. Instead a full-screen intent
     * notification is posted and the system raises NagCallActivity over the
     * lock screen; with the app in the foreground, ACTION_CALL is allowed.
     *
     * A direct launch is also attempted as a fast path on devices that still
     * permit it. Both routes converge on NagCallActivity, which only lets one
     * of them dial.
     *
     * Returns true once the call has been handed over by either route.
     */
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

        postCallNotification(c, normalized)

        try {
            c.startActivity(
                Intent(c, NagCallActivity::class.java)
                    .putExtra(NagCallActivity.EXTRA_NUMBER, normalized)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {
            // Expected while the app is backgrounded on Android 10+. The
            // full-screen intent posted above is the fallback.
        }
        return true
    }

    /**
     * Raises the call prompt above the lock screen. This is the route that
     * survives a background alarm on Android 10 and later.
     */
    private fun postCallNotification(c: Context, number: String) {
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CALL_CHANNEL, "Battery phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 300, 700)
        }
        manager.createNotificationChannel(channel)

        val launch = PendingIntent.getActivity(
            c,
            CALL_FULLSCREEN_REQ,
            Intent(c, NagCallActivity::class.java)
                .putExtra(NagCallActivity.EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = "Battery is critically low - Battery Nag is calling you."
        manager.notify(
            CALL_NOTIF_ID,
            NotificationCompat.Builder(c, CALL_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Battery Nag - answering now")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(TimeUnit.HOURS.toMillis(1))
                .setContentIntent(launch)
                .setFullScreenIntent(launch, true)
                .build()
        )
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

    /** Battery percentage reported by the system, or -1 if unavailable. */
    private fun batteryPercent(c: Context): Int {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0) level * 100 / scale else -1
    }

    private fun recordEval(c: Context, p: Int, charging: Boolean, snoozed: Boolean) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        c.getSharedPreferences(PREFS, 0).edit()
            .putString(LAST_EVAL, "$time, battery $p%, charging=$charging, snoozed=$snoozed")
            .apply()
    }

    /**
     * Buzzes directly. The warning notification carries its own vibration,
     * so if notifications are blocked the phone would otherwise never buzz
     * at all - this proves the hardware works independently of that.
     */
    private fun vibrateDirect(c: Context, ms: Long) {
        try {
            val v = c.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /** A dismissible test notification; the real warning is not swipeable. */
    private fun postTestNotification(c: Context, p: Int) {
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
        )
        manager.notify(
            TEST_NOTIF_ID,
            NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle("Battery Nag self test")
                .setContentText("Battery $p% - notification and vibration are working.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0, 500, 250, 500))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Sends a real alert email and reports the exact HTTP outcome. */
    private fun sendTestEmail(c: Context): String {
        val email = c.getSharedPreferences(EMAIL_PREFS, 0).getString(EMAIL_KEY, null)?.trim().orEmpty()
        if (email.isEmpty()) return "skipped - no address registered"
        var connection: HttpURLConnection? = null
        return try {
            val percent = batteryPercent(c).coerceIn(0, 100)
            val body = JSONObject().put("email", email).put("threshold", 30).put("percent", percent).toString()
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
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $code ${text.take(110)}"
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName} ${e.message}"
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reports every gate that can silence a real warning, then fires the
     * vibration, track, notification and email anyway so each can be checked
     * at any battery level. Runs on the WebView bridge thread, so the
     * blocking network call is fine here.
     */
    fun runSelfTest(c: Context): String {
        val p = batteryPercent(c)
        val charging = isCharging(c)
        val snoozed = isSnoozed(c)
        val last = c.getSharedPreferences(PREFS, 0).getString(LAST_EVAL, null)
            ?: "NEVER - the warning code has not run since install"
        val email = c.getSharedPreferences(EMAIL_PREFS, 0).getString(EMAIL_KEY, null)?.trim().orEmpty()
        val phone = c.getSharedPreferences(PHONE_PREFS, 0).getString(PHONE_KEY, null)?.trim().orEmpty()

        val blocker = when {
            charging -> "SILENT: the phone is charging"
            p > 30 -> "SILENT: battery is above 30%"
            snoozed -> "SILENT: snoozed"
            else -> "a real warning WOULD fire right now"
        }
        val notif = if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            "BLOCKED - permission not granted"
        } else {
            "allowed"
        }

        vibrateDirect(c, 900)
        playNag(c, force = true)
        postTestNotification(c, p)
        val emailResult = sendTestEmail(c)

        return listOf(
            "battery: $p%   charging: $charging   snoozed: $snoozed",
            "last real check: $last",
            "",
            "real warning: $blocker",
            "notifications: $notif",
            "email: " + if (email.isEmpty()) "NOT REGISTERED" else "registered ($email)",
            "phone: " + if (phone.isEmpty()) "not saved" else "saved",
            "",
            "-> vibration fired (900ms)",
            "-> mp3 playing (looping)",
            "-> notification posted",
            "-> test email: $emailResult"
        ).joinToString("\n")
    }

    fun stop(c: Context) {
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(c, REQ, Intent(c, ToneReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        cancelCallRetry(c)
        stopNag()
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(42)
        // Plugging in or snoozing withdraws any call that has not been
        // answered yet.
        manager.cancel(CALL_NOTIF_ID)
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
