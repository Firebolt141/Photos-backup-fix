package com.firebolt141.photosync

import android.app.Application
import androidx.work.*
import com.firebolt141.photosync.service.ScanWorker
import java.util.concurrent.TimeUnit

class PhotoSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleScan()
    }

    private fun scheduleScan() {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "media_scan",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
