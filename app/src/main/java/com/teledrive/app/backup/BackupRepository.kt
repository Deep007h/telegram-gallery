package com.teledrive.app.backup

import android.content.Context
import com.teledrive.app.core.AppLogger
import com.teledrive.app.data.db.dao.BackupDao
import com.teledrive.app.data.db.dao.TransferDao
import com.teledrive.app.data.db.entity.BackupDecision
import com.teledrive.app.data.db.entity.BackupSessionEntity
import com.teledrive.app.data.db.entity.BackupSessionStatus
import com.teledrive.app.data.db.entity.BackupTrigger
import com.teledrive.app.data.preferences.AppPreferences
import com.teledrive.app.telegram.ChannelRepository
import com.teledrive.app.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Orchestrates device media scanning, incremental change detection,
 * session tracking, and enqueuing backup transfers.
 */
class BackupRepository(
    private val context: Context,
    private val backupDao: BackupDao,
    private val transferDao: TransferDao,
    private val transferManager: TransferManager,
    private val channelRepository: ChannelRepository,
    private val preferences: AppPreferences
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val decideAction = DecideBackupActionUseCase()
    private var trackingJob: Job? = null

    init {
        // Recover and continue tracking active session across process restarts
        scope.launch {
            try {
                backupDao.activeSession()?.let { session ->
                    AppLogger.i(TAG, "Resuming tracker for active session: ${session.id}")
                    startTrackingSession(session.id)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to recover active backup session: ${e.message}")
            }
        }
    }

    fun observeActiveSession(): Flow<BackupSessionEntity?> =
        backupDao.observeActiveSession().distinctUntilChanged()

    fun observeLastBackupAt(): Flow<Long?> =
        backupDao.observeLastBackupAt().distinctUntilChanged()

    suspend fun startBackup(trigger: BackupTrigger = BackupTrigger.MANUAL): Result<String?> = withContext(Dispatchers.IO) {
        val active = backupDao.activeSession()
        if (active != null) {
            AppLogger.w(TAG, "Backup already running with session ID: ${active.id}")
            return@withContext Result.failure(IllegalStateException("Backup is already running"))
        }

        val targetChatId = preferences.getCachedStorageChatId().takeIf { it != 0L }
            ?: preferences.getCachedSavedMessagesChatId().takeIf { it != 0L }
            ?: try { channelRepository.getSavedMessagesChatId() } catch (_: Exception) { 0L }

        if (targetChatId == 0L) {
            AppLogger.e(TAG, "No storage chat configured for backup")
            return@withContext Result.failure(IllegalStateException("No storage channel configured. Please log in first."))
        }

        // Clean orphaned records for files that might have been removed
        try {
            backupDao.deleteOrphanedRecords()
        } catch (_: Exception) {}

        // Scan standard backup folders (DCIM/Camera and Pictures)
        val folders = StandardBackupFolder.defaultBackupFolders()
        val candidateFiles = mutableListOf<File>()

        for (folderPath in folders) {
            val root = File(folderPath)
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown()
                .onEnter { dir -> !dir.name.startsWith(".") }
                .filter { it.isFile && it.length() > 0 && !it.name.startsWith(".") }
                .forEach { candidateFiles.add(it) }
        }

        var totalBytes = 0L
        var skippedCount = 0
        val toBackup = mutableListOf<File>()

        for (file in candidateFiles) {
            val existingRecord = backupDao.recordByPath(file.absolutePath)
            val decision = decideAction.execute(file, existingRecord)
            if (decision == BackupDecision.BACKUP) {
                toBackup.add(file)
                totalBytes += file.length()
            } else {
                skippedCount++
            }
        }

        AppLogger.i(TAG, "Backup scan complete: ${toBackup.size} to backup, $skippedCount skipped, total ${totalBytes} bytes")

        if (toBackup.isEmpty()) {
            return@withContext Result.success(null)
        }

        val sessionId = UUID.randomUUID().toString()
        val session = BackupSessionEntity(
            id = sessionId,
            trigger = trigger.name,
            status = BackupSessionStatus.RUNNING.name,
            totalFiles = toBackup.size,
            completedFiles = 0,
            failedFiles = 0,
            skippedFiles = skippedCount,
            totalBytes = totalBytes,
            transferredBytes = 0L,
            startedAt = System.currentTimeMillis()
        )
        backupDao.upsertSession(session)

        // Enqueue transfers in batches
        for (file in toBackup) {
            val virtualFolder = "/Backup/${file.parentFile?.name ?: "Media"}"
            transferManager.enqueueBackupUpload(
                file = file,
                virtualPath = virtualFolder,
                chatId = targetChatId,
                sessionId = sessionId
            )
        }

        startTrackingSession(sessionId)
        Result.success(sessionId)
    }

    private fun startTrackingSession(sessionId: String) {
        trackingJob?.cancel()
        trackingJob = transferDao.observeByBackupSessionId(sessionId).onEach { transfers ->
            if (transfers.isEmpty()) return@onEach

            val currentSession = backupDao.sessionById(sessionId) ?: return@onEach
            if (currentSession.status == BackupSessionStatus.CANCELLED.name) return@onEach

            val completed = transfers.count { it.status == "COMPLETED" }
            val failed = transfers.count { it.status == "FAILED" }
            val transferredBytes = transfers.sumOf {
                if (it.status == "COMPLETED") it.fileSize else it.transferredBytes
            }
            val totalBytes = transfers.sumOf { it.fileSize }.takeIf { it > 0L } ?: currentSession.totalBytes

            val allSettled = (completed + failed) >= transfers.size && transfers.isNotEmpty()

            val newStatus = when {
                allSettled && failed > 0 -> BackupSessionStatus.COMPLETED_WITH_ERRORS.name
                allSettled -> BackupSessionStatus.COMPLETED.name
                transfers.all { it.status == "PAUSED" } -> BackupSessionStatus.PAUSED.name
                else -> BackupSessionStatus.RUNNING.name
            }

            val updatedSession = currentSession.copy(
                totalFiles = transfers.size,
                completedFiles = completed,
                failedFiles = failed,
                totalBytes = totalBytes,
                transferredBytes = transferredBytes,
                status = newStatus,
                completedAt = if (allSettled) System.currentTimeMillis() else currentSession.completedAt
            )
            backupDao.updateSession(updatedSession)

            if (allSettled) {
                AppLogger.i(TAG, "Backup session $sessionId completed: $completed succeeded, $failed failed")
                trackingJob?.cancel()
                trackingJob = null
            }
        }.launchIn(scope)
    }

    suspend fun cancelBackup(sessionId: String) = withContext(Dispatchers.IO) {
        val transfers = transferDao.getByBackupSessionId(sessionId)
        for (t in transfers) {
            if (t.status != "COMPLETED" && t.status != "FAILED" && t.status != "CANCELLED") {
                transferManager.cancelTransfer(t.transferId)
            }
        }
        backupDao.setSessionStatus(sessionId, BackupSessionStatus.CANCELLED.name, System.currentTimeMillis())
        trackingJob?.cancel()
        trackingJob = null
    }

    companion object {
        private const val TAG = "BackupRepository"
    }
}
