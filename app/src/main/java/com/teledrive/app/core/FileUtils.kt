package com.teledrive.app.core

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

enum class FileType { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, OTHER }

object FileUtils {

    fun getFileInfo(context: Context, uri: Uri): Triple<String, Long, String> {
        var name = "unknown"
        var size = 0L
        var mimeType = "*/*"

        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: "unknown"
                    }
                    if (sizeIndex != -1) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
            mimeType = context.contentResolver.getType(uri) ?: "*/*"
        } else if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            name = file.name
            size = file.length()
            mimeType = name.getMimeType()
        }

        return Triple(name, size, mimeType)
    }

    fun copyToTemp(context: Context, uri: Uri, fileName: String): String {
        val cacheDir = context.cacheDir
        // Unique temp name: the old File(cacheDir, fileName) overwrote concurrent
        // uploads of same-named files (race + wrong bytes uploaded).
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}_${(0..99999).random()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return tempFile.absolutePath
    }

    fun getDownloadDir(context: Context): File {
        return try {
            val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Constants.DOWNLOAD_DIR)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            if (downloadDir.canWrite()) {
                downloadDir
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            }
        } catch (e: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        }
    }

    fun scanFile(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }

    fun openFile(context: Context, filePath: String, fileName: String) {
        val file = File(filePath)
        if (!file.exists()) {
            // Check in standard downloads directory as well
            val altFile = File(getDownloadDir(context), fileName)
            if (altFile.exists()) {
                openFileWithIntent(context, altFile, fileName)
                return
            }
            Toast.makeText(context, "File not found on device", Toast.LENGTH_SHORT).show()
            return
        }
        openFileWithIntent(context, file, fileName)
    }

    private fun openFileWithIntent(context: Context, file: File, fileName: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mimeType = fileName.getMimeType().ifBlank { "*/*" }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open $fileName with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(genericIntent, "Open with...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (ex: Exception) {
                Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getFileIcon(mimeType: String): ImageVector {
        return when (mimeType.toFileType()) {
            FileType.IMAGE -> Icons.Outlined.Image
            FileType.VIDEO -> Icons.Outlined.Videocam
            FileType.AUDIO -> Icons.Outlined.MusicNote
            FileType.DOCUMENT -> {
                if (mimeType.contains("pdf")) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description
            }
            FileType.ARCHIVE -> Icons.Outlined.FolderZip
            FileType.CODE -> Icons.Outlined.Code
            FileType.OTHER -> Icons.Outlined.InsertDriveFile
        }
    }

    fun deleteTempFiles(context: Context) {
        // Only delete our upload_* temps: the old version deleted every file in
        // cacheDir, wiping Coil's disk cache, profile photos and OTA staging.
        context.cacheDir.listFiles()?.forEach { file ->
            try {
                if (file.isFile && file.name.startsWith("upload_")) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }
}
