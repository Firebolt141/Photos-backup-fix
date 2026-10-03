package com.firebolt141.ubertrag.service

import com.firebolt141.ubertrag.util.loc
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.firebolt141.ubertrag.MainActivity
import com.firebolt141.ubertrag.R
import com.firebolt141.ubertrag.repository.CopySummary
import com.firebolt141.ubertrag.repository.SyncRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class CopyProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val speedMBps: Double,
)

/** Copies the queue to the drive in the foreground so it survives the screen turning off. */
class CopyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notifManager: NotificationManager
    private var copyJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L
    @Volatile private var timedOut = false

    companion object {
        private const val TAG    = "CopyService"
        const val CHANNEL_ID     = "copy_progress"
        const val NOTIF_ID       = 1
        const val NOTIF_SUMMARY  = 2
        const val ACTION_START   = "START_COPY"
        const val ACTION_STOP    = "STOP_COPY"
        const val EXTRA_FROM_MS  = "from_ms"
        const val EXTRA_TO_MS    = "to_ms"

        val copyProgress = MutableStateFlow<CopyProgress?>(null)
        /** Result of the most recent copy run in this process (shown on the home screen). */
        val lastSummary  = MutableStateFlow<CopySummary?>(null)
        @Volatile var stopRequested = false

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.loc().getString(R.string.ch_copy_progress), NotificationManager.IMPORTANCE_LOW)
            )
        }

        /** Tapping a notification opens the app. */
        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(NotificationManager::class.java)!!
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be in the foreground within a few seconds of startForegroundService(),
        // even when the request turns out to be a duplicate.
        val running = copyJob?.isActive == true
        if (!goForeground(buildProgressNotif(str(if (running) R.string.n_copying else R.string.n_starting_copy), 0, 0))) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                if (!running) stopSelf()
            }
            ACTION_START -> if (!running) startCopy(intent)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when started from the background
        Log.w(TAG, "startForeground failed: ${e.message}")
        false
    }

    private fun startCopy(intent: Intent) {
        val fromMs = intent.getLongExtra(EXTRA_FROM_MS, 0L)
        val toMs   = intent.getLongExtra(EXTRA_TO_MS,   0L)
        stopRequested = false
        lastSummary.value = null
        copyProgress.value = CopyProgress(0, 0, "", 0.0)

        wakeLock = getSystemService(PowerManager::class.java)!!
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ubertrag:copy")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }

        copyJob = scope.launch {
            var summary: CopySummary? = null
            try {
                summary = SyncRepository(applicationContext).copyPending(
                    fromMs      = fromMs,
                    toMs        = toMs,
                    isCancelled = { stopRequested },
                ) { done, total, name, speedMBps ->
                    copyProgress.value = CopyProgress(done, total, name, speedMBps)
                    // Android drops notification updates sent faster than ~10/s; keep it calm.
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyMs > 700 || done == total) {
                        lastNotifyMs = now
                        notifManager.notify(NOTIF_ID, buildProgressNotif(
                            if (name.isNotEmpty()) str(R.string.n_copying_name, name) else str(R.string.n_finishing), done, total,
                        ))
                    }
                }
            } catch (e: CancellationException) {
                // Service torn down mid-copy (time limit or system); not an error.
                summary = null
            } catch (e: Exception) {
                Log.e(TAG, "copy failed", e)
                summary = CopySummary(problem = e.message ?: str(R.string.copy_failed))
            } finally {
                val s = (summary ?: CopySummary(stoppedEarly = str(R.string.stopped))).let {
                    if (timedOut) it.copy(stoppedEarly = str(R.string.time_limit_reached)) else it
                }
                lastSummary.value = s
                copyProgress.value = null
                showSummaryNotification(s)
                try { wakeLock?.release() } catch (_: Exception) { }
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        copyProgress.value = null
        try { wakeLock?.release() } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Text in the app's chosen language. */
    private fun str(id: Int, vararg args: Any): String = loc().getString(id, *args)

    /**
     * Android 15+ limits data-sync foreground services to ~6 h a day and calls
     * this when the time is up; the service must stop within seconds or the
     * app crashes. Stop cleanly — files copied so far are kept.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached; stopping")
        timedOut = true
        stopRequested = true
        stopSelf()
    }

    private fun buildProgressNotif(text: String, done: Int, total: Int): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CopyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (total > 0) str(R.string.n_copying_to_drive_progress, done, total) else str(R.string.n_copying_to_drive))
            .setContentText(text)
            .setContentIntent(openAppIntent(this))
            .addAction(0, str(R.string.stop), stop)
            .apply {
                if (total > 0) setProgress(total, done, false)
                else setProgress(0, 0, true)
            }
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun showSummaryNotification(summary: CopySummary) {
        if (summary.total == 0 && summary.problem.isBlank() && summary.stoppedEarly.isBlank()) return
        val parts = mutableListOf<String>()
        if (summary.problem.isNotBlank()) parts += summary.problem
        if (summary.total > 0) parts += str(R.string.n_part_copied, summary.copied)
        if (summary.skipped > 0) parts += str(R.string.n_part_on_drive, summary.skipped)
        if (summary.gone    > 0) parts += str(R.string.n_part_gone, summary.gone)
        if (summary.failed  > 0) parts += str(R.string.n_part_failed, summary.failed)
        if (summary.stoppedEarly.isNotBlank()) parts += str(R.string.n_part_stopped, summary.stoppedEarly)
        val title = when {
            summary.problem.isNotBlank() -> str(R.string.n_sum_couldnt_start)
            summary.stoppedEarly.isNotBlank() -> str(R.string.n_sum_stopped)
            summary.failed > 0 -> str(R.string.n_sum_problems)
            else -> str(R.string.n_sum_complete)
        }
        notifManager.notify(
            NOTIF_SUMMARY,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(parts.joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(parts.joinToString("\n")))
                .setContentIntent(openAppIntent(this))
                .setAutoCancel(true)
                .build()
        )
    }
}
