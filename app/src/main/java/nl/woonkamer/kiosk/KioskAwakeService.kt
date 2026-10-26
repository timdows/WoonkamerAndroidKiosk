package nl.woonkamer.kiosk

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*

/** Keeps the display awake across allowed apps, only while actual Lock Task is active. */
class KioskAwakeService : Service() {
    private var screenLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            if (!isKioskActive()) { stopSelf(); return }
            handler.postDelayed(this, 2_000)
        }
    }
    private fun isKioskActive() = getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout") // Deliberately held for the user-enabled kiosk session; released on stop/destruction.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isKioskActive()) { stopSelf(); return START_NOT_STICKY }
        val channel = "kiosk-awake"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Woonkamer actief", NotificationManager.IMPORTANCE_LOW))
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_kiosk_notification)
            .setContentTitle("Woonkamer is actief").setContentText("Het scherm blijft aan. Stop via Beheer.")
            .setOngoing(true).setContentIntent(home).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        if (screenLock == null) {
            screenLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "WoonkamerKiosk:display").apply {
                    setReferenceCounted(false); acquire()
                }
        }
        handler.removeCallbacks(monitor)
        handler.post(monitor)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        screenLock?.let { if (it.isHeld) it.release() }
        screenLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        fun start(context: Context) = context.startForegroundService(Intent(context, KioskAwakeService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, KioskAwakeService::class.java)) }
    }
}
