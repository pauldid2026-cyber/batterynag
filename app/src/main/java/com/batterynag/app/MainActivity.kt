package com.batterynag.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val EMAIL_PREFS = "battery_nag_email"
        private const val EMAIL_KEY = "registered_email"
        private const val SONGSLIKE_PLAYLIST_URL = "https://open.songslike.com/battery+nag"
        private const val REGISTER_URL =
            "https://battery-nag-email-service-etk83m.v2.appdeploy.ai/api/register"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            addJavascriptInterface(BatteryNagBridge(), "BatteryNag")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                10
            )
        }
        BatteryNag.checkCurrentBattery(this)
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
                    connection = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                    }

                    val payload = JSONObject().put("email", email).toString()
                    connection.outputStream.use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }

                    val code = connection.responseCode
                    val message = if (code in 200..299) {
                        "You're registered for alerts. Check your email."
                    } else {
                        "Registration email could not be sent. Try again."
                    }

                    runOnUiThread {
                        val escaped = JSONObject.quote(message)
                        webView.evaluateJavascript(
                            "onRegistrationResult(\${code in 200..299},$escaped)",
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
