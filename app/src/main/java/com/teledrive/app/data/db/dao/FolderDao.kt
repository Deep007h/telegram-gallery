package com.teledrive.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.teledrive.app.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE virtual_path = :virtualPath LIMIT 1")
    suspend fun getByPath(virtualPath: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parent_folder_id = :parentFolderId OR (parent_folder_id IS NULL AND :parentFolderId IS NULL)")
    fun getByParentFolder(parentFolderId: Long?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parent_folder_id = :parentFolderId")
    fun getChildren(parentFolderId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parent_folder_id IS NULL AND telegram_chat_id = :chatId")
    fun getRootFolders(chatId: Long): Flow<List<FolderEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE virtual_path = :virtualPath AND telegram_chat_id = :chatId)")
    suspend fun exists(virtualPath: String, chatId: Long): Boolean

    @Query("SELECT * FROM folders")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAllFoldersList(): List<FolderEntity>

    @Query("DELETE FROM folders WHERE virtual_path = :virtualPath")
    suspend fun deleteByPath(virtualPath: String)

    @Query("DELETE FROM folders WHERE virtual_path = :virtualPath AND telegram_chat_id = :chatId")
    suspend fun deleteByPathAndChat(virtualPath: String, chatId: Long)

    @Query("SELECT * FROM folders WHERE virtual_path = :virtualPath AND telegram_chat_id = :chatId LIMIT 1")
    suspend fun getByPathAndChat(virtualPath: String, chatId: Long): FolderEntity?

    @Query("DELETE FROM folders")
    suspend fun clearAll()

    @Query("UPDATE folders SET virtual_path = REPLACE(virtual_path, :oldPrefix, :newPrefix) WHERE virtual_path LIKE :oldPrefix || '%'")
    suspend fun updatePathPrefix(oldPrefix: String, newPrefix: String)

    @Query("DELETE FROM folders WHERE telegram_chat_id != :savedMessagesChatId AND telegram_chat_id != 0")
    suspend fun deleteNonSavedMessages(savedMessagesChatId: Long)
}
