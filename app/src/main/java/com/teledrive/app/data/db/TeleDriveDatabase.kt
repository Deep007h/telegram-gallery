package com.teledrive.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.teledrive.app.data.db.dao.BackupDao
import com.teledrive.app.data.db.dao.FileDao
import com.teledrive.app.data.db.dao.FolderDao
import com.teledrive.app.data.db.dao.TransferDao
import com.teledrive.app.data.db.entity.BackupRecordEntity
import com.teledrive.app.data.db.entity.BackupSessionEntity
import com.teledrive.app.data.db.entity.FileEntity
import com.teledrive.app.data.db.entity.FolderEntity
import com.teledrive.app.data.db.entity.TransferEntity

@Database(
    entities = [
        FileEntity::class,
        FolderEntity::class,
        TransferEntity::class,
        BackupRecordEntity::class,
        BackupSessionEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun folderDao(): FolderDao
    abstract fun transferDao(): TransferDao
    abstract fun backupDao(): BackupDao

    companion object {
        @Volatile private var INSTANCE: TeleDriveDatabase? = null
        fun getInstance(context: Context): TeleDriveDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, TeleDriveDatabase::class.java, "teledrive.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
