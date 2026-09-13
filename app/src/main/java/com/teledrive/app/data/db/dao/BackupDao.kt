package com.teledrive.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.teledrive.app.data.db.entity.BackupRecordEntity
import com.teledrive.app.data.db.entity.BackupSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: BackupSessionEntity)

    @Update
    suspend fun updateSession(session: BackupSessionEntity)

    @Query("SELECT * FROM backup_sessions WHERE id = :id")
    suspend fun sessionById(id: String): BackupSessionEntity?

    @Query("SELECT * FROM backup_sessions ORDER BY started_at DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int): Flow<List<BackupSessionEntity>>

    @Query("SELECT * FROM backup_sessions WHERE status IN ('RUNNING', 'PAUSED') ORDER BY started_at DESC LIMIT 1")
    fun observeActiveSession(): Flow<BackupSessionEntity?>

    @Query(
        """SELECT MAX(completed_at) FROM backup_sessions
            WHERE completed_at IS NOT NULL
              AND status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')"""
    )
    fun observeLastBackupAt(): Flow<Long?>

    @Query("SELECT * FROM backup_sessions WHERE status IN ('RUNNING', 'PAUSED') ORDER BY started_at DESC LIMIT 1")
    suspend fun activeSession(): BackupSessionEntity?

    @Query("UPDATE backup_sessions SET status = :status, completed_at = :completedAt WHERE id = :id")
    suspend fun setSessionStatus(id: String, status: String, completedAt: Long?)

    @Query("UPDATE backup_sessions SET completed_files = completed_files + 1, transferred_bytes = transferred_bytes + :bytes WHERE id = :id")
    suspend fun incrementCompleted(id: String, bytes: Long)

    @Query("UPDATE backup_sessions SET failed_files = failed_files + 1 WHERE id = :id")
    suspend fun incrementFailed(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: BackupRecordEntity)

    @Query("SELECT * FROM backup_records WHERE source_path = :sourcePath")
    suspend fun recordByPath(sourcePath: String): BackupRecordEntity?

    @Query("SELECT * FROM backup_records WHERE content_hash = :contentHash LIMIT 1")
    suspend fun recordByHash(contentHash: String): BackupRecordEntity?

    @Query("DELETE FROM backup_records WHERE file_id IN (:fileIds)")
    suspend fun deleteRecordsForFiles(fileIds: List<Long>)

    @Query("DELETE FROM backup_records WHERE file_id IS NULL OR file_id NOT IN (SELECT file_id FROM files)")
    suspend fun deleteOrphanedRecords(): Int
}
