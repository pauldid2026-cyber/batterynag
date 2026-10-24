package com.songslike.life
import android.content.*
class BootReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(c.getSharedPreferences("life",0).getBoolean("prox",false)&&c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==0)c.startForegroundService(Intent(c,ProximityService::class.java))}}
