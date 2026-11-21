package com.arin.civicreport.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.arin.civicreport.model.Attachment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStore(private val context: Context) {

    private fun mediaDir(): File =
        File(context.filesDir, "media").also { if (!it.exists()) it.mkdirs() }

    fun newPhotoFile(): File =
        File(mediaDir(), "photo_${System.currentTimeMillis()}.jpg")

    fun newVideoFile(): File =
        File(mediaDir(), "video_${System.currentTimeMillis()}.mp4")

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Copies picked gallery content into the app folder so the URI never expires. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                mime.startsWith("video/") -> "mp4"
                mime == "image/png" -> "png"
                else -> "jpg"
            }
            val target = File(mediaDir(), "import_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }.getOrNull()
    }

    /**
     * Keeps long-term read access to a file the person picked, of any type or size.
     * The file is not copied, so a 4 GB video costs no extra space.
     */
    fun attach(uri: Uri): Attachment {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val z = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
                }
            }
        }
        val mime = context.contentResolver.getType(uri)
            ?: name?.substringAfterLast('.', "")?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "application/octet-stream"
        return Attachment(
            uri = uri.toString(),
            name = name ?: uri.lastPathSegment ?: "file",
            mimeType = mime,
            sizeBytes = size
        )
    }

    fun release(attachment: Attachment) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(attachment.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun deleteFile(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
