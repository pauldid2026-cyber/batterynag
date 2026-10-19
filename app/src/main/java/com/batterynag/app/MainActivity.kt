package com.batterynag.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var trialBadge: TextView
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TRIAL_PREFS = "battery_trial"
        private const val TRIAL_KEY = "first_launch_timestamp"
        private const val TRIAL_DAYS = 30
        private const val EMAIL_PREFS = "battery_nag_email"
        private const val EMAIL_KEY = "registered_email"
        private const val PHONE_PREFS = "battery_nag_phone"
        private const val PHONE_KEY = "call_number"
        private const val KEY_FSI_WARNED = "fsi_warned"
        private const val SONGSLIKE_PLAYLIST_URL = "https://open.songslike.com/battery+nag"
        private const val PAY_URL = "https://open.songslike.com/batterynag.php"
        private const val REGISTER_URL =
            "https://open.songslike.com/battery-nag-api/index.php/register"
        private const val REGISTER_PATH = "/battery-nag-api/index.php/register"
        private const val CLIENT_API_KEY = "batterynag-public-client"
        private const val SONGSLIKE_SEARCH_URL =
            "https://open.songslike.com/lib/ajax/ajax.php"
        private const val FEATURED_VIDEO_ID = "JWdZEumNRmI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(BatteryNagBridge(), "BatteryNag")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        trialBadge = TextView(this).apply {
            text = trialText()
            setTextColor(0xFF9DFF3C.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 12)
            setBackgroundColor(0xFF07090C.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            addView(
                trialBadge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        setContentView(root)
        val page = assets.open("index.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(
            "https://open.songslike.com/",
            page,
            "text/html",
            "UTF-8",
            null
        )

        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        wanted += Manifest.permission.CALL_PHONE
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        }
        BatteryNag.checkCurrentBattery(this)
        warnIfFullScreenIntentBlocked()
    }

    /** Days left in the 30-day trial; records first launch on first call. */
    private fun trialText(): String {
        val prefs = getSharedPreferences(TRIAL_PREFS, MODE_PRIVATE)
        var first = prefs.getLong(TRIAL_KEY, 0L)
        if (first == 0L) {
            first = System.currentTimeMillis()
            prefs.edit().putLong(TRIAL_KEY, first).apply()
        }
        val elapsed = System.currentTimeMillis() - first
        val left = TRIAL_DAYS - (elapsed / (24 * 60 * 60 * 1000L)).toInt()
        return if (left > 0) "Battery Nag — Free trial: $left days remaining"
        else "Battery Nag — Trial ended — Upgrade to Pro \$4.99"
    }

    /**
     * Android 14 and later can withhold full-screen intents, and without one
     * the low-battery call can only be raised by tapping a notification
     * instead of appearing over the lock screen. Warn once, on launch, so the
     * restriction can be lifted in Settings.
     */
    private fun warnIfFullScreenIntentBlocked() {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.canUseFullScreenIntent()) return

        val prefs = getSharedPreferences(PHONE_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FSI_WARNED, false)) return
        prefs.edit().putBoolean(KEY_FSI_WARNED, true).apply()

        Toast.makeText(
            this,
            "Battery Nag cannot raise calls over the lock screen. Allow full-screen alerts for this app in Settings.",
            Toast.LENGTH_LONG
        ).show()
    }

    inner class BatteryNagBridge {
        @JavascriptInterface
        fun getTrialStatus(): String = trialText()

        @JavascriptInterface
        fun getStatus(): String {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra("level", -1) ?: -1
            val scale = battery?.getIntExtra("scale", 100) ?: 100
            val percent = if (level >= 0) level * 100 / scale else -1
            return if (BatteryNag.isSnoozed(this@MainActivity)) {
                "Warnings snoozed"
            } else {
                "Battery: $percent%"
            }
        }

        @JavascriptInterface
        fun snooze(hours: Int) {
            if (hours !in 1..5) return
            BatteryNag.snooze(this@MainActivity, hours)
        }

        @JavascriptInterface
        fun openPlaylist() {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SONGSLIKE_PLAYLIST_URL)))
        }

        /** Opens the payment/upgrade page. */
        @JavascriptInterface
        fun openPayment() {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAY_URL)))
        }

        /** Plays a specific track by ID, source, and bucket. */
        @JavascriptInterface
        fun playSpecificTrack(id: String, source: String, bucket: Int): String {
            return BatteryNag.playSpecificTrack(this@MainActivity, id, source, bucket)
        }

        /**
         * Blocks while it sends the test email, so the page hands back to the
         * browser first and shows "running" rather than freezing.
         */
        @JavascriptInterface
        fun selfTest(): String = BatteryNag.runSelfTest(this@MainActivity)

        /** Plays the bundled track straight away, with no conditions. */
        @JavascriptInterface
        fun playSong(): String = BatteryNag.playSong(this@MainActivity)

        @JavascriptInterface
        fun stopSong() {
            BatteryNag.stopNag()
        }

        /**
         * Which buckets still need tracks. The answer comes back straight
         * away from whatever the last manifest said, and the refreshed line
         * is pushed to the page afterwards - opening settings never waits
         * on a socket, and the fetch costs one small JSON file rather than
         * any audio.
         */
        @JavascriptInterface
        fun trackStatus(): String {
            val now = runCatching { TrackStore.statusCached(this@MainActivity) }
                .getOrDefault("")
            TrackStore.statusAsync(this@MainActivity) { line ->
                runOnUiThread {
                    val quoted = JSONObject.quote(line)
                    webView.evaluateJavascript(
                        "window.onTrackStatus&&window.onTrackStatus($quoted)", null
                    )
                }
            }
            return now
        }

        /**
         * Which source the notification songs come from: "freeai" or
         * "jukebox". Answered straight from preferences, so the page never
         * waits on a socket to draw the picker.
         */
        @JavascriptInterface
        fun getSongSource(): String = TrackStore.source(this@MainActivity)

        /**
         * Switches the source and tops the new one up straight away rather
         * than waiting for the first warning to discover it empty, then
         * pushes the refreshed stock line back like trackStatus does - the
         * count under the picker has just changed meaning.
         */
        @JavascriptInterface
        fun setSongSource(source: String) {
            TrackStore.setSource(this@MainActivity, source)
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra("level", -1) ?: -1
            val scale = battery?.getIntExtra("scale", 100) ?: 100
            val percent = if (level >= 0) level * 100 / scale else 30
            TrackStore.refresh(this@MainActivity, percent)
            TrackStore.statusAsync(this@MainActivity) { line ->
                runOnUiThread {
                    val quoted = JSONObject.quote(line)
                    webView.evaluateJavascript(
                        "window.onTrackStatus&&window.onTrackStatus($quoted)", null
                    )
                }
            }
        }

        /**
         * Returns the list of currently eligible nag tracks from the chosen source.
         * Intended for the settings/debug screen - returns file, id, bucket and a
         * display string so the page can render a playlist without touching the
         * bundled fallback track.
         */
        @JavascriptInterface
        fun getCurrentTracks(): String {
            val ctx = this@MainActivity
            val tracks = TrackStore.currentTracks(ctx)
            val json = JSONArray()
            for (t in tracks) {
                val obj = JSONObject()
                obj.put("file", t.file?.absolutePath ?: "")
                obj.put("id", t.id)
                obj.put("bucket", t.bucket)
                obj.put("source", t.source)
                obj.put("display", t.display)
                json.put(obj)
            }
            return json.toString()
        }

        /** Triggers a track refresh for the given battery level. */
        @JavascriptInterface
        fun refreshTracks(level: Int) {
            TrackStore.refresh(this@MainActivity, level)
            // The list is about to be redrawn - this is the moment to say how
            // much the jukebox gained this week, rather than waiting for the
            // next battery report to carry it.
            BatteryNag.checkJukeboxWeek(this@MainActivity)
        }

        /**
         * Saves the number the low-battery call should dial, and says so.
         *
         * This used to reject silently, which reads exactly like a dead
         * button: Patterns.PHONE is locale bound and turns away plenty of
         * perfectly dialable international numbers. The count of digits
         * decides instead, and the page shows whichever answer comes back.
         */
        @JavascriptInterface
        fun setCallNumber(number: String): String {
            val cleaned = number.trim()
            val digits = cleaned.count { it.isDigit() }
            if (cleaned.isEmpty() || digits < 7 || digits > 15) return "invalid"
            getSharedPreferences(PHONE_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PHONE_KEY, cleaned)
                .apply()
            return "saved"
        }

        /** The number already on file, so the field opens filled in. */
        @JavascriptInterface
        fun getCallNumber(): String =
            getSharedPreferences(PHONE_PREFS, MODE_PRIVATE).getString(PHONE_KEY, null) ?: ""

        /** The address this phone last registered with. */
        @JavascriptInterface
        fun getRegisteredEmail(): String =
            getSharedPreferences(EMAIL_PREFS, MODE_PRIVATE).getString(EMAIL_KEY, null) ?: ""

        @JavascriptInterface
        fun searchSongslike(query: String) {
            executor.execute {
                var connection: HttpURLConnection? = null
                val videoIds = mutableListOf<String>()
                try {
                    connection = (URL(SONGSLIKE_SEARCH_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        setRequestProperty("Referer", "https://open.songslike.com/")
                    }

                    val payload = listOf(
                        "action" to "aisearch",
                        "query" to query,
                        "foryou" to "false",
                        "type" to "search",
                        "offset" to "0"
                    ).joinToString("&") { (key, value) ->
                        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                    if (connection.responseCode in 200..299) {
                        val results = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                        for (index in 0 until results.length()) {
                            val comments = results.optJSONObject(index)?.optJSONArray("comments")
                            val comment = comments?.optJSONObject(0)
                            val ids = comment?.optJSONArray("youtube_id")
                            val candidate = ids?.optString(0).orEmpty()
                            if (candidate.matches(Regex("[A-Za-z0-9_-]{11}") ) && !videoIds.contains(candidate)) {
                                videoIds.add(candidate)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The JavaScript side keeps the fallback video if search fails.
                } finally {
                    connection?.disconnect()
                }

                runOnUiThread {
                    val selectedIds = listOf(FEATURED_VIDEO_ID) +
                        videoIds.filter { it != FEATURED_VIDEO_ID }.shuffled().take(29)
                    webView.evaluateJavascript(
                        "onSongslikeVideoResults(${JSONArray(selectedIds).toString()})",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun registerEmail(email: String) {
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "onRegistrationResult(false,'Enter a valid email address.')",
                        null
                    )
                }
                return
            }

            getSharedPreferences(EMAIL_PREFS, MODE_PRIVATE)
                .edit()
                .putString(EMAIL_KEY, email)
                .apply()

            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    val payload = JSONObject().put("email", email).toString()
                    val signed = RequestSigner.headers(BuildConfig.SIGNING_SECRET, "POST", REGISTER_PATH, payload)

                    connection = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("X-BatteryNag-Key", CLIENT_API_KEY)
                        signed.forEach { (name, value) -> setRequestProperty(name, value) }
                    }

                    connection.outputStream.use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }

                    val code = connection.responseCode
                    val success = code in 200..299
                    val message = if (success) {
                        "You're registered for alerts. Check your email."
                    } else {
                        // The server's own reason is the useful one here -
                        // "missing signature" and "bad address" need
                        // different fixes, and a flat failure hides both.
                        val detail = runCatching {
                            val stream = connection.errorStream ?: connection.inputStream
                            stream?.bufferedReader()?.use { it.readText() } ?: ""
                        }.getOrDefault("")
                        val reason = runCatching {
                            JSONObject(detail).optString("error", "")
                        }.getOrDefault("")
                        if (reason.isNotBlank()) reason
                        else "Registration email could not be sent (HTTP $code). Try again."
                    }

                    runOnUiThread {
                        val escaped = JSONObject.quote(message)
                        webView.evaluateJavascript(
                            "onRegistrationResult($success,$escaped)",
                            null
                        )
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "onRegistrationResult(false,'Could not reach the registration service.')",
                            null
                        )
                    }
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("BatteryNag")
        webView.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
