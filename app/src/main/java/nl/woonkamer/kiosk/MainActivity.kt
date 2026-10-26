package nl.woonkamer.kiosk

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.InputType
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var kiosk: KioskController
    private lateinit var pins: AdminPinStore
    private var activeDialog: AlertDialog? = null
    private var awaitingSetupCredential = false
    private val worker = Executors.newSingleThreadExecutor()
    private var authEpoch = 0
    private var clockTapCount = 0
    private val ink = Color.rgb(30, 49, 40)
    private val cream = Color.rgb(246, 243, 236)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingSetupCredential = savedInstanceState?.getBoolean("setupCredential") ?: false
        kiosk = KioskController(this)
        pins = AdminPinStore(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            setBackgroundColor(cream)
        }
        page.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { intArrayOf(it.left,it.top,it.right,it.bottom) }
                else intArrayOf(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            view.setPadding(24.dp + bars[0], 20.dp + bars[1], 24.dp + bars[2], 24.dp + bars[3])
            insets
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Gezellige gezins telefoon", 19f, ink, true).apply { isAccessibilityHeading = true; setPadding(0,0,16.dp,0) }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(top)
        page.addView(Space(this), LinearLayout.LayoutParams(1,32.dp))
        page.addView(card("Spotify", "Zet iets moois op", "MUZIEK VOOR JOU", Color.rgb(28,51,42), Color.WHITE,
            Color.rgb(160,226,160), android.R.drawable.ic_media_play, "Spotify openen") { launch(KioskPlan.SPOTIFY) })
        page.addView(Space(this), LinearLayout.LayoutParams(1,16.dp))
        val lowerCards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lowerCards.addView(card("Bellen", "Even iemand horen", "", Color.rgb(230,218,196), ink,
            Color.rgb(250,247,237), android.R.drawable.ic_menu_call, "Samsung Telefoon openen", compact = true) { launch(KioskPlan.DIALER) },
            LinearLayout.LayoutParams(0,-2,1f))
        lowerCards.addView(Space(this),LinearLayout.LayoutParams(14.dp,1))
        lowerCards.addView(card("Home\nAssistant", "Alles voor thuis", "", Color.rgb(210,229,232), ink,
            Color.rgb(244,251,250), R.drawable.ic_kiosk_notification, "Home Assistant openen", compact = true) {
                val pkg = kiosk.homeAssistantPackage()
                if (pkg == null) message("Home Assistant ontbreekt", "Installeer en configureer de Home Assistant-app eerst buiten kioskmodus.")
                else launch(pkg)
            },LinearLayout.LayoutParams(0,-2,1f))
        page.addView(lowerCards,LinearLayout.LayoutParams(-1,-2))
        page.addView(Space(this), LinearLayout.LayoutParams(1,0,1f))
        page.addView(TextClock(this).apply {
            id = R.id.admin_clock
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            setOnClickListener {
                clockTapCount++
                if (clockTapCount == 10) {
                    clockTapCount = 0
                    authenticate()
                }
            }
            // Both formats are explicit so the clock stays 24h even on a 12h-configured device.
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            textSize = 76f; setTextColor(ink)
            typeface = Typeface.create("sans-serif-light",Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            setPadding(0,28.dp,0,8.dp)
        }, LinearLayout.LayoutParams(-1,-2))
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(cream); addView(page) })
        page.requestApplyInsets()
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.dp.toFloat()
        stroke?.let { setStroke(1.dp,it) }
    }
    private fun card(title: String, subtitle: String, eyebrow: String, backgroundColor: Int,
        textColor: Int, accent: Int, icon: Int, description: String, compact: Boolean = false, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (compact) setPadding(18.dp,18.dp,18.dp,20.dp) else setPadding(24.dp,22.dp,24.dp,24.dp)
            background = shape(backgroundColor,28f)
            minimumHeight = (if(compact) 220 else 190).dp; elevation = 1.dp.toFloat()
            isClickable = true; isFocusable = true; contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info); info.className = Button::class.java.name
                }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            if (!compact) row.addView(label(eyebrow,11f,textColor,true), LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ImageView(this@MainActivity).apply {
                setImageResource(icon); setColorFilter(if (textColor == Color.WHITE) backgroundColor else textColor); setPadding(12.dp,12.dp,12.dp,12.dp)
                background = shape(accent,32f)
            }, LinearLayout.LayoutParams(48.dp,48.dp))
            details.addView(row)
            details.addView(label(title,if(compact) 23f else 30f,textColor,true).apply { setPadding(0,12.dp,0,4.dp) })
            details.addView(label("$subtitle  ›",if(compact) 14f else 16f,textColor))
            addView(details)
            setOnClickListener { action() }
        }
    }
    override fun onResume() {
        super.onResume()
        if (kiosk.isLocked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { KioskAwakeService.start(this) }
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun launch(pkg: String) {
        clockTapCount = 0
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
                ?: return message("App niet beschikbaar", "Installeer of schakel deze app eerst in.")
            val options = if (kiosk.isLocked) ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle() else null
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options)
        } catch (_: ActivityNotFoundException) { message("App niet beschikbaar", "De app kan niet worden geopend.") }
          catch (_: SecurityException) { message("App geblokkeerd", "Controleer installatie en apparaatbeleid.") }
    }
    private fun authenticate() {
        if (!pins.isConfigured) { beginPinSetup(); return }
        val wait = pins.remainingWaitMillis()
        if (wait > 0) { message("Even wachten", "Probeer het over ${(wait+999)/1000} seconden opnieuw."); return }
        val field = pinField("Beheer-PIN")
        val dialog = AlertDialog.Builder(this).setTitle("Beheer")
            .setMessage("Voer je aparte beheer-PIN in.").setView(pinContainer(field))
            .setNegativeButton("Terug",null).setPositiveButton("Open beheer",null).create()
        showSensitive(dialog)
        dialog.setOnDismissListener { field.text.clear() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = field.text.toString().toCharArray()
            if (!PinHash.isValid(pin)) { pin.fill(' '); field.error = "Gebruik 6 tot 12 cijfers"; return@setOnClickListener }
            val epoch = authEpoch
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            worker.execute {
                val result = runCatching { pins.verify(pin) }
                pin.fill(' ')
                runOnUiThread {
                    if (isFinishing || isDestroyed || epoch != authEpoch) return@runOnUiThread
                    dialog.dismiss()
                    when {
                        result.isFailure -> message("Herstel nodig", "Beheer kon niet worden gecontroleerd. Gebruik zo nodig de ADB-herstelroute.")
                        result.getOrDefault(false) -> showAdmin(AdminWindow(SystemClock.elapsedRealtime()))
                        else -> message("PIN klopt niet", "Probeer opnieuw. Na vijf onjuiste pogingen geldt 30 seconden wachttijd.")
                    }
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun beginPinSetup() {
        if (kiosk.isLocked) { message("Eerst kiosk stoppen", "Stop eerst de kiosk met de vorige beheerroute of via ADB voordat je de nieuwe beheer-PIN instelt."); return }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) { showPinSetup(); return }
        val intent = km.createConfirmDeviceCredentialIntent("Beheer-PIN instellen", "Bevestig eenmalig je huidige toestelpatroon.")
            ?: return message("Niet beschikbaar", "De toestelverificatie is niet beschikbaar.")
        try { awaitingSetupCredential = true; startActivityForResult(intent,42) }
        catch (_: ActivityNotFoundException) { awaitingSetupCredential = false; message("Niet beschikbaar", "De toestelverificatie kon niet openen.") }
    }
    @Deprecated("Platform credential result used only to initialize separate administrator PIN")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 42 || !awaitingSetupCredential) return
        awaitingSetupCredential = false
        if (resultCode == RESULT_OK) showPinSetup()
    }
    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(12)); isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        textSize = 20f; setSingleLine()
    }
    private fun pinContainer(vararg fields: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(24.dp,8.dp,24.dp,0)
        fields.forEach { addView(it,LinearLayout.LayoutParams(-1,64.dp)) }
    }
    private fun showSensitive(dialog: AlertDialog) {
        activeDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun showPinSetup(session: AdminWindow? = null) {
        val first = pinField("Nieuwe PIN · 6–12 cijfers")
        val second = pinField("Herhaal de PIN")
        val dialog = AlertDialog.Builder(this).setTitle(if(pins.isConfigured) "Beheer-PIN wijzigen" else "Kies een beheer-PIN")
            .setMessage("Gebruik een andere code dan je toestelontgrendeling. Alleen deze PIN geeft straks toegang tot beheer. Onthoud hem goed.")
            .setView(pinContainer(first,second)).setNegativeButton("Annuleren",null).setPositiveButton("PIN opslaan",null).create()
        showSensitive(dialog)
        dialog.setOnDismissListener { first.text.clear(); second.text.clear() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (session != null && !isCurrent(session)) { dialog.dismiss(); return@setOnClickListener }
            val pin = first.text.toString().toCharArray()
            val repeated = second.text.toString().toCharArray()
            val valid = PinHash.isValid(pin) && pin.contentEquals(repeated)
            repeated.fill(' ')
            if (!valid) { pin.fill(' '); second.error = "Gebruik dezelfde PIN van 6 tot 12 cijfers"; return@setOnClickListener }
            val epoch = authEpoch
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            worker.execute {
                val result = runCatching { pins.configure(pin) }
                pin.fill(' ')
                runOnUiThread {
                    if (isFinishing || isDestroyed || epoch != authEpoch) return@runOnUiThread
                    dialog.dismiss()
                    if (result.isSuccess) message("Beheer-PIN ingesteld", "Open Beheer opnieuw met je nieuwe PIN. Je toestelpatroon blijft behouden buiten kioskmodus.")
                    else message("Niet opgeslagen", "De beheer-PIN kon niet veilig worden opgeslagen. Kioskactivering blijft geblokkeerd.")
                }
            }
        }
        if (session != null) expire(dialog,session)
    }
    private fun isCurrent(session: AdminWindow): Boolean {
        if (session.isValidAt(SystemClock.elapsedRealtime())) return true
        message("Beheer verlopen", "Voer opnieuw je beheer-PIN in."); return false
    }
    private fun expire(dialog: AlertDialog, session: AdminWindow) {
        activeDialog = dialog
        val remaining = (AdminWindow.DURATION_MILLIS - (SystemClock.elapsedRealtime()-session.authenticatedAt)).coerceAtLeast(0)
        window.decorView.postDelayed({ if(dialog.isShowing) dialog.dismiss() },remaining)
    }
    private fun showAdmin(session: AdminWindow) {
        val choices = mutableListOf<String>()
        if (BuildConfig.KIOSK_ACTIVATION_ENABLED && !kiosk.isLocked) choices.add("Kiosk starten")
        choices.add("Kiosk stoppen"); choices.add("Beheer-PIN wijzigen"); choices.add("Status en informatie")
        val dialog = AlertDialog.Builder(this).setTitle("Beheer")
            .setItems(choices.toTypedArray()) { _, index ->
                if (!isCurrent(session)) return@setItems
                when (choices[index]) {
                    "Kiosk starten" -> confirmTrial(session)
                    "Kiosk stoppen" -> stopKiosk(session)
                    "Beheer-PIN wijzigen" -> showPinSetup(session)
                    else -> message("Woonkamer · ${BuildConfig.VERSION_NAME}",
                        "Kiosk: ${if(kiosk.isLocked) "actief" else "uit"}\nApparaatbeheer: ${if(kiosk.isOwner) "ingesteld" else "niet ingesteld"}\nScherm blijft aan tijdens kiosk, ook in Spotify. De aan-uitknop kan het scherm uitschakelen.\nNa herstart is mogelijk eerst je toestelpatroon nodig.\n${if(BuildConfig.KIOSK_ACTIVATION_ENABLED) "Begeleide testversie met ADB-herstel." else "Activering is in deze build geblokkeerd."}")
                }
            }.setNegativeButton("Sluiten",null).show()
        expire(dialog,session)
    }
    private fun stopKiosk(session: AdminWindow) {
        if (!isCurrent(session)) return
        try { kiosk.exitAfterAuthentication(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            message("Kiosk gestopt", "Je telefoon is weer normaal te gebruiken. Het toestelpatroon geldt weer buiten kioskmodus.")
        } catch (_: Exception) { message("Herstel nodig", "Stoppen kon niet volledig worden bevestigd. Laat USB aangesloten voor ADB-herstel.") }
    }
    private fun confirmTrial(session: AdminWindow) {
        val reason = kiosk.activationBlockReason(true)
        if (reason != null) { message("Kiosk nog niet beschikbaar",reason); return }
        val confirm = CheckBox(this).apply {
            text = "De beheer-PIN is bekend en de herstelroute is gecontroleerd."
            setPadding(24.dp,12.dp,24.dp,12.dp)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Woonkamer starten?")
            .setMessage("Alleen de toegestane apps zijn bereikbaar. Het scherm blijft aan en het vergrendelscherm blijft uit. Stoppen kan via Beheer met je aparte PIN. Test daarna ook inkomende oproepen.")
            .setView(confirm).setNegativeButton("Nog niet",null).setPositiveButton("Start kiosk") { _,_ ->
                if (isCurrent(session) && confirm.isChecked) {
                    try { kiosk.startAfterReviewedProvisioning(true); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    catch (e: Exception) { message("Niet gestart",e.message ?: "Controleer de herstelroute.") }
                }
            }.create()
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        confirm.setOnCheckedChangeListener { _,checked -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked }
        expire(dialog,session)
    }
    override fun onPause() {
        clockTapCount = 0
        authEpoch++; activeDialog?.dismiss(); activeDialog = null; super.onPause()
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("setupCredential",awaitingSetupCredential); super.onSaveInstanceState(outState)
    }
    private fun message(title: String, body: String) {
        if (!isFinishing && !isDestroyed) {
            activeDialog = AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show()
        }
    }
    private val Int.dp get() = (this*resources.displayMetrics.density).toInt()
    private val Float.dp get() = (this*resources.displayMetrics.density).toInt()
}
