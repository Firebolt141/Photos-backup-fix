package com.firebolt141.photosync.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firebolt141.photosync.repository.SyncRepository

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        SyncRepository(applicationContext).scanMedia()
        Result.success()
    } catch (_: SecurityException) {
        Result.failure()
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
