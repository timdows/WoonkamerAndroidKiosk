package nl.woonkamer.kiosk
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
 private lateinit var kiosk: KioskController
 private lateinit var status: TextView
 private lateinit var callInfo: TextView
 private var awaitingCredential = false
 private var adminDialog: AlertDialog? = null
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  awaitingCredential = savedInstanceState?.getBoolean("awaitingCredential") ?: false
  kiosk = KioskController(this)
  val column = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
   setPadding(24.dp, 24.dp, 24.dp, 24.dp)
  }
  column.setOnApplyWindowInsetsListener { v, i ->
   v.setPadding(24.dp + i.systemWindowInsetLeft, 24.dp + i.systemWindowInsetTop,
    24.dp + i.systemWindowInsetRight, 24.dp + i.systemWindowInsetBottom)
   i
  }
  fun text(value: String, size: Float) = TextView(this).apply {
   text = value; textSize = size; setTextColor(Color.rgb(25,44,34))
   setPadding(0,12.dp,0,12.dp); column.addView(this)
  }
  text("Woonkamer",34f).apply { isAccessibilityHeading = true }
  text("Muziek en telefoon",20f)
  status = text("",16f)
  fun button(label: String, action: () -> Unit) {
   column.addView(Button(this).apply {
    text = label; textSize = 23f; isAllCaps = false; minHeight = 88.dp
    setOnClickListener { action() }
   }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 16.dp })
  }
  button("Spotify") { launch(KioskPlan.SPOTIFY) }
  button("Samsung Telefoon") { launch(KioskPlan.DIALER) }
  callInfo = text("",17f)
  button("Beheerder") { authenticate() }
  setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
  column.requestApplyInsets()
 }
 override fun onResume() {
  super.onResume()
  refreshStatus()
 }
 private fun refreshStatus() {
  val mode = when {
   kiosk.isLocked -> "Kiosk actief"
   BuildConfig.KIOSK_ACTIVATION_ENABLED -> "Begeleide proef • nog niet vergrendeld"
   else -> "Voorbereidingsmodus • activering geblokkeerd"
  }
  status.text = "$mode\nDevice Owner: ${if(kiosk.isOwner) "ja" else "nee"}"
  callInfo.text = if (kiosk.isLocked) "Oproepen via Samsung Telefoon. Bij problemen: Beheerder → Kiosk stoppen."
   else "Oproepen worden door Samsung Telefoon afgehandeld. Kioskmodus is nog niet ingeschakeld."
 }
 private fun launch(pkg: String) {
  try {
   val intent = packageManager.getLaunchIntentForPackage(pkg)
    ?: return message("App niet beschikbaar", "Installeer of schakel $pkg eerst in.")
   val options = if (kiosk.isLocked) ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle() else null
   startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
  } catch(e: ActivityNotFoundException) { message("App niet beschikbaar", "De app kan niet worden geopend.")
  } catch(e: SecurityException) { message("App geblokkeerd", "Controleer installatie en apparaatbeleid.") }
 }
 @Suppress("DEPRECATION")
 private fun authenticate() {
  val km = getSystemService(KeyguardManager::class.java)
  if(!km.isDeviceSecure) {
   message("Beheerder", "Stel eerst op het toestel een beheerder-schermcode in. Er is geen standaard kiosk-PIN."); return
  }
  val intent = km.createConfirmDeviceCredentialIntent("WoonkamerKiosk beheren", "Bevestig de schermcode van de beheerder.")
   ?: return message("Niet beschikbaar", "Beheerderverificatie is niet beschikbaar.")
  try { awaitingCredential = true; startActivityForResult(intent,42)
  } catch(e: ActivityNotFoundException) { awaitingCredential = false; message("Niet beschikbaar", "Beheerderverificatie kon niet openen.") }
 }
 @Deprecated("Platform credential result")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode != 42 || !awaitingCredential) return
  awaitingCredential = false
  if(resultCode != RESULT_OK) return
  showAdmin(AdminWindow(SystemClock.elapsedRealtime()))
 }
 private fun isCurrent(session: AdminWindow): Boolean {
  if (session.isValidAt(SystemClock.elapsedRealtime())) return true
  message("Beheer verlopen", "Bevestig opnieuw de schermcode van de beheerder.")
  return false
 }
 private fun trackAdmin(dialog: AlertDialog, session: AdminWindow) {
  adminDialog = dialog
  val remaining = (AdminWindow.DURATION_MILLIS - (SystemClock.elapsedRealtime() - session.authenticatedAt)).coerceAtLeast(0)
  window.decorView.postDelayed({ if(dialog.isShowing) dialog.dismiss() }, remaining)
 }
 private fun showAdmin(session: AdminWindow) {
  val builder = AlertDialog.Builder(this).setTitle("Beheerder")
   .setMessage(if (BuildConfig.KIOSK_ACTIVATION_ENABLED) "Start uitsluitend een begeleide proef met hersteltoegang. Stoppen herstelt het opgeslagen kioskbeleid; Device Owner blijft behouden."
    else "Activering is in deze voorbereidingsbuild geblokkeerd. Stoppen herstelt eventueel opgeslagen kioskbeleid. Device Owner blijft behouden.")
   .setNegativeButton("Terug",null)
   .setNeutralButton("Kiosk stoppen") { _,_ -> stopKiosk(session) }
  if (BuildConfig.KIOSK_ACTIVATION_ENABLED && !kiosk.isLocked) {
   builder.setPositiveButton("Proef voorbereiden") { _,_ -> if (isCurrent(session)) confirmTrial(session) }
  }
  trackAdmin(builder.show(),session)
 }
 private fun stopKiosk(session: AdminWindow) {
  if (!isCurrent(session)) return
  try {
   kiosk.exitAfterAuthentication()
   refreshStatus()
   message("Kiosk gestopt", "De gewone bediening is beschikbaar. Device Owner is niet verwijderd.")
  } catch(e: Exception) { message("Herstel nodig", "Stoppen kon niet volledig worden bevestigd. Bewaar ADB-hersteltoegang en controleer het beleid.") }
 }
 private fun confirmTrial(session: AdminWindow) {
  val reason = kiosk.activationBlockReason(true)
  if (reason != null) { message("Proef niet beschikbaar",reason); return }
  val confirm = CheckBox(this).apply {
   text = "De gewone oproepentest is geslaagd. De beheerder is aanwezig en ADB-hersteltoegang is gecontroleerd."
   setPadding(24.dp,16.dp,24.dp,16.dp)
  }
  val dialog = AlertDialog.Builder(this).setTitle("Kioskproef starten?")
   .setMessage("Home en appwisselen worden beperkt. Test eerst direct de beheerdersexit en daarna inkomende oproepen. Installatie of herstart activeert de kiosk nooit automatisch.")
   .setView(confirm).setNegativeButton("Annuleren",null)
   .setPositiveButton("Nu vergrendelen") { _,_ ->
    if (isCurrent(session) && confirm.isChecked) {
     try {
      kiosk.startAfterReviewedProvisioning(true)
      refreshStatus()
     } catch (e: Exception) { refreshStatus(); message("Proef niet gestart",e.message ?: "Controleer apparaatbeleid en hersteltoegang.") }
    }
   }.create()
  dialog.show()
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
  confirm.setOnCheckedChangeListener { _, checked -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked }
  trackAdmin(dialog,session)
 }
 override fun onPause() { adminDialog?.dismiss(); adminDialog = null; super.onPause() }
 override fun onSaveInstanceState(outState: Bundle) {
  outState.putBoolean("awaitingCredential",awaitingCredential); super.onSaveInstanceState(outState)
 }
 private fun message(title: String, body: String) {
  if(!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show()
 }
 private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
