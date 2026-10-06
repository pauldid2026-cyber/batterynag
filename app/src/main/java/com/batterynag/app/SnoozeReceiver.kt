package com.batterynag.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the Snooze actions on the low-battery notification.
 *
 * BatteryNag.snooze() clears the warning alarm, cancels any pending
 * second call, stops the warning track and removes the notification.
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val hours = intent?.getIntExtra(EXTRA_HOURS, 1) ?: 1
        BatteryNag.snooze(context, if (hours in 1..5) hours else 1)
    }

    companion object {
        const val EXTRA_HOURS = "hours"
    }
}
