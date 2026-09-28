package com.batterynag.app
import android.content.*
import android.os.BatteryManager
class ToneReceiver:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent?){
  val b=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val l=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
  val s=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
  if(l>=0){val p=l*100/s;if(p<=30){BatteryNag.tone(context);BatteryNag.evaluate(context,p)}}
 }
}
