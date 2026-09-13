package com.teledrive.app.backup

import android.os.Environment
import java.io.File

/**
 * Well-known media folders offered as backup sources.
 */
enum class StandardBackupFolder(
    val displayName: String,
    private val directory: String
) {
    CAMERA("Camera", Environment.DIRECTORY_DCIM),
    PICTURES("Pictures", Environment.DIRECTORY_PICTURES),
    MOVIES("Movies", Environment.DIRECTORY_MOVIES),
    DOWNLOADS("Downloads", Environment.DIRECTORY_DOWNLOADS),
    DOCUMENTS("Documents", Environment.DIRECTORY_DOCUMENTS);

    val file: File
        get() = Environment.getExternalStoragePublicDirectory(directory)

    val path: String
        get() = file.absolutePath

    companion object {
        fun defaultBackupFolders(): Set<String> {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return setOfNotNull(
                dcim.absolutePath.takeIf { dcim.exists() },
                pictures.absolutePath.takeIf { pictures.exists() }
            )
        }
    }
}
