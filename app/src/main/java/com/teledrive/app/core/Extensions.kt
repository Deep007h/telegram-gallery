package com.teledrive.app.core

import android.webkit.MimeTypeMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.toFormattedSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    // Coerce: files > TB would otherwise index out of bounds and crash binds.
    val digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.2f %s", this / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun Long.toFormattedDate(): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(this))
}

fun Long.toRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
        diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(this))
        }
    }
}

fun String.toFileType(): FileType {
    val mimeType = this.lowercase()
    return when {
        mimeType.startsWith("image/") -> FileType.IMAGE
        mimeType.startsWith("video/") -> FileType.VIDEO
        mimeType.startsWith("audio/") -> FileType.AUDIO
        mimeType.startsWith("application/pdf") || 
        mimeType.startsWith("application/msword") || 
        mimeType.startsWith("application/vnd.openxmlformats-officedocument") || 
        mimeType.startsWith("text/plain") -> FileType.DOCUMENT
        mimeType.startsWith("application/zip") || 
        mimeType.startsWith("application/x-rar-compressed") || 
        mimeType.startsWith("application/x-7z-compressed") ||
        mimeType.startsWith("application/x-tar") -> FileType.ARCHIVE
        mimeType.startsWith("text/") || 
        mimeType.startsWith("application/json") || 
        mimeType.startsWith("application/xml") -> FileType.CODE
        else -> FileType.OTHER
    }
}

fun String.getFileExtension(): String {
    val lastDot = this.lastIndexOf('.')
    return if (lastDot != -1) this.substring(lastDot + 1).lowercase() else ""
}

fun String.getMimeType(): String {
    val extension = this.getFileExtension()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}
