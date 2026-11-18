package com.fullshare.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Sharer {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val FACEBOOK = "com.facebook.katana"
    const val FACEBOOK_LITE = "com.facebook.lite"

    /** WhatsApp accepts at most this many media items in one share. */
    const val MAX_PER_SHARE = 30

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Shares all [files] in one go. WhatsApp turns multiple videos into consecutive
     * status updates that play back-to-back.
     * @param pkg target app, or null for the system share sheet.
     * @param openStatusDirectly asks WhatsApp to open "My status" directly (unofficial hint;
     *        if WhatsApp ignores it, "My status" is at the top of its contact picker).
     */
    fun share(
        activity: Activity,
        files: List<File>,
        pkg: String?,
        openStatusDirectly: Boolean = false,
    ): Boolean {
        if (files.isEmpty()) return false
        val uris = ArrayList(files.map { uriFor(activity, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "video/mp4"
        val clip = ClipData.newRawUri("video", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (openStatusDirectly) intent.putExtra("jid", "status@broadcast")

        return try {
            if (pkg != null) {
                intent.setPackage(pkg)
                activity.startActivity(intent)
            } else {
                activity.startActivity(Intent.createChooser(intent, "Share video"))
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Saves files to Movies/FullStatusShare so they appear in the Gallery. */
    fun saveToGallery(context: Context, files: List<File>): Int {
        var saved = 0
        for (f in files) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "FullShare_${System.currentTimeMillis()}_${f.name}")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FullStatusShare")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val item = context.contentResolver.insert(collection, values) ?: continue
                context.contentResolver.openOutputStream(item)?.use { out ->
                    f.inputStream().use { it.copyTo(out, 1 shl 16) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(item, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FullStatusShare")
                dir.mkdirs()
                val dest = File(dir, "FullShare_${System.currentTimeMillis()}_${f.name}")
                f.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            }
            saved++
        }
        return saved
    }
}
