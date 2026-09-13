package com.teledrive.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.teledrive.app.data.db.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity): Long

    @Update
    suspend fun update(transfer: TransferEntity)

    @Delete
    suspend fun delete(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transfer_id = :transferId LIMIT 1")
    suspend fun getById(transferId: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE status = 'PENDING'")
    fun getPending(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status = 'IN_PROGRESS'")
    fun getInProgress(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status = 'COMPLETED'")
    fun getCompleted(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status = 'FAILED'")
    fun getFailed(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers")
    fun getAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status IN (:statuses)")
    fun getByStatusFlow(statuses: List<String>): Flow<List<TransferEntity>>

    @Query("UPDATE transfers SET transferred_bytes = :bytes, updated_at = :updatedAt WHERE transfer_id = :transferId")
    suspend fun updateProgress(transferId: Long, bytes: Long, updatedAt: Long)

    @Query("UPDATE transfers SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE transfer_id = :transferId")
    suspend fun updateStatus(transferId: Long, status: String, errorMessage: String?, updatedAt: Long)

    @Query("DELETE FROM transfers WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("SELECT * FROM transfers WHERE status = 'PENDING'")
    suspend fun getPendingList(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE type = 'UPLOAD' AND file_name = :fileName AND file_size = :fileSize AND status IN ('PENDING', 'IN_PROGRESS') LIMIT 1")
    suspend fun getActiveUpload(fileName: String, fileSize: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE backup_session_id = :sessionId")
    suspend fun getByBackupSessionId(sessionId: String): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE backup_session_id = :sessionId")
    fun observeByBackupSessionId(sessionId: String): Flow<List<TransferEntity>>

    @Query("DELETE FROM transfers")
    suspend fun clearAll()
}
