package com.songslike.life
import android.app.*
import android.content.*
import java.text.SimpleDateFormat
import java.util.*
object ScheduleStore{
 fun add(c:Context,keys:String,repeat:String){if(keys.isBlank())return;val p=c.getSharedPreferences("life",0);val set=p.getStringSet("schedules",emptySet())!!.toMutableSet();val label=repeat+" • "+keys+" • "+SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date());set.add(label);p.edit().putStringSet("schedules",set).apply();val am=c.getSystemService(AlarmManager::class.java);val id=System.currentTimeMillis();val pi=PendingIntent.getBroadcast(c,id,Intent(c,ScheduleReceiver::class.java).putExtra("keys",keys),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);val at=System.currentTimeMillis()+60000;when(repeat){"Daily"->am.setInexactRepeating(AlarmManager.RTC_WAKEUP,at,AlarmManager.INTERVAL_DAY,pi);"Weekly"->am.setInexactRepeating(AlarmManager.RTC_WAKEUP,at,AlarmManager.INTERVAL_DAY*7,pi);"Monthly"->am.setInexactRepeating(AlarmManager.RTC_WAKEUP,at,AlarmManager.INTERVAL_DAY*30,pi);"Yearly"->am.setInexactRepeating(AlarmManager.RTC_WAKEUP,at,AlarmManager.INTERVAL_DAY*365,pi);else->am.set(AlarmManager.RTC_WAKEUP,at,pi)}}
}
