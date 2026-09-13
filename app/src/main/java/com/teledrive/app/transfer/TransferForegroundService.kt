package com.teledrive.app.transfer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.teledrive.app.TeleDriveApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TransferForegroundService : Service() {

    private val job = SupervisorJob()
    // DB Flows must not collect on Main: getActiveTransfers maps the whole
    // transfer table per emission (was Dispatchers.Main → frame drops).
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate() {
        super.onCreate()
        
        val notificationManager = TransferNotificationManager()
        val notification = notificationManager.createNotification(
            context = this,
            fileName = "TeleDrive Background Sync",
            progress = 0,
            isUpload = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                1001,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } else {
            startForeground(1001, notification)
        }

        val app = application as TeleDriveApplication
        app.transferManager.getActiveTransfers().onEach { activeList ->
            if (activeList.isEmpty()) {
                stopSelf()
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Cancel action previously sent a bare CANCEL_TRANSFER
        // with no transfer ID, so the service could never act on it (dead button).
        // WorkManager owns cancellation now (TransferManager.cancelTransfer sets
        // CANCELLED + cancelUniqueWork); just stop the service shell here.
        if (intent?.action == "CANCEL_TRANSFER") {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
