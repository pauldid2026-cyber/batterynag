package com.batterynag.app
import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit
object BatteryNag{
 private const val PREFS="battery_nag";private const val SNOOZE_UNTIL="snooze_until";private const val CHANNEL="battery_warning";private const val REQ=1001
 fun checkCurrentBattery(c:Context){val b=c.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val l=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1;val s=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100;if(l>=0)evaluate(c,l*100/s)}
 fun evaluate(c:Context,p:Int){if(p>30){stop(c);return};if(isSnoozed(c))return;notify(c,p);schedule(c,when{p<=5->15000L;p<=10->30000L;p<=15->60000L;p<=20->120000L;p<=25->300000L;else->600000L})}
 private fun schedule(c:Context,d:Long){val a=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager;val pi=PendingIntent.getBroadcast(c,REQ,Intent(c,ToneReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);a.cancel(pi);a.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+d,pi)}
 fun tone(c:Context){if(isSnoozed(c))return;ToneGenerator(AudioManager.STREAM_NOTIFICATION,100).apply{startTone(ToneGenerator.TONE_PROP_BEEP,350);Handler(Looper.getMainLooper()).postDelayed({release()},500)}}
 fun snooze(c:Context,h:Int){c.getSharedPreferences(PREFS,0).edit().putLong(SNOOZE_UNTIL,System.currentTimeMillis()+TimeUnit.HOURS.toMillis(h.toLong())).apply();stop(c)}
 fun isSnoozed(c:Context)=System.currentTimeMillis()<c.getSharedPreferences(PREFS,0).getLong(SNOOZE_UNTIL,0)
 fun stop(c:Context){val a=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager;val pi=PendingIntent.getBroadcast(c,REQ,Intent(c,ToneReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);a.cancel(pi)}
 private fun notify(c:Context,p:Int){val n=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(NotificationChannel(CHANNEL,"Battery warnings",NotificationManager.IMPORTANCE_HIGH));n.notify(42,NotificationCompat.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_low_battery).setContentTitle("Battery Nag").setContentText("Battery is at $p%. Plug me in.").setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true).build())}
}
