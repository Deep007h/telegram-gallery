package com.teledrive.app.backup

import com.teledrive.app.data.db.entity.BackupDecision
import com.teledrive.app.data.db.entity.BackupRecordEntity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Incremental backup decision for a candidate file.
 * Compares file size + modified time first.
 * If size matches but modified time changed, computes SHA-256 hash.
 * Touched-but-identical files are not re-uploaded.
 */
class DecideBackupActionUseCase {

    fun execute(
        file: File,
        existingRecord: BackupRecordEntity?,
        maxFileSizeBytes: Long = 2000L * 1024 * 1024 // 2 GB default
    ): BackupDecision {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return BackupDecision.SKIP_UNCHANGED
        }

        // Hidden files or .nomedia
        if (file.name.startsWith(".") || file.name.equals(".nomedia", ignoreCase = true)) {
            return BackupDecision.SKIP_EXCLUDED
        }

        if (maxFileSizeBytes in 1 until file.length()) {
            return BackupDecision.SKIP_TOO_LARGE
        }

        if (existingRecord != null) {
            val sameSizeAndTime = existingRecord.sizeBytes == file.length() &&
                    existingRecord.modifiedAt == file.lastModified()
            if (sameSizeAndTime) {
                return BackupDecision.SKIP_UNCHANGED
            }

            if (existingRecord.sizeBytes == file.length() && !existingRecord.contentHash.isNullOrBlank()) {
                val currentHash = computeSha256(file)
                if (currentHash != null && currentHash == existingRecord.contentHash) {
                    return BackupDecision.SKIP_UNCHANGED
                }
            }
        }

        return BackupDecision.BACKUP
    }

    companion object {
        fun computeSha256(file: File): String? = try {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
