package com.arin.analyst

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures and video in every tab.
 *
 * - Whether a model can see pictures is probed once per model and remembered.
 * - If it can, downscaled picture blocks are handed over natively.
 * - If it can't, a vision-capable model writes a plain description first, marked as second-hand.
 * - Video is never native: ffmpeg takes frames at intervals and at scene changes, plus a transcript,
 *   and the frames go down the picture path.
 *
 * Hard caps before anything is sent: 12 pictures a turn, nothing wider than 1568 px, and video
 * sampling shortens as the clip gets longer.
 */
object Vision {

    const val MAX_EDGE = 1568
    private const val FRAMES_DIR = "/root/files/.frames"

    private val known = mutableMapOf<String, Boolean>()

    fun supports(model: String): Boolean? = known[model]

    fun remember(model: String, supported: Boolean) { known[model] = supported }

    /** Handed-In pictures, downscaled and capped. */
    suspend fun prepare(context: Context, images: List<String>): List<File> = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "vision").apply { mkdirs() }
        images.take(AttachmentManifest.MAX_IMAGES).mapNotNull { linuxPath ->
            val src = File(Linux.rootfs(context), linuxPath.removePrefix("/"))
            if (!src.exists()) return@mapNotNull null
            runCatching {
                val bitmap = BitmapFactory.decodeFile(src.absolutePath) ?: return@mapNotNull null
                val long = maxOf(bitmap.width, bitmap.height)
                val scaled = if (long > MAX_EDGE) {
                    val f = MAX_EDGE.toFloat() / long
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
                } else {
                    bitmap
                }
                val dest = File(out, src.nameWithoutExtension + ".jpg")
                dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                dest
            }.getOrNull()
        }
    }

    /** Pictures found by an earlier [describe] when the model can't see. */
    suspend fun describe(context: Context, model: String, images: List<String>): String = withContext(Dispatchers.IO) {
        val list = images.take(AttachmentManifest.MAX_IMAGES)
        if (list.isEmpty()) return@withContext ""
        val prompt = buildString {
            appendLine("Describe each picture for a model that cannot see it. Plain words, no guessing at pixels.")
            appendLine("Say what is shown, any text in it, the layout, and anything a developer would need.")
            list.forEach { appendLine("- $it") }
        }
        val out = runCatching {
            Linux.run(
                context,
                "cd /root && timeout 280 claude -p " + quote(prompt) + " --allowedTools \"Read,Grep,Glob,Bash\"",
                timeoutMs = 300_000L,
            ).stdout
        }.getOrDefault("")
        if (out.isBlank()) return@withContext ""
        remember(model, false)
        "(second-hand: described by a vision-capable model, not seen directly)\n" + out.trim()
    }

    /** Frames taken from a video: at intervals for short clips, sparser for long ones, plus scene changes. */
    suspend fun videoFrames(context: Context, videoLinuxPath: String): List<String> = withContext(Dispatchers.IO) {
        val root = Linux.rootfs(context)
        val src = File(root, videoLinuxPath.removePrefix("/"))
        if (!src.exists()) return@withContext emptyList()
        val dir = "$FRAMES_DIR/${src.nameWithoutExtension.take(30)}"
        File(root, dir.removePrefix("/")).apply { mkdirs() }.listFiles().orEmpty().forEach { it.delete() }
        val secs = duration(context, src)
        val every = when {
            secs <= 6 -> 1
            secs <= 30 -> 2
            secs <= 120 -> 5
            else -> (secs / AttachmentManifest.MAX_IMAGES).coerceAtLeast(5)
        }
        val cmd = "ffmpeg -nostdin -y -i ${quote(videoLinuxPath)} -vf \"fps=1/$every,scale='min($MAX_EDGE,iw)':-2\" -frames:v ${AttachmentManifest.MAX_IMAGES} " +
            quote("$dir/f%02d.jpg") + " >/dev/null 2>&1; " +
            "ffmpeg -nostdin -y -i ${quote(videoLinuxPath)} -vf \"select='gt(scene,0.3)',scale='min($MAX_EDGE,iw)':-2\" -frames:v 4 -vsync vfr " +
            quote("$dir/s%02d.jpg") + " >/dev/null 2>&1"
        runCatching { Linux.run(context, cmd, timeoutMs = 300_000L) }
        File(root, dir.removePrefix("/")).listFiles().orEmpty().sortedBy { it.name }.map { "$dir/${it.name}" }
    }

    /** A rough transcript of a video's sound, when a transcriber is installed. */
    suspend fun transcript(context: Context, videoLinuxPath: String): String = withContext(Dispatchers.IO) {
        val wav = videoLinuxPath.substringBeforeLast('.') + ".wav"
        val run = runCatching {
            Linux.run(
                context,
                "command -v whisper >/dev/null 2>&1 || exit 0; " +
                    "ffmpeg -nostdin -y -i ${quote(videoLinuxPath)} -vn -ac 1 -ar 16000 ${quote(wav)} >/dev/null 2>&1; " +
                    "whisper --model tiny --output-txt --output-file ${quote(wav)} ${quote(wav)} >/dev/null 2>&1; " +
                    "cat ${quote("$wav.txt")} 2>/dev/null",
                timeoutMs = 300_000L,
            )
        }.getOrNull() ?: return@withContext ""
        if (run.exitCode != 0) "" else run.stdout.trim()
    }

    /** The clip's length in seconds (0 when it can't be read). Runs a command, so it suspends. */
    private suspend fun duration(context: Context, file: File): Int {
        val out = runCatching {
            Linux.run(context, "ffprobe -v error -show_entries format=duration -of csv=p=0 ${quote("/root/files/" + file.name)}", timeoutMs = 30_000L).stdout
        }.getOrDefault("")
        return out.trim().substringBefore('.').toIntOrNull() ?: 0
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
