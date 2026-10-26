package nl.woonkamer.kiosk
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.telecom.TelecomManager

object KioskPlan {
 const val SPOTIFY = "com.spotify.music"
 const val DIALER = "com.samsung.android.dialer"
 // Candidate only; verify this component on the target device firmware.
 const val INCALL = "com.samsung.android.incallui"
 fun blockReason(enabled: Boolean, owner: Boolean, adminPinConfigured: Boolean, supervisedTrialConfirmed: Boolean): String? = when {
  !enabled -> "Deze voorbereidingsbuild kan kioskmodus niet activeren."
  !owner -> "Device Owner is nog niet ingesteld."
  !adminPinConfigured -> "Stel eerst een aparte beheer-PIN in."
  !supervisedTrialConfirmed -> "Bevestig eerst de begeleide proef en hersteltoegang."
  else -> null
 }
}
class KioskController(private val activity: Activity) {
 private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
 private val admin = ComponentName(activity, KioskAdminReceiver::class.java)
 private val prefs = activity.getSharedPreferences("kiosk-policy", 0)
 val isOwner get() = dpm.isDeviceOwnerApp(activity.packageName)
 val isLocked get() = activity.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
 // Re-evaluate prerequisites on every attempt, before any policy mutation.
 fun activationBlockReason(supervisedTrialConfirmed: Boolean): String? {
  KioskPlan.blockReason(BuildConfig.KIOSK_ACTIVATION_ENABLED, isOwner,
   AdminPinStore(activity).isConfigured,
   supervisedTrialConfirmed)?.let { return it }
  if (isLocked) return "Kioskmodus is al actief."
  if (prefs.contains("oldFeatures")) return "Herstel eerst eerder kioskbeleid met Kiosk stoppen."
  if (activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return "Ontgrendel eerst het toestel."
  for (pkg in requiredPackages) {
   val enabled = try { activity.packageManager.getApplicationInfo(pkg, 0).enabled }
    catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
   if (!enabled) return "Benodigde app ontbreekt of staat uit: $pkg"
  }
  if (activity.getSystemService(TelecomManager::class.java).defaultDialerPackage != KioskPlan.DIALER)
   return "Stel Samsung Telefoon eerst in als standaard bel-app."
  return null
 }
 private val requiredPackages get() = listOf(activity.packageName, KioskPlan.SPOTIFY, KioskPlan.DIALER, KioskPlan.INCALL)
 fun startAfterReviewedProvisioning(supervisedTrialConfirmed: Boolean) {
  val reason = activationBlockReason(supervisedTrialConfirmed)
  check(reason == null) { reason ?: "Activering geblokkeerd" }
  val packages = requiredPackages
  check(prefs.edit().putInt("oldFeatures", dpm.getLockTaskFeatures(admin))
   .putStringSet("oldPackages", dpm.getLockTaskPackages(admin).toSet()).commit())
  try {
   dpm.setLockTaskPackages(admin, packages.toTypedArray())
   dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
   val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
   dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
   check(dpm.isLockTaskPermitted(activity.packageName))
   activity.startLockTask()
   check(isLocked) { "Lock Task niet bevestigd" }
   KioskAwakeService.start(activity)
  } catch (e: Exception) {
   try { exitAfterAuthentication() } catch (rollbackFailure: Exception) { e.addSuppressed(rollbackFailure) }
   throw e
  }
 }
 // Caller authenticates with the separate administrator PIN. Device Owner remains.
 fun exitAfterAuthentication() {
  val hasSnapshot = prefs.contains("oldFeatures")
  check(!hasSnapshot || isOwner) { "Device Owner ontbreekt; opgeslagen beleid kan niet worden hersteld" }
  val failures = mutableListOf<Exception>()
  fun attempt(action: () -> Unit) {
   try { action() } catch (e: Exception) { failures.add(e) }
  }
  attempt { KioskAwakeService.stop(activity) }
  if (isLocked) attempt { activity.stopLockTask() }
  if (hasSnapshot) {
   attempt { dpm.setLockTaskPackages(admin, prefs.getStringSet("oldPackages", emptySet())!!.toTypedArray()) }
   attempt { dpm.setLockTaskFeatures(admin, prefs.getInt("oldFeatures", DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)) }
   attempt { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
  }
  attempt { check(!isLocked) { "Lock Task is nog actief" } }
  if (failures.isNotEmpty()) {
   val failure = IllegalStateException("Kioskherstel niet volledig; beleidskopie blijft bewaard", failures.first())
   failures.drop(1).forEach { failure.addSuppressed(it) }
   throw failure
  }
  if (hasSnapshot) check(prefs.edit().clear().commit()) { "Herstelstatus kon niet worden opgeslagen" }
 }
}
