package com.firebolt141.ubertrag.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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

class CopyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notifManager: NotificationManager
    private var copyJob: Job? = null

    companion object {
        const val CHANNEL_ID     = "copy_progress"
        const val NOTIF_ID       = 1
        const val NOTIF_SUMMARY  = 2
        const val ACTION_START   = "START_COPY"
        const val EXTRA_FROM_MS  = "from_ms"
        const val EXTRA_TO_MS    = "to_ms"

        val copyProgress = MutableStateFlow<CopyProgress?>(null)
    }

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(NotificationManager::class.java)
        notifManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Copy Progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            if (copyJob?.isActive == true) return START_NOT_STICKY

            val fromMs = intent.getLongExtra(EXTRA_FROM_MS, 0L)
            val toMs   = intent.getLongExtra(EXTRA_TO_MS,   0L)

            val initial = buildProgressNotif("Starting copy…", 0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, initial)
            }

            copyJob = scope.launch {
                try {
                    val summary = SyncRepository(applicationContext).copyPending(
                        fromMs = fromMs,
                        toMs   = toMs,
                    ) { done, total, name, speedMBps ->
                        val p = CopyProgress(done, total, name, speedMBps)
                        copyProgress.value = p
                        notifManager.notify(NOTIF_ID, buildProgressNotif(
                            if (name.isNotEmpty()) "Copying: $name" else "Done",
                            done, total
                        ))
                    }
                    if (summary.copied + summary.skipped + summary.failed > 0) {
                        showSummaryNotification(summary)
                    }
                } finally {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        copyProgress.value = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotif(text: String, done: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Übertrag")
            .setContentText(text)
            .apply {
                if (total > 0) setProgress(total, done, false)
                else setProgress(0, 0, true)
            }
            .setOngoing(true)
            .build()

    private fun showSummaryNotification(summary: CopySummary) {
        val parts = mutableListOf("${summary.copied} copied")
        if (summary.skipped > 0) parts += "${summary.skipped} skipped (already on drive)"
        if (summary.failed  > 0) parts += "${summary.failed} failed"
        notifManager.notify(
            NOTIF_SUMMARY,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Sync complete")
                .setContentText(parts.joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(parts.joinToString("\n")))
                .setAutoCancel(true)
                .build()
        )
    }
}
