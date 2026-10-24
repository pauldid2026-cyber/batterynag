package com.songslike.life
import android.content.*
import android.net.Uri
class ScheduleReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val k=i.getStringExtra("keys")?:return;val n=c.getSystemService(android.app.NotificationManager::class.java);if(android.os.Build.VERSION.SDK_INT>=26)n.createNotificationChannel(android.app.NotificationChannel("schedule","SongsLike",android.app.NotificationManager.IMPORTANCE_HIGH));n.notify(k.hashCode(),android.app.Notification.Builder(c,"schedule").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("SongsLike playlist").setContentText(k).setAutoCancel(true).build());c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://open.songslike.com/"+k)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
