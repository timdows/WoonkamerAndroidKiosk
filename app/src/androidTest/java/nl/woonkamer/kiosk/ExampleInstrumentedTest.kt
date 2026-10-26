package nl.woonkamer.kiosk
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView

/** Emulator-only fixture, excluded from application APKs. */
class SmokeInstrumentation : Instrumentation() {
 override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() {
  var activity: Activity? = null
  val result = Bundle()
  var code = Activity.RESULT_CANCELED
  try {
   check(android.os.Build.MODEL.contains("sdk_gphone")) { "Emulator required" }
   val pins = AdminPinStore(targetContext)
   val random = java.security.SecureRandom()
   val pin = CharArray(8) { ('0'.code + random.nextInt(10)).toChar() }
   val wrong = pin.copyOf().apply { this[0] = if(this[0] == '9') '0' else this[0] + 1 }
   try {
    pins.configure(pin)
    check(pins.isConfigured && pins.verify(pin))
    repeat(5) { check(!pins.verify(wrong)) }
    check(pins.remainingWaitMillis() > 0 && !pins.verify(pin))
    check(pins.remainingWaitMillis(System.currentTimeMillis()+31_000) == 0L)
    pins.configure(pin)
   } finally { pin.fill(' '); wrong.fill(' ') }
   val a = startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   activity = a
   waitForIdleSync()
   runOnMainSync {
    fun texts(v: View): List<TextView> = (if(v is TextView) listOf(v) else emptyList()) +
     (if(v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
    val values = texts(a.window.decorView).map { it.text.toString().replace("\n"," ") }
    check(values.containsAll(listOf("Spotify","Bellen","Home Assistant")) && !values.contains("Beheer"))
    for (title in listOf("Spotify","Bellen","Home Assistant")) {
     val view = texts(a.window.decorView).first { it.text.toString().replace("\n"," ") == title }
     check(view.getGlobalVisibleRect(android.graphics.Rect())) { "Launch card is not visible" }
    }
    val controller = KioskController(a)
    check(!controller.isLocked && !controller.isOwner)
    var blocked = false
    try { controller.startAfterReviewedProvisioning(true) } catch(_: IllegalStateException) { blocked = true }
    check(blocked && !a.getSharedPreferences("kiosk-policy",0).contains("oldFeatures"))
    val clock = a.findViewById<TextClock>(R.id.admin_clock)
    check(clock.format12Hour == "HH:mm" && clock.format24Hour == "HH:mm")
    repeat(9) { clock.performClick() }
   }
   waitForIdleSync()
   val rootDeadline = SystemClock.elapsedRealtime()+3_000
   while(uiAutomation.rootInActiveWindow == null && SystemClock.elapsedRealtime()<rootDeadline) SystemClock.sleep(100)
   checkNotNull(uiAutomation.rootInActiveWindow)
   fun pinDialogVisible() = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Voer je aparte beheer-PIN in.")?.isNotEmpty() == true
   check(!pinDialogVisible())
   runOnMainSync { a.findViewById<TextClock>(R.id.admin_clock).performClick() }
   waitForIdleSync()
   val deadline = SystemClock.elapsedRealtime()+3_000
   while(!pinDialogVisible() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
   check(pinDialogVisible())
   result.putString("stream","PASS: no administrator button, 24-hour clock, nine taps stay silent, tenth tap opens PIN; PIN verifier and rate limit passed.\n")
   code = Activity.RESULT_OK
  } catch(e: Throwable) { result.putString("stream","FAIL: ${e.stackTraceToString()}") }
  finally { activity?.let { a -> runOnMainSync { a.finish() } } }
  finish(code,result)
 }
}
