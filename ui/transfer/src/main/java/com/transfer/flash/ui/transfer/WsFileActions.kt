package com.transfer.flash.ui.transfer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.transfer.flash.core.transfer.model.WsTransferItem
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object WsFileActions {

    private val KNOWN_MIME_TYPES = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "txt" to "text/plain",
        "html" to "text/html",
        "json" to "application/json",
        "xml" to "application/xml",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun resolveMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return "*/*"
        KNOWN_MIME_TYPES[extension]?.let { return it }
        return runCatching {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
        }.getOrNull() ?: "*/*"
    }

    fun resolveFile(context: Context, transfer: WsTransferItem): File? {
        if (!transfer.filePath.isNullOrBlank()) {
            val explicit = File(transfer.filePath!!)
            if (explicit.exists() && explicit.canRead()) return explicit
        }
        val dir = File(context.filesDir, "ws-received")
        if (dir.exists()) {
            val direct = File(dir, transfer.fileName)
            if (direct.exists() && direct.canRead()) return direct
            val detailName = transfer.detail.substringAfter("ws-received/", "").trim()
            if (detailName.isNotBlank()) {
                val detailFile = File(dir, detailName)
                if (detailFile.exists() && detailFile.canRead()) return detailFile
            }
        }
        val rootFile = File(context.filesDir, transfer.fileName)
        if (rootFile.exists() && rootFile.canRead()) return rootFile
        return null
    }

    fun openTransfer(context: Context, transfer: WsTransferItem) {
        val file = resolveFile(context, transfer)
        if (file == null || !file.exists() || !file.canRead()) {
            Toast.makeText(context, "File '${transfer.fileName}' not found in app storage", Toast.LENGTH_SHORT).show()
            return
        }
        openFile(context, file.absolutePath, transfer.fileName)
    }

    fun shareTransfer(context: Context, transfer: WsTransferItem) {
        val file = resolveFile(context, transfer)
        if (file == null || !file.exists() || !file.canRead()) {
            Toast.makeText(context, "File '${transfer.fileName}' not found in app storage", Toast.LENGTH_SHORT).show()
            return
        }
        shareFile(context, file.absolutePath, transfer.fileName)
    }

    fun openFile(context: Context, filePath: String, fileName: String? = null) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(context, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = resolveMimeType(fileName ?: file.name)
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri(file.name, contentUri)
            }
            val chooser = Intent.createChooser(intent, "Open ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            tryFallbackView(context, file)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryFallbackView(context: Context, file: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri(file.name, contentUri)
            }
            val fallbackChooser = Intent.createChooser(fallbackIntent, "Open ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(fallbackChooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No application found to open ${file.name}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareFile(context: Context, filePath: String, fileName: String? = null) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(context, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = resolveMimeType(fileName ?: file.name)
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(file.name, contentUri)
            }
            val chooser = Intent.createChooser(intent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportFileToUri(context: Context, sourceFilePath: String, destinationUri: Uri) {
        val sourceFile = File(sourceFilePath)
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            Toast.makeText(context, "Source file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val resolver = context.contentResolver
            val outputStream = resolver.openOutputStream(destinationUri)
                ?: throw IOException("Cannot open destination")
            FileInputStream(sourceFile).use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "Saved ${sourceFile.name} successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
