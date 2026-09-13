package com.teledrive.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackupTrigger {
    MANUAL,
    SCHEDULED,
    EXTERNAL
}

enum class BackupSessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}

enum class BackupDecision {
    BACKUP,
    SKIP_UNCHANGED,
    SKIP_EXCLUDED,
    SKIP_TOO_LARGE
}

/**
 * One row per source path that has ever been backed up. Used for incremental
 * backup decisions: a file is re-uploaded only when size or hash changed.
 */
@Entity(
    tableName = "backup_records",
    indices = [
        Index(value = ["source_path"], unique = true),
        Index("content_hash")
    ]
)
data class BackupRecordEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "file_id") val fileId: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "backed_up_at") val backedUpAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "backup_sessions",
    indices = [
        Index("started_at"),
        Index("status")
    ]
)
data class BackupSessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "trigger") val trigger: String, // MANUAL, SCHEDULED, EXTERNAL
    @ColumnInfo(name = "status") val status: String = BackupSessionStatus.RUNNING.name,
    @ColumnInfo(name = "total_files") val totalFiles: Int = 0,
    @ColumnInfo(name = "completed_files") val completedFiles: Int = 0,
    @ColumnInfo(name = "failed_files") val failedFiles: Int = 0,
    @ColumnInfo(name = "skipped_files") val skippedFiles: Int = 0,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0L,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long = 0L,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)
