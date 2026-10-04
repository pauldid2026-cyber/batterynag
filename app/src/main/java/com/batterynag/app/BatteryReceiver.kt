package com.batterynag.app
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class BatteryReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  when(intent.action){Intent.ACTION_POWER_CONNECTED->BatteryNag.stop(context);Intent.ACTION_POWER_DISCONNECTED,Intent.ACTION_BOOT_COMPLETED->BatteryNag.checkCurrentBattery(context)}
 }
}
