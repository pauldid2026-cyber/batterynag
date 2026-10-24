package com.songslike.life
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.widget.*

class MainActivity:Activity(){
 private val prefs by lazy{getSharedPreferences("life",MODE_PRIVATE)}
 private lateinit var status:TextView
 private lateinit var transcript:TextView
 private var recognizer:SpeechRecognizer?=null
 override fun onCreate(b:Bundle?){super.onCreate(b);window.decorView.setBackgroundColor(0xFF000000.toInt());buildUi()}
 private fun buildUi(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,28,28,28)}
  fun tv(s:String,z:Float=16f)=TextView(this).apply{text=s;textSize=z;setTextColor(0xFFFFFFFF.toInt());setPadding(0,10,0,10)}
  root.addView(tv("SONGSLIKE",30f));root.addView(tv("SOUNDTRACK TO YOUR LIFE",20f));status=tv("Ready",14f);root.addView(status)
  val mic=Button(this).apply{text="MICROPHONE: OFF";setOnClickListener{toggleMic(this)}};root.addView(mic)
  transcript=tv("Context will appear here.",16f);root.addView(transcript)
  root.addView(Button(this).apply{text="MAKE PLAYLIST FROM CONTEXT";setOnClickListener{openSongsLike(extractKeywords(transcript.text.toString()))}})
  root.addView(tv("PROXIMITY PLAYLIST",20f))
  val prox=Button(this).apply{text=if(prefs.getBoolean("prox",false))"PROXIMITY: ON" else "PROXIMITY: OFF"}
  prox.setOnClickListener{if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),20)else{prefs.edit().putBoolean("prox",!prefs.getBoolean("prox",false)).apply();startOrStopProximity();prox.text=if(prefs.getBoolean("prox",false))"PROXIMITY: ON" else "PROXIMITY: OFF"}}
  root.addView(prox);root.addView(tv("Enter a saved radius to automatically open its SongsLike playlist.",13f));root.addView(tv("SCHEDULER",20f))
  root.addView(Button(this).apply{text="SCHEDULE PLAYLIST";setOnClickListener{showScheduleDialog()}})
  root.addView(Button(this).apply{text="VIEW SCHEDULES";setOnClickListener{showSchedules()}})
  root.addView(Button(this).apply{text="PROXIMITY PLACE / KEYWORDS";setOnClickListener{showPlaceDialog()}})
  setContentView(root)
 }
 private fun toggleMic(button:Button){
  if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Speech recognition unavailable";return}
  if(recognizer!=null){recognizer?.cancel();recognizer?.destroy();recognizer=null;button.text="MICROPHONE: OFF";status.text="Mic off";return}
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10);return}
  recognizer=SpeechRecognizer.createSpeechRecognizer(this)
  recognizer?.setRecognitionListener(object:RecognitionListener{
   override fun onResults(r:Bundle?){val t=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();transcript.text=t.ifBlank{"No speech detected"};status.text="Context captured";recognizer?.startListening(speechIntent())}
   override fun onError(e:Int){status.text="Mic listening paused ("+e+")";if(recognizer!=null)recognizer?.startListening(speechIntent())}
   override fun onReadyForSpeech(p0:Bundle?){status.text="Listening…"};override fun onBeginningOfSpeech(){};override fun onRmsChanged(p0:Float){};override fun onBufferReceived(p0:ByteArray?){};override fun onEndOfSpeech(){};override fun onPartialResults(p0:Bundle?){};override fun onEvent(p0:Int,p1:Bundle?){}
  });button.text="MICROPHONE: ON";recognizer?.startListening(speechIntent())
 }
 private fun speechIntent()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)}
 private fun extractKeywords(s:String):String{val stop=setOf("the","and","that","this","with","from","have","for","you","are","was","what","when","where","your","about");return s.lowercase().split(Regex("\\W+")).filter{it.length>2&&!stop.contains(it)}.distinct().take(8).joinToString("+")}
 private fun openSongsLike(k:String){if(k.isBlank()){status.text="Need some context first";return};startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://open.songslike.com/"+k)))}
 private fun startOrStopProximity(){if(prefs.getBoolean("prox",false))startForegroundService(Intent(this,ProximityService::class.java))else stopService(Intent(this,ProximityService::class.java))}
 private fun showPlaceDialog(){
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val key=EditText(this).apply{hint="playlist keywords";setText(prefs.getString("placeKeys",""))};val lat=EditText(this).apply{hint="latitude";inputType=2 or 8192;setText(prefs.getString("lat",""))};val lon=EditText(this).apply{hint="longitude";inputType=2 or 8192;setText(prefs.getString("lon",""))};box.addView(key);box.addView(lat);box.addView(lon)
  AlertDialog.Builder(this).setTitle("Proximity playlist").setView(box).setPositiveButton("SAVE"){_,_->prefs.edit().putString("placeKeys",key.text.toString()).putString("lat",lat.text.toString()).putString("lon",lon.text.toString()).apply()}.setNegativeButton("CANCEL",null).show()
 }
 private fun showScheduleDialog(){val input=EditText(this).apply{hint="playlist keywords";setSingleLine()};val choices=arrayOf("Once","Daily","Weekly","Monthly","Yearly");var which=0;AlertDialog.Builder(this).setTitle("Schedule SongsLike").setView(input).setSingleChoiceItems(choices,0){_,w->which=w}.setPositiveButton("SAVE"){_,_->ScheduleStore.add(this,input.text.toString(),choices[which])}.setNegativeButton("CANCEL",null).show()}
 private fun showSchedules(){val all=prefs.getStringSet("schedules",emptySet())!!.toList();AlertDialog.Builder(this).setTitle("Upcoming schedules").setMessage(if(all.isEmpty())"No schedules yet." else all.joinToString("\n\n")).setPositiveButton("OK",null).show()}
 override fun onDestroy(){recognizer?.destroy();super.onDestroy()}
}
