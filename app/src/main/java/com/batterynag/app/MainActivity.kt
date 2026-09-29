package com.batterynag.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var emailInput: EditText
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val EMAIL_PREFS = "battery_nag_email"
        private const val EMAIL_KEY = "registered_email"
        private const val REGISTER_URL =
            "https://battery-nag-email-service-etk83m.v2.appdeploy.ai/api/register"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                10
            )
        }
        BatteryNag.checkCurrentBattery(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFF000000.toInt())
        }

        root.addView(TextView(this).apply {
            text = "BATTERY NAG"
            textSize = 30f
            setTextColor(-1)
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 20f
            setTextColor(-1)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 20)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Get an email when Battery Nag registers your alert address."
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 15)
        })

        emailInput = EditText(this).apply {
            hint = "Your email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(getSharedPreferences(EMAIL_PREFS, MODE_PRIVATE).getString(EMAIL_KEY, ""))
            setTextColor(-1)
            setHintTextColor(0xFF888888.toInt())
            setSingleLine(true)
        }
        root.addView(emailInput, LinearLayout.LayoutParams(-1, 60).apply {
            setMargins(0, 8, 0, 8)
        })

        root.addView(Button(this).apply {
            text = "REGISTER FOR ALERTS"
            setOnClickListener { registerEmail() }
        }, LinearLayout.LayoutParams(-1, 58).apply {
            setMargins(0, 4, 0, 12)
        })

        root.addView(TextView(this).apply {
            text = "Warns below 30% and gets more frequent as the battery falls."
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "IGNORE WARNINGS FOR:"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 15)
        })

        for (h in 1..5) {
            root.addView(Button(this).apply {
                text = "$h HOUR" + if (h > 1) "S" else ""
                setOnClickListener {
                    BatteryNag.snooze(this@MainActivity, h)
                    updateStatus()
                    Toast.makeText(
                        this@MainActivity,
                        "Warnings snoozed for $h hour(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }, LinearLayout.LayoutParams(-1, 55).apply {
                setMargins(0, 4, 0, 4)
            })
        }

        setContentView(root)
        updateStatus()
    }

    private fun registerEmail() {
        val email = emailInput.text.toString().trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(EMAIL_PREFS, MODE_PRIVATE)
            .edit()
            .putString(EMAIL_KEY, email)
            .apply()

        Toast.makeText(this, "Registering...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(REGISTER_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject().put("email", email).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(
                            this,
                            "You're registered for alerts. Check your email.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Registration email could not be sent. Try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not reach the registration service.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun updateStatus() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100) ?: 100
        val percent = if (level >= 0) level * 100 / scale else -1
        status.text = if (BatteryNag.isSnoozed(this)) {
            "Warnings snoozed"
        } else {
            "Battery: $percent%"
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
