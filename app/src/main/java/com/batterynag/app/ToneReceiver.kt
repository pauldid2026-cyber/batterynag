package com.batterynag.app
import android.content.*
import android.os.BatteryManager
class ToneReceiver:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent?){
  val b=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val l=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
  val s=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
  // Always hand the level over. Filtering here for p<=30 stopped the chain
  // dead above 30%, so no further check was ever scheduled.
  if(l>=0){BatteryNag.evaluate(context,l*100/s)}
 }
}
