package nl.woonkamer.kiosk
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

// Platform-only smoke runner. Run exclusively against an emulator, never a physical device.
class SmokeInstrumentation : Instrumentation() {
 override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() {
  var activity: Activity? = null
  val result = Bundle()
  try {
   check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
    "Smoke test is uitsluitend voor de emulator"
   }
   val a = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   activity = a
   waitForIdleSync()
   runOnMainSync {
    fun texts(v: View): List<TextView> = (if(v is TextView) listOf(v) else emptyList()) +
     (if(v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
    val views = texts(a.window.decorView)
    check(views.map { it.text.toString() }.containsAll(listOf("Spotify","Bellen","Beheer")))
    val controller = KioskController(a)
    check(!controller.isLocked && !controller.isOwner)
    var blocked = false
    try { controller.startAfterReviewedProvisioning(true) } catch(e: IllegalStateException) { blocked = true }
    check(blocked && !controller.isLocked)
    check(!a.getSharedPreferences("kiosk-policy",0).contains("oldFeatures"))
   }
   // Exercise the real device-bound verifier and persistent rate limit on emulator only.
   val pins = AdminPinStore(targetContext)
   val random = java.security.SecureRandom()
   val pin = CharArray(8) { ('0'.code + random.nextInt(10)).toChar() }
   val wrong = pin.copyOf().apply { this[0] = if(this[0] == '9') '0' else this[0] + 1 }
   try {
    pins.configure(pin)
    check(pins.isConfigured && pins.verify(pin))
    repeat(5) { check(!pins.verify(wrong)) }
    check(pins.remainingWaitMillis() > 0)
    check(!pins.verify(pin)) { "Rate limit must also block the correct PIN until timeout" }
    check(pins.remainingWaitMillis(System.currentTimeMillis()+31_000) == 0L)
   } finally { pin.fill(' '); wrong.fill(' ') }
   result.putString("stream", "PASS: startscherm, aparte PIN met Keystore en wachttijd, geen Device Owner/Lock Task, activering geblokkeerd zonder beleidsopslag.\n")
   finish(Activity.RESULT_OK, result)
  } catch(e: Throwable) {
   // Exercise the real device-bound verifier and persistent rate limit on emulator only.
   val pins = AdminPinStore(targetContext)
   val random = java.security.SecureRandom()
   val pin = CharArray(8) { ('0'.code + random.nextInt(10)).toChar() }
   val wrong = pin.copyOf().apply { this[0] = if(this[0] == '9') '0' else this[0] + 1 }
   try {
    pins.configure(pin)
    check(pins.isConfigured && pins.verify(pin))
    repeat(5) { check(!pins.verify(wrong)) }
    check(pins.remainingWaitMillis() > 0)
    check(!pins.verify(pin)) { "Rate limit must also block the correct PIN until timeout" }
    check(pins.remainingWaitMillis(System.currentTimeMillis()+31_000) == 0L)
   } finally { pin.fill(' '); wrong.fill(' ') }
   result.putString("stream", "FAIL: ${e.stackTraceToString()}")
   finish(Activity.RESULT_CANCELED, result)
  } finally { activity?.let { a -> runOnMainSync { a.finish() } } }
 }
}
