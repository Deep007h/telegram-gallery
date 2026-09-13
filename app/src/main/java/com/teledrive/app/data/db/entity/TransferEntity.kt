package com.teledrive.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["status"]),
        Index(value = ["created_at"]),
        Index(value = ["backup_session_id"])
    ]
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "transfer_id") val transferId: Long = 0,
    @ColumnInfo(name = "type") val type: String, // "UPLOAD" or "DOWNLOAD"
    @ColumnInfo(name = "status") val status: String = "PENDING", // PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    @ColumnInfo(name = "local_file_path") val localFilePath: String,
    @ColumnInfo(name = "virtual_path") val virtualPath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long = 0,
    @ColumnInfo(name = "telegram_chat_id") val telegramChatId: Long,
    @ColumnInfo(name = "telegram_message_id") val telegramMessageId: Long? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "backup_session_id") val backupSessionId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
