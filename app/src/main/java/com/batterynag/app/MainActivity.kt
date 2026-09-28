package com.batterynag.app
import android.Manifest
import android.app.Activity
import android.content.*
import android.os.*
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
class MainActivity:Activity(){
 private lateinit var status:TextView
 override fun onCreate(s:Bundle?){super.onCreate(s);buildUi();if(Build.VERSION.SDK_INT>=33)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),10);BatteryNag.checkCurrentBattery(this)}
 private fun buildUi(){val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(40,40,40,40);setBackgroundColor(0xFF000000.toInt())};r.addView(TextView(this).apply{text="BATTERY NAG";textSize=30f;setTextColor(-1);gravity=Gravity.CENTER});status=TextView(this).apply{textSize=20f;setTextColor(-1);gravity=Gravity.CENTER;setPadding(0,30,0,30)};r.addView(status);r.addView(TextView(this).apply{text="Warns below 30% and gets more frequent as the battery falls.";textSize=16f;setTextColor(0xFFCCCCCC.toInt());gravity=Gravity.CENTER});r.addView(TextView(this).apply{text="IGNORE WARNINGS FOR:";textSize=14f;setTextColor(0xFFAAAAAA.toInt());gravity=Gravity.CENTER;setPadding(0,40,0,15)});for(h in 1..5){r.addView(Button(this).apply{text="$h HOUR"+if(h>1)"S" else "";setOnClickListener{BatteryNag.snooze(this@MainActivity,h);updateStatus();Toast.makeText(this@MainActivity,"Warnings snoozed for $h hour(s)",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(-1,55).apply{setMargins(0,4,0,4)})};setContentView(r);updateStatus()}
 private fun updateStatus(){val b=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val l=b?.getIntExtra("level",-1)?:-1;val s=b?.getIntExtra("scale",100)?:100;val p=if(l>=0)l*100/s else -1;status.text=if(BatteryNag.isSnoozed(this))"Warnings snoozed" else "Battery: $p%"}
}
