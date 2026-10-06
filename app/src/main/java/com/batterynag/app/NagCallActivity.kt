package com.batterynag.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/**
 * Places the outgoing battery call.
 *
 * The alarm arrives in a BroadcastReceiver, and Android 10 and later refuse
 * to let a background receiver start an activity, so calling straight from
 * the alarm silently does nothing.
 *
 * Instead the alarm posts a full-screen intent notification pointing at this
 * activity. The system launches it over the lock screen, which puts the app
 * in the foreground, and only then is ACTION_CALL allowed to run.
 *
 * The alarm also tries a direct launch as a fast path for devices that still
 * permit it. Both routes land here, so [lastDispatch] makes sure only one of
 * them dials.
 */
class NagCallActivity : Activity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        private const val DISPATCH_GUARD_MS = 3_000L
        private val lastDispatch = AtomicLong(0L)
    }

    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()

        // The direct launch and the full-screen intent can both arrive for a
        // single alarm. Only one of them is allowed to dial.
        val now = System.currentTimeMillis()
        val previous = lastDispatch.get()
        if (now - previous < DISPATCH_GUARD_MS || !lastDispatch.compareAndSet(previous, now)) {
            finish()
            return
        }

        // The prompt has served its purpose once this activity is on screen.
        dismissCallNotification()

        showScreen()
        val placed = placeCall(number)
        if (!placed) {
            statusView?.text = "Unable to place the call.\nCheck the phone permission for Battery Nag."
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (placed) 900L else 1600L)
    }

    private fun showScreen() {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val heading = TextView(this).apply {
            text = "Battery Nag"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = "Battery is critically low, so Battery Nag is calling you."
            textSize = 16f
            setTextColor(0xFFBFBFBF.toInt())
            gravity = Gravity.CENTER
            setPadding(pad, (14 * density).toInt(), pad, 0)
        }
        statusView = status

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(heading)
            addView(status)
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )
    }

    private fun placeCall(number: String): Boolean {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val digits = number.filter { it.isDigit() }
        val normalized = if (number.trimStart().startsWith("+")) "+" + digits else digits
        if (normalized.isEmpty() || normalized == "+") return false

        return try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized")))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dismissCallNotification() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(BatteryNag.CALL_NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}
