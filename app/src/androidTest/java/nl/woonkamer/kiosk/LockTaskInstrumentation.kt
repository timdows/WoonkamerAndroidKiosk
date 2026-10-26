package nl.woonkamer.kiosk
import android.app.Activity
import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock

/** Emulator-only fixture; never included in the application APK. */
class LockTaskInstrumentation : Instrumentation() {
 override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() {
  val result = Bundle()
  var activity: Activity? = null
  var controller: KioskController? = null
  try {
   check(android.os.Build.MODEL.contains("sdk_gphone")) { "Emulator required" }
   val a = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   activity = a
   waitForIdleSync()
   val dpm = targetContext.getSystemService(DevicePolicyManager::class.java)
   val admin = ComponentName(targetContext, KioskAdminReceiver::class.java)
   val prefs = targetContext.getSharedPreferences("kiosk-policy", 0)
   val oldPackages = dpm.getLockTaskPackages(admin).toSet()
   val oldFeatures = dpm.getLockTaskFeatures(admin)
   val c = KioskController(a)
   controller = c
   runOnMainSync {
    check(c.isOwner && !c.isLocked)
    check(!prefs.contains("oldFeatures"))
    check(prefs.edit().putInt("oldFeatures", oldFeatures).putStringSet("oldPackages", oldPackages).commit())
    // No real Spotify/Samsung apps on emulator: test actual lock and exit only.
    dpm.setLockTaskPackages(admin, arrayOf(targetContext.packageName))
    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
     DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
     DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
    val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
    dpm.addPersistentPreferredActivity(admin, home, ComponentName(targetContext, MainActivity::class.java))
    a.startLockTask()
   }
   SystemClock.sleep(500)
   check(c.isLocked) { "Actual LOCKED mode not entered" }
   sendStatus(1, Bundle().apply { putString("stream", "LOCKED: waiting for administrator authentication and UI exit\n") })
   val deadline = SystemClock.elapsedRealtime() + 240_000
   while (prefs.contains("oldFeatures") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
   check(!prefs.contains("oldFeatures")) { "Administrator exit timed out" }
   check(!c.isLocked)
   check(dpm.getLockTaskPackages(admin).toSet() == oldPackages)
   check(dpm.getLockTaskFeatures(admin) == oldFeatures)
   result.putString("stream", "PASS: real Lock Task, administrator UI exit, allowlist/features restored.\n")
   finish(Activity.RESULT_OK, result)
  } catch (e: Throwable) {
   result.putString("stream", "FAIL: ${e.stackTraceToString()}")
   finish(Activity.RESULT_CANCELED, result)
  } finally {
   controller?.let { c -> runOnMainSync { runCatching { c.exitAfterAuthentication() } } }
   activity?.let { a -> runOnMainSync { a.finish() } }
  }
 }
}
