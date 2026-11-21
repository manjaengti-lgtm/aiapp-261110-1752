// === ABOUT: Saved photos and videos with their location, kept for later complaints; also copied to the phone gallery ===
package com.arin.civicreport.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore as AndroidMediaStore
import com.arin.civicreport.model.ReportLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

@Serializable
data class EvidenceItem(
    val id: String,
    val path: String,
    val isVideo: Boolean,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val address: String = "",
    val takenAt: Long,
    /** Copy in the phone gallery, if one was made. */
    val galleryUri: String? = null
) {
    val hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0
    val locationLine: String
        get() = address.ifBlank { if (hasLocation) "%.6f, %.6f".format(latitude, longitude) else "No location" }
}

class EvidenceStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(context.filesDir, "evidence.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<EvidenceItem>> = _items.asStateFlow()

    private fun load(): List<EvidenceItem> = runCatching {
        if (!index.exists()) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(EvidenceItem.serializer()), index.readText()).filter { File(it.path).exists() }
    }.getOrDefault(emptyList())

    private fun write(list: List<EvidenceItem>) {
        _items.value = list.sortedByDescending { it.takenAt }
        runCatching {
            val tmp = File(index.path + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(EvidenceItem.serializer()), _items.value))
            tmp.renameTo(index)
        }
    }

    fun contains(path: String): Boolean = _items.value.any { it.path == path }
    fun byPath(path: String): EvidenceItem? = _items.value.firstOrNull { it.path == path }

    /**
     * Finishes a photo straight from the camera: turns it upright, prints the date, time,
     * coordinates and address on it (so offices can see where it was taken even if their
     * mail program hides the GPS data), writes the GPS tags, and saves a gallery copy.
     */
    suspend fun addPhoto(file: File, loc: ReportLocation, saveToGallery: Boolean): EvidenceItem = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        runCatching { stamp(file, loc, now) }
        if (loc.hasFix) ExifGeotagger.tagPhoto(file.absolutePath, loc.latitude, loc.longitude)
        val gallery = if (saveToGallery) copyToGallery(file, false, now) else null
        val item = EvidenceItem(
            id = UUID.randomUUID().toString(), path = file.absolutePath, isVideo = false,
            latitude = loc.latitude, longitude = loc.longitude, accuracy = loc.accuracy,
            address = loc.oneLineAddress(), takenAt = now, galleryUri = gallery?.toString()
        )
        write(_items.value + item)
        item
    }

    suspend fun addVideo(file: File, loc: ReportLocation, startedAt: Long, saveToGallery: Boolean): EvidenceItem = withContext(Dispatchers.IO) {
        val gallery = if (saveToGallery) copyToGallery(file, true, startedAt) else null
        val item = EvidenceItem(
            id = UUID.randomUUID().toString(), path = file.absolutePath, isVideo = true,
            latitude = loc.latitude, longitude = loc.longitude, accuracy = loc.accuracy,
            address = loc.oneLineAddress(), takenAt = startedAt, galleryUri = gallery?.toString()
        )
        write(_items.value + item)
        item
    }

    /** Deletes the saved item from the app and, if asked, its copy in the phone gallery. */
    suspend fun delete(items: Collection<EvidenceItem>, alsoFromGallery: Boolean) = withContext(Dispatchers.IO) {
        val ids = items.map { it.id }.toSet()
        items.forEach { item ->
            runCatching { File(item.path).delete() }
            if (alsoFromGallery) item.galleryUri?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
        }
        write(_items.value.filterNot { it.id in ids })
    }

    private fun ReportLocation.oneLineAddress(): String =
        address.ifBlank { listOf(city, district, state).filter { it.isNotBlank() }.joinToString(", ") }

    // ---------- gallery ----------

    private fun copyToGallery(file: File, video: Boolean, takenAt: Long): Uri? = runCatching {
        val resolver = context.contentResolver
        val collection = if (video) {
            if (Build.VERSION.SDK_INT >= 29) AndroidMediaStore.Video.Media.getContentUri(AndroidMediaStore.VOLUME_EXTERNAL_PRIMARY)
            else AndroidMediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            if (Build.VERSION.SDK_INT >= 29) AndroidMediaStore.Images.Media.getContentUri(AndroidMediaStore.VOLUME_EXTERNAL_PRIMARY)
            else AndroidMediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(AndroidMediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(AndroidMediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(AndroidMediaStore.MediaColumns.DATE_TAKEN, takenAt)
            if (Build.VERSION.SDK_INT >= 29) {
                put(
                    AndroidMediaStore.MediaColumns.RELATIVE_PATH,
                    (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Duarbagori Reports"
                )
                put(AndroidMediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(AndroidMediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        uri
    }.getOrNull()

    // ---------- visible stamp ----------

    private fun stamp(file: File, loc: ReportLocation, time: Long) {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        // Decode at most ~2560 px on the long side: sharp enough for evidence, small enough to email.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2560) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (rotation != 0f) {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation) }, true)
                .also { if (it !== raw) raw.recycle() }
        } else raw
        val bmp = if (upright.isMutable) upright else upright.copy(Bitmap.Config.ARGB_8888, true).also { upright.recycle() }

        val lines = buildList {
            add(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(time)))
            if (loc.hasFix) add("Lat %.6f  Long %.6f  (±%.0f m)".format(loc.latitude, loc.longitude, loc.accuracy))
            else add("Location not available")
            val place = loc.oneLineAddress()
            if (place.isNotBlank()) add(place)
            add("Duarbagori Reports")
        }
        val canvas = Canvas(bmp)
        val textSize = max(18f, bmp.width / 42f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val pad = textSize * 0.6f
        val maxWidth = bmp.width - pad * 2
        val wrapped = lines.flatMap { wrap(it, paint, maxWidth) }
        val lineH = textSize * 1.25f
        val boxH = wrapped.size * lineH + pad * 1.5f
        canvas.drawRect(0f, bmp.height - boxH, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })
        wrapped.forEachIndexed { i, line ->
            canvas.drawText(line, pad, bmp.height - boxH + pad + textSize + i * lineH, paint)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        // The new file is already upright; record that so viewers don't rotate it again.
        runCatching {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        if (paint.measureText(text) <= width) return listOf(text)
        val out = mutableListOf<String>()
        var line = ""
        text.split(" ").forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > width && line.isNotEmpty()) { out += line; line = word } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    companion object {
        fun androidLocation(loc: ReportLocation): Location? =
            if (!loc.hasFix) null else Location("duarbagori").apply {
                latitude = loc.latitude; longitude = loc.longitude; accuracy = loc.accuracy; time = System.currentTimeMillis()
            }

    }
}
