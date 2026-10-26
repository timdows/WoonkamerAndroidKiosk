package nl.woonkamer.kiosk

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/** Temporary-emulator fixture. No credentials or real device identifiers are recorded. */
class LockTaskInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun shell(command: String): String = uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime()+10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected test control not available")
    }
    private fun clickText(value: String) {
        var node = awaitNode { it.text?.toString()?.equals(value,ignoreCase = true) == true }
        repeat(5) {
            if(node.isClickable) { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            node = node.parent ?: error("Control is not clickable")
        }
        error("No clickable parent")
    }
    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        var activity: Activity? = null
        var controller: KioskController? = null
        var originalTimeout: String? = null
        var originalStayOn: String? = null
        val pin = CharArray(8) { ('0'.code+java.security.SecureRandom().nextInt(10)).toChar() }
        try {
            check(android.os.Build.MODEL.contains("sdk_gphone")) { "Emulator required" }
            AdminPinStore(targetContext).configure(pin)
            val a = startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = a
            val dpm = targetContext.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(targetContext,KioskAdminReceiver::class.java)
            val prefs = targetContext.getSharedPreferences("kiosk-policy",0)
            val oldPackages = dpm.getLockTaskPackages(admin).toSet()
            val oldFeatures = dpm.getLockTaskFeatures(admin)
            val c = KioskController(a); controller = c
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val settingsPackage = settingsIntent.resolveActivity(targetContext.packageManager)!!.packageName
            runOnMainSync {
                check(c.isOwner && !c.isLocked && !prefs.contains("oldFeatures"))
                check(prefs.edit().putInt("oldFeatures",oldFeatures).putStringSet("oldPackages",oldPackages).commit())
                dpm.setLockTaskPackages(admin,arrayOf(targetContext.packageName,settingsPackage))
                dpm.setLockTaskFeatures(admin,DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
                val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
                dpm.addPersistentPreferredActivity(admin,home,ComponentName(targetContext,MainActivity::class.java))
                a.startLockTask()
            }
            SystemClock.sleep(500)
            check(c.isLocked)
            runOnMainSync { KioskAwakeService.start(a) }
            originalTimeout = shell("settings get system screen_off_timeout").trim()
            originalStayOn = shell("settings get global stay_on_while_plugged_in").trim()
            shell("settings put system screen_off_timeout 1000")
            shell("settings put global stay_on_while_plugged_in 0")
            runOnMainSync { a.startActivity(settingsIntent,ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle()) }
            SystemClock.sleep(4_000)
            check(targetContext.getSystemService(PowerManager::class.java).isInteractive) { "Screen did not stay awake across apps" }
            runOnMainSync { a.startActivity(Intent(a,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            clickText("Beheer")
            val field = awaitNode { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,String(pin))
            }))
            clickText("Open beheer")
            clickText("Kiosk stoppen")
            val deadline = SystemClock.elapsedRealtime()+10_000
            while (prefs.contains("oldFeatures") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            check(!prefs.contains("oldFeatures") && !c.isLocked)
            check(dpm.getLockTaskPackages(admin).toSet() == oldPackages)
            check(dpm.getLockTaskFeatures(admin) == oldFeatures)
            SystemClock.sleep(500)
            check(shell("dumpsys power").lineSequence().none { it.contains("WoonkamerKiosk:display") && it.contains("SCREEN_BRIGHT_WAKE_LOCK") }) { "Display wake lock was not released" }
            result.putString("stream","PASS: real Lock Task, screen awake across allowed app, separate PIN exit, policies restored, wake lock released.\n")
            resultCode = Activity.RESULT_OK
        } catch(e: Throwable) {
            result.putString("stream","FAIL: ${e.stackTraceToString()}")
        } finally {
            pin.fill(' ')
            controller?.let { c -> runOnMainSync { runCatching { c.exitAfterAuthentication() } } }
            originalTimeout?.let { if(it != "null") shell("settings put system screen_off_timeout $it") else shell("settings delete system screen_off_timeout") }
            originalStayOn?.let { if(it != "null") shell("settings put global stay_on_while_plugged_in $it") else shell("settings delete global stay_on_while_plugged_in") }
            activity?.let { a -> runOnMainSync { a.finish() } }
        }
        finish(resultCode,result)
    }
}
