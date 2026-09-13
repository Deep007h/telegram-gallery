package com.teledrive.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.teledrive.app.data.db.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: FileEntity): Long

    @Update
    suspend fun update(file: FileEntity)

    @Delete
    suspend fun delete(file: FileEntity)

    @Query("DELETE FROM files WHERE telegram_chat_id = :chatId AND telegram_message_id = :messageId")
    suspend fun deleteByMessageId(chatId: Long, messageId: Long)

    @Query("SELECT * FROM files WHERE virtual_path = :virtualPath LIMIT 1")
    suspend fun getByPath(virtualPath: String): FileEntity?

    @Query("SELECT * FROM files WHERE telegram_chat_id = :chatId AND telegram_message_id = :messageId LIMIT 1")
    suspend fun getByMessageId(chatId: Long, messageId: Long): FileEntity?

    @Query("SELECT * FROM files WHERE parent_folder_id = :parentFolderId OR (parent_folder_id IS NULL AND :parentFolderId IS NULL)")
    fun getByParentFolder(parentFolderId: Long?): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE virtual_path LIKE :pathPrefix || '/%' AND telegram_chat_id = :chatId")
    fun getFilesInPath(pathPrefix: String, chatId: Long): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE file_name LIKE '%' || :query || '%'")
    fun searchByName(query: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files")
    fun getAll(): Flow<List<FileEntity>>

    @Query("SELECT COUNT(*) FROM files")
    fun getCount(): Flow<Int>

    @Query("SELECT SUM(file_size) FROM files")
    fun getTotalSize(): Flow<Long>

    @Query("SELECT * FROM files WHERE mime_type LIKE :prefix || '%'")
    fun getByMimeTypePrefix(prefix: String): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertByMessageId(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileEntity>)

    @Query("SELECT * FROM files")
    suspend fun getAllFilesList(): List<FileEntity>

    @Query("SELECT * FROM files WHERE file_name = :fileName AND file_size = :fileSize LIMIT 1")
    suspend fun getByNameAndSize(fileName: String, fileSize: Long): FileEntity?

    @Query("SELECT * FROM files WHERE file_id = :id LIMIT 1")
    suspend fun getById(id: Long): FileEntity?

    @Query("DELETE FROM files")
    suspend fun clearAll()

    @Query("SELECT * FROM files WHERE telegram_chat_id = :chatId AND file_name = :fileName AND file_size = :fileSize LIMIT 1")
    suspend fun getByChatNameAndSize(chatId: Long, fileName: String, fileSize: Long): FileEntity?

    @Query("UPDATE files SET telegram_file_id = :fileId, thumbnail_file_id = :thumbId WHERE file_id = :localFileId")
    suspend fun updateFileIds(localFileId: Long, fileId: Int, thumbId: Int?)

    @Query("DELETE FROM files WHERE file_id NOT IN (SELECT MIN(file_id) FROM files GROUP BY telegram_chat_id, telegram_message_id)")
    suspend fun deleteDuplicates()

    @Query("DELETE FROM files WHERE file_id NOT IN (SELECT MIN(file_id) FROM files GROUP BY file_name, file_size)")
    suspend fun deleteDuplicatesByNameAndSize()

    @Query("DELETE FROM files WHERE telegram_chat_id != :savedMessagesChatId AND telegram_chat_id != 0")
    suspend fun deleteNonSavedMessages(savedMessagesChatId: Long)
}
