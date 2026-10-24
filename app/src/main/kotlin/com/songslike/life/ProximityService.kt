package com.songslike.life
import android.Manifest
import android.app.*
import android.content.*
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
class ProximityService:Service(){
 private val h=android.os.Handler(android.os.Looper.getMainLooper());private lateinit var lm:LocationManager
 private val r=object:Runnable{override fun run(){checkPlace();h.postDelayed(this,120000)}}
 override fun onCreate(){super.onCreate();lm=getSystemService(LocationManager::class.java);getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("proximity","SongsLike Proximity",NotificationManager.IMPORTANCE_LOW));startForeground(77,Notification.Builder(this,"proximity").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("SongsLike proximity").setContentText("Watching for your saved playlist area").build());h.post(r)}
 private fun checkPlace(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=0)return;val l:Location?=try{lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?:lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)}catch(_:Exception){null};val p=getSharedPreferences("life",0);val a=l?.latitude?:return;val o=l.longitude;val lat=p.getString("lat","")?.toDoubleOrNull()?:return;val lon=p.getString("lon","")?.toDoubleOrNull()?:return;val d=FloatArray(1);Location.distanceBetween(a,o,lat,lon,d);if(d[0]<=250&&!p.getBoolean("inside",false)){p.edit().putBoolean("inside",true).apply();val k=p.getString("placeKeys","")?:"";if(k.isNotBlank())startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://open.songslike.com/"+k)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}else if(d[0]>400)p.edit().putBoolean("inside",false).apply()}
 override fun onDestroy(){h.removeCallbacks(r);super.onDestroy()};override fun onBind(i:Intent?):IBinder?=null
}
