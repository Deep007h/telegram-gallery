package com.teledrive.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.core.AppLogger
import com.teledrive.app.data.db.entity.BackupTrigger
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker for scheduled periodic media backup.
 */
class ScheduledBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppLogger.i(TAG, "Starting scheduled backup worker...")
        val app = applicationContext as? TeleDriveApplication ?: return Result.failure()

        // Verify TDLib is logged in and ready
        if (!app.tdLibManager.isReady) {
            val authState = app.tdLibManager.authState.value
            AppLogger.w(TAG, "TDLib not ready ($authState), retrying scheduled backup later")
            return Result.retry()
        }

        val result = app.backupRepository.startBackup(BackupTrigger.SCHEDULED)
        return if (result.isSuccess) {
            AppLogger.i(TAG, "Scheduled backup initiated or completed successfully: ${result.getOrNull()}")
            Result.success()
        } else {
            val err = result.exceptionOrNull()
            if (err is IllegalStateException && err.message?.contains("running", ignoreCase = true) == true) {
                AppLogger.i(TAG, "Backup already running, completing worker cleanly")
                Result.success()
            } else {
                AppLogger.w(TAG, "Scheduled backup failed: ${err?.message}")
                Result.retry()
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "scheduled_backup"
        private const val TAG = "ScheduledBackupWorker"

        fun schedule(
            context: Context,
            repeatIntervalHours: Long = 24,
            wifiOnly: Boolean = false
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
                repeatIntervalHours,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            AppLogger.i(TAG, "Scheduled backup registered (every ${repeatIntervalHours}h, wifiOnly=$wifiOnly)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            AppLogger.i(TAG, "Scheduled backup cancelled")
        }
    }
}
