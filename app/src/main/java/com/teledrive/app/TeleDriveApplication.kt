package com.teledrive.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.teledrive.app.core.Constants
import com.teledrive.app.data.db.TeleDriveDatabase
import com.teledrive.app.data.preferences.AppPreferences
import com.teledrive.app.data.repository.LocalRepository
import com.teledrive.app.telegram.AuthRepository
import com.teledrive.app.telegram.ChannelRepository
import com.teledrive.app.telegram.FileRepository
import com.teledrive.app.telegram.MetadataParser
import com.teledrive.app.telegram.TdLibManager
import com.teledrive.app.transfer.TransferManager

import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.teledrive.app.core.ThumbnailCacheManager
import kotlinx.coroutines.launch
import java.io.File

class TeleDriveApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(com.teledrive.app.core.MediaStoreThumbnailFetcher.Factory(this@TeleDriveApplication))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.50) // 50% RAM memory cache for instant scrolling back & forth
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_disk_cache"))
                    .maxSizeBytes(500L * 1024 * 1024)
                    .build()
            }
            .interceptorDispatcher(com.teledrive.app.core.AppDispatchers.ImageLoader)
            .fetcherDispatcher(com.teledrive.app.core.AppDispatchers.ImageLoader)
            .decoderDispatcher(com.teledrive.app.core.AppDispatchers.ImageLoader)
            .transformationDispatcher(com.teledrive.app.core.AppDispatchers.ImageLoader)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .networkCachePolicy(coil.request.CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .allowHardware(true)
            .crossfade(false) // Zero-latency 1-frame instant rendering
            .build()
    }

    lateinit var database: TeleDriveDatabase
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var tdLibManager: TdLibManager
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var fileRepository: FileRepository
        private set

    lateinit var channelRepository: ChannelRepository
        private set

    lateinit var localRepository: LocalRepository
        private set

    lateinit var transferManager: TransferManager
        private set

    lateinit var backupRepository: com.teledrive.app.backup.BackupRepository
        private set

    lateinit var deviceMediaRepository: com.teledrive.app.data.repository.DeviceMediaRepository
        private set

    lateinit var peopleRepository: com.teledrive.app.data.repository.PeopleRepository
        private set

    lateinit var thumbnailCacheManager: ThumbnailCacheManager
        private set

    lateinit var otaUpdateManager: com.teledrive.app.core.ota.OtaUpdateManager
        private set

    lateinit var trashManager: com.teledrive.app.data.trash.TrashManager
        private set

    val fastThumbnailCacheManager = com.teledrive.app.core.FastThumbnailCacheManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        com.teledrive.app.core.AppLogger.init(this)
        createNotificationChannel()

        database = TeleDriveDatabase.getInstance(this)
        preferences = AppPreferences(this)

        tdLibManager = TdLibManager()
        val cachedApiId = preferences.getCachedApiId()
        val cachedApiHash = preferences.getCachedApiHash()
        if (cachedApiId > 0 && cachedApiHash.isNotBlank()) {
            tdLibManager.setApiCredentials(cachedApiId, cachedApiHash)
        }
        val cachedSavedId = preferences.getCachedSavedMessagesChatId()
        if (cachedSavedId != 0L) {
            tdLibManager.cachedSavedMessagesChatId = cachedSavedId
        }
        val cachedPhoto = preferences.getCachedTelegramProfilePhotoPath()
        if (cachedPhoto.isNotBlank()) {
            tdLibManager.setCachedProfilePhotoPath(cachedPhoto)
        }
        tdLibManager.initialize(this)

        thumbnailCacheManager = ThumbnailCacheManager(this, tdLibManager)
        otaUpdateManager = com.teledrive.app.core.ota.OtaUpdateManager(this, preferences)
        authRepository = AuthRepository(tdLibManager)
        fileRepository = FileRepository(tdLibManager)
        channelRepository = ChannelRepository(tdLibManager, preferences)
        deviceMediaRepository = com.teledrive.app.data.repository.DeviceMediaRepository(this)
        peopleRepository = com.teledrive.app.data.repository.PeopleRepository(this, tdLibManager)
        localRepository = LocalRepository(
            fileDao = database.fileDao(),
            folderDao = database.folderDao(),
            transferDao = database.transferDao(),
            tdLibManager = tdLibManager,
            metadataParser = MetadataParser,
            preferences = preferences
        )
        transferManager = TransferManager(this, database.transferDao())
        backupRepository = com.teledrive.app.backup.BackupRepository(
            context = this,
            backupDao = database.backupDao(),
            transferDao = database.transferDao(),
            transferManager = transferManager,
            channelRepository = channelRepository,
            preferences = preferences
        )
        trashManager = com.teledrive.app.data.trash.TrashManager(this, localRepository, tdLibManager)

        // Schedule periodic backup in background
        com.teledrive.app.backup.ScheduledBackupWorker.schedule(this)

        // Eager Pre-warming of HyperOS-grade thumbnail cache map
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            fastThumbnailCacheManager.preWarmCacheMap(this@TeleDriveApplication)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        localRepository.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "File Transfers"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = "Upload and download progress notifications"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: TeleDriveApplication
            private set
    }
}
