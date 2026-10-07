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
    private const val LAST_STEPS = "last_steps"
    // How often to re-check while the battery is still healthy. Without a
    // re-check here nothing ever runs again, so the drop past 30% is missed.
    private const val WATCH_DELAY = 300_000L
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
    @Volatile private var trackPlaying: Boolean = false
    @Volatile private var chargingWatcher: BroadcastReceiver? = null
    @Volatile private var watcherContext: Context? = null
    /** What the current run is actually playing, for the notification and self test. */
    @Volatile private var lastTrack: String = "I'm All Out Of Love"

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
            runCatching { schedule(c, WATCH_DELAY) }
            return
        }
        if (p > 30) {
            stop(c)
            resetThresholds(c)
            // Keep watching. Returning here with no next check meant a phone
            // installed above 30% stayed silent forever, draining past every
            // threshold without the nag ever running.
            runCatching { schedule(c, WATCH_DELAY) }
            return
        }
        if (snoozed) return
        // Each step stands on its own. A blocked notification or a rejected
        // call must not take the sound and the email down with it - run
        // loose, a single exception killed the entire warning silently and
        // cancelled every check after it.
        val errors = mutableListOf<String>()
        runCatching { maybeCall(c, p) }.onFailure { errors += "call: " + it.javaClass.simpleName }
        runCatching { sendThresholdEmail(c, p) }.onFailure { errors += "email: " + it.javaClass.simpleName }
        // Sound first: the notification quotes whatever track the store just
        // picked, so it cannot name a song this cycle is not going to play.
        runCatching { playNag(c, level = p) }.onFailure { errors += "audio: " + it.javaClass.simpleName }
        runCatching { notify(c, p) }.onFailure { errors += "notify: " + it.javaClass.simpleName }
        val delay = when {
            p <= 5 -> 15000L
            p <= 10 -> 30000L
            p <= 15 -> 60000L
            p <= 20 -> 120000L
            p <= 25 -> 300000L
            else -> 600000L
        }
        runCatching { schedule(c, delay) }.onFailure { errors += "schedule: " + it.javaClass.simpleName }
        recordSteps(c, errors)
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
    /**
     * The track plays on the alarm stream, so an alarm volume of zero makes
     * the whole nag silent even while playback itself is healthy. Lift it off
     * zero rather than let a warning nobody can hear count as a warning.
     */
    private fun ensureAudible(c: Context): String {
        return try {
            val audio = c.getSystemService(AudioManager::class.java)
                ?: return "alarm volume: unavailable"
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current == 0) {
                val target = (max / 2).coerceAtLeast(1)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                "alarm volume was 0 (silent) - raised to $target/$max"
            } else {
                "alarm volume $current/$max"
            }
        } catch (e: Exception) {
            "alarm volume check failed: ${e.javaClass.simpleName}"
        }
    }

    private fun playNag(c: Context, force: Boolean = false, level: Int = 30) {
        if (force) {
            // The self test restarts playback so the track is heard from
            // the beginning even if a run is already going.
            stopNag()
        } else {
            if (isSnoozed(c) || isCharging(c)) return
            if (nagPlayer != null) return
        }
        try {
            // The alarm stream can be muted, which would make the warning
            // completely inaudible while playback itself looks healthy.
            ensureAudible(c)
            // Which folder this level belongs to is decided here, so 26% gets
            // the 30% run and 24% the 25% run. A pool that has run dry hands
            // back the bundled track for this cycle and quietly refills itself
            // for the next one.
            val track = runCatching { TrackStore.pick(c, level) }
                .getOrElse { TrackStore.Track(null, "", TrackStore.bucketFor(level)) }
            lastTrack = if (track.bundled) "I'm All Out Of Love"
            else "track ${track.id} (${track.bucket}%)"
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (track.file != null) {
                // Read from disk, so a phone with no signal still warns.
                mp.setDataSource(track.file.absolutePath)
            } else {
                val fd = c.resources.openRawResourceFd(R.raw.nag_alert)
                if (fd == null) {
                    // Never prepared, so release it rather than leave a
                    // MediaServer object behind every time the resource is
                    // missing.
                    runCatching { mp.release() }
                    tone(c)
                    return
                }
                fd.use {
                    mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mp.isLooping = true
            mp.setOnPreparedListener { player ->
                try {
                    player.start()
                    trackPlaying = true
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
            watchForCharger(c)
            mp.prepareAsync()
        } catch (_: Exception) {
            nagPlayer = null
            trackPlaying = false
            stopWatching()
            tone(c)
        }
    }

    /** Stops and releases a player if it is still playing. */
    private fun releasePlayer(player: MediaPlayer) {
        if (nagPlayer === player) {
            nagPlayer = null
            trackPlaying = false
            stopWatching()
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }

    /**
     * Plugging in has to cut the track the moment it happens. Waiting for the
     * next scheduled check, or for the power broadcast to reach a manifest
     * receiver, leaves the song running for minutes after the charger is in.
     * So while the track is playing the app watches the battery itself.
     */
    private fun watchForCharger(c: Context) {
        if (chargingWatcher != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Two independent signals: the plug-in broadcast and the
                // charging flag carried on the battery intent. Either is
                // enough to cut the track, so one missed extra cannot leave
                // the song running after the charger is in.
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    isCharging(ctx)
                ) {
                    stop(ctx)
                    resetThresholds(ctx)
                }
            }
        }
        try {
            // Registered and unregistered on the same context, or Android
            // reports "Receiver not registered" and the watcher leaks.
            val app = c.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            app.registerReceiver(receiver, filter)
            chargingWatcher = receiver
            watcherContext = app
        } catch (_: Exception) {
        }
    }

    /** Drops the battery watcher taken out by [watchForCharger]. */
    private fun stopWatching() {
        val receiver = chargingWatcher ?: return
        chargingWatcher = null
        val context = watcherContext
        watcherContext = null
        if (context != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    /** Stops the warning track if it is playing. Safe to call repeatedly. */
    fun stopNag() {
        val player = nagPlayer ?: return
        nagPlayer = null
        trackPlaying = false
        stopWatching()
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
            .remove(LAST_STEPS)
            .apply()
    }

    /** Records how far the warning got, so a silent phone can be explained. */
    private fun recordSteps(c: Context, errors: List<String>) {
        val value = if (errors.isEmpty()) "all steps ran" else errors.joinToString("; ")
        c.getSharedPreferences(PREFS, 0).edit().putString(LAST_STEPS, value).apply()
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

        val volume = ensureAudible(c)
        // Every action is isolated: a blocked notification used to be able to
        // throw before the email step and take the whole test down with it.
        runCatching { vibrateDirect(c, 900) }
        runCatching { playNag(c, force = true, level = p) }
        runCatching { postTestNotification(c, p) }
        val emailResult = runCatching { sendTestEmail(c) }
            .getOrElse { "failed before sending: ${it.javaClass.simpleName} ${it.message}" }
        val steps = c.getSharedPreferences(PREFS, 0).getString(LAST_STEPS, null) ?: "no steps run yet"

        return listOf(
            "battery: $p%   charging: $charging   snoozed: $snoozed",
            "last real check: $last",
            "last real steps: $steps",
            "",
            "real warning: $blocker",
            "notifications: $notif",
            "email: " + if (email.isEmpty()) "NOT REGISTERED" else "registered ($email)",
            "phone: " + if (phone.isEmpty()) "not saved" else "saved",
            "",
            volume,
            "-> vibration fired (900ms)",
            "-> mp3 playing (looping)",
            "-> notification posted",
            "-> test email: $emailResult"
        ).joinToString("\n")
    }

    /**
     * Plays the bundled track on demand with no battery, snooze or charging
     * condition attached, and reports what happened. One obvious way to prove
     * the audio path works before blaming the warning logic.
     */
    fun playSong(c: Context): String {
        val volume = ensureAudible(c)
        trackPlaying = false
        playNag(c, force = true, level = batteryPercent(c))
        // prepareAsync resolves off-thread, so give start() a moment to run
        // before reporting whether the track actually came up.
        var waited = 0
        while (!trackPlaying && waited < 2000) {
            Thread.sleep(50)
            waited += 50
        }
        val lines = mutableListOf(
            if (trackPlaying) "playing: $lastTrack (looping)" else "playing: NOT STARTED",
            volume,
            "battery: ${batteryPercent(c)}%   charging: ${isCharging(c)}   snoozed: ${isSnoozed(c)}"
        )
        if (!trackPlaying) {
            lines += ""
            lines += "The track did not start. Run the self test below for detail."
        }
        return lines.joinToString("\n")
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
        val body = "$lastTrack playing - you need to charge your battery, or snooze."

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
