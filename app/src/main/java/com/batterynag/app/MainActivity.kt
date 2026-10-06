package com.batterynag.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val EMAIL_PREFS = "battery_nag_email"
        private const val EMAIL_KEY = "registered_email"
        private const val PHONE_PREFS = "battery_nag_phone"
        private const val PHONE_KEY = "call_number"
        private const val KEY_FSI_WARNED = "fsi_warned"
        private const val SONGSLIKE_PLAYLIST_URL = "https://open.songslike.com/battery+nag"
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
        setContentView(webView)
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

        @JavascriptInterface
        fun setCallNumber(number: String) {
            val cleaned = number.trim()
            if (!android.util.Patterns.PHONE.matcher(cleaned).matches()) return
            getSharedPreferences(PHONE_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PHONE_KEY, cleaned)
                .apply()
        }

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
                        "Registration email could not be sent. Try again."
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
