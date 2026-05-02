package com.firebolt141.photosync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.firebolt141.photosync.R
import com.firebolt141.photosync.repository.SyncRepository
import kotlinx.coroutines.*

class CopyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notifManager: NotificationManager

    companion object {
        const val CHANNEL_ID   = "copy_progress"
        const val NOTIF_ID     = 1
        const val ACTION_START = "START_COPY"
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
            val notif = buildNotif("Starting copy…", 0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }

            scope.launch {
                SyncRepository(applicationContext).copyPending { done, total, name ->
                    notifManager.notify(
                        NOTIF_ID,
                        buildNotif(
                            if (name.isNotEmpty()) "Copying: $name" else "Done",
                            done, total
                        )
                    )
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotif(text: String, done: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Photo Sync")
            .setContentText(text)
            .apply {
                if (total > 0) setProgress(total, done, false)
                else setProgress(0, 0, true)
            }
            .setOngoing(true)
            .build()
}
