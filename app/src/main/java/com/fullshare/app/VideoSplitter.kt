package com.fullshare.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer

data class VideoInfo(
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

/**
 * Splits a video into parts of at most [maxPartMs] WITHOUT re-encoding.
 * Samples are copied byte-for-byte, so quality is identical to the original.
 * Cuts are placed on keyframes (required for lossless cutting), so each part
 * is usually a little shorter than the limit.
 */
object VideoSplitter {

    fun probe(context: Context, uri: Uri): VideoInfo {
        var name = "video.mp4"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return VideoInfo(name, dur, size, w, h)
        } finally {
            r.release()
        }
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }

    private fun rotationOf(context: Context, uri: Uri): Int = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        0
    }

    fun split(
        context: Context,
        uri: Uri,
        maxPartMs: Long,
        outDir: File,
        onProgress: (Float) -> Unit,
    ): List<File> {
        outDir.deleteRecursively()
        outDir.mkdirs()

        // ---- Find tracks and cut points ----
        val scanner = MediaExtractor()
        scanner.setDataSource(context, uri, null)
        var videoTrack = -1
        var audioTrack = -1
        var bufferSize = 2 * 1024 * 1024
        for (i in 0 until scanner.trackCount) {
            val f = scanner.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
            else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = maxOf(bufferSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        if (videoTrack < 0) {
            scanner.release()
            throw IllegalArgumentException("No video track found in this file")
        }
        val vFormat = scanner.getTrackFormat(videoTrack)
        var durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        if (durationUs <= 0) durationUs = probe(context, uri).durationMs * 1000

        scanner.selectTrack(videoTrack)
        val limitUs = maxPartMs * 1000
        val bounds = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        while (true) {
            val target = start + limitUs
            if (target >= durationUs) {
                bounds += start to Long.MAX_VALUE
                break
            }
            scanner.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var cut = scanner.sampleTime
            if (cut <= start) {
                // No keyframe inside the window (very long keyframe gap): use the next one.
                scanner.seekTo(target, MediaExtractor.SEEK_TO_NEXT_SYNC)
                cut = scanner.sampleTime
                if (cut <= start) {
                    bounds += start to Long.MAX_VALUE
                    break
                }
            }
            bounds += start to cut
            start = cut
        }
        scanner.release()

        // ---- Write each part ----
        val rotation = rotationOf(context, uri)
        val buffer = ByteBuffer.allocateDirect(maxOf(bufferSize, 8 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        val files = mutableListOf<File>()

        bounds.forEachIndexed { index, (s, e) ->
            val file = File(outDir, "part_%02d.mp4".format(index + 1))
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                muxer.setOrientationHint(rotation)
                val trackMap = HashMap<Int, Int>()
                trackMap[videoTrack] = muxer.addTrack(ex.getTrackFormat(videoTrack))
                ex.selectTrack(videoTrack)
                if (audioTrack >= 0) {
                    try {
                        trackMap[audioTrack] = muxer.addTrack(ex.getTrackFormat(audioTrack))
                        ex.selectTrack(audioTrack)
                    } catch (_: Exception) {
                        // Audio codec not supported by MP4 muxer: keep video only.
                    }
                }
                muxer.start()
                ex.seekTo(s, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val partEnd = minOf(e, durationUs)
                val partLen = (partEnd - s).coerceAtLeast(1)
                val finished = HashSet<Int>()
                var lastReport = 0L
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    val track = ex.sampleTrackIndex
                    val out = trackMap[track]
                    if (out == null) { ex.advance(); continue }
                    if (t >= e) {
                        finished += track
                        if (finished.size >= trackMap.size) break
                        ex.advance()
                        continue
                    }
                    if (t >= s) {
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size > 0) {
                            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, t - s, flags)
                            muxer.writeSampleData(out, buffer, info)
                        }
                    }
                    if (t - lastReport > 500_000) {
                        lastReport = t
                        val within = ((t - s).toFloat() / partLen).coerceIn(0f, 1f)
                        onProgress((index + within) / bounds.size)
                    }
                    ex.advance()
                }
                muxer.stop()
            } finally {
                try { muxer.release() } catch (_: Exception) {}
                ex.release()
            }
            files += file
            onProgress((index + 1).toFloat() / bounds.size)
        }
        return files
    }

    /** Copies the whole original file (for apps without a length limit, e.g. a Facebook post). */
    fun copyFull(context: Context, uri: Uri, outDir: File, name: String): File {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video.mp4" }
        val file = File(outDir, if (safe.contains('.')) safe else "$safe.mp4")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open video" }
            file.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return file
    }
}
