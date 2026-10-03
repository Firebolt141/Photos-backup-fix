package com.firebolt141.ubertrag.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.firebolt141.ubertrag.R
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the app alive (foreground notification + CPU wake lock) while a long
 * job started from a screen is running — Takeout import, Organize by date,
 * Fix dates, Rename folders. The work itself stays in the screen's ViewModel;
 * without this, Android may pause or kill it once the screen turns off.
 *
 *     KeepAlive.begin(context, "Importing Takeout")
 *     try { … KeepAlive.update(context, "IMG_1234.jpg", done, total) … }
 *     finally { KeepAlive.end() }
 */
object KeepAlive {
    internal val active = AtomicInteger(0)
    @Volatile internal var title = ""
    private var lastNotifyMs = 0L

    fun begin(context: Context, what: String) {
        title = what
        active.incrementAndGet()
        try {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            // Not allowed from the background (Android 12+): the job still runs, just unprotected.
            Log.w("KeepAlive", "could not start: ${e.message}")
        }
    }

    fun update(context: Context, text: String, done: Int, total: Int) {
        if (active.get() <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 700 && done != total) return
        lastNotifyMs = now
        context.getSystemService(NotificationManager::class.java)!!
            .notify(KeepAliveService.NOTIF_ID, KeepAliveService.build(context, text, done, total))
    }

    fun end() {
        if (active.decrementAndGet() < 0) active.set(0)
        // The service notices within a second and stops itself.
    }
}

class KeepAliveService : Service() {

    companion object {
        const val NOTIF_ID = 3

        fun build(context: Context, text: String, done: Int, total: Int) =
            NotificationCompat.Builder(context, CopyService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(KeepAlive.title.ifBlank { "Working…" } + if (total > 0) " · $done of $total" else "")
                .setContentText(text)
                .setContentIntent(CopyService.openAppIntent(context))
                .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        CopyService.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, "Keep the drive connected until this finishes", 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w("KeepAlive", "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)!!
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ubertrag:job")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                while (isActive && KeepAlive.active.get() > 0) delay(1000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+ time limit for data-sync services: drop the protection, the job itself keeps going. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("KeepAlive", "foreground time limit reached")
        stopSelf()
    }
}
