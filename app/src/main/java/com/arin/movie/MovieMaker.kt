package com.arin.movie

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arin.terminal.Linux
import com.arin.terminal.Shell
import java.io.File

/**
 * Turns a list of scenes (each a visual prompt + narration) into one stitched video, entirely with
 * free, keyless tools inside Arin's Linux:
 *  - a still image per scene from Pollinations (image.pollinations.ai, no key),
 *  - narration per scene from Pollinations TTS (text.pollinations.ai, no key),
 *  - ffmpeg gives each image slow motion (Ken Burns) for the length of its narration and stitches them.
 *
 * The "director" that writes the scenes from a script is the person's own custom model — that happens
 * in the view model, not here. This class is only the rendering tool.
 */
object MovieMaker {
    data class Scene(val prompt: String, val narration: String)

    private const val DIR = "/root/movie"

    suspend fun toolsReady(context: Context): Boolean =
        Shell.run(context, "command -v ffmpeg >/dev/null 2>&1 && command -v curl >/dev/null 2>&1 && echo yes || echo no", 60_000)
            .stdout.contains("yes")

    suspend fun installTools(context: Context, log: (String) -> Unit): Boolean {
        log("Installing ffmpeg (one-time)…")
        val r = Shell.run(
            context,
            "apt-get update >/dev/null 2>&1; apt-get install -y ffmpeg curl python3 >/dev/null 2>&1; " +
                "command -v ffmpeg >/dev/null 2>&1 && echo ARIN_OK || echo ARIN_FAIL",
            timeoutMs = 20 * 60_000L,
        )
        val ok = r.stdout.contains("ARIN_OK")
        log(if (ok) "ffmpeg ready ✓" else "Couldn't install ffmpeg.")
        return ok
    }

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    /** Build the whole render as one bash script (scene data embedded as base64, so nothing to escape). */
    private fun script(scenes: List<Scene>, voice: String): String {
        val sb = StringBuilder()
        sb.append("cd $DIR || exit 1\n")
        sb.append("rm -f s*.prompt s*.narr s*.jpg s*.mp3 s*.mp4 movie.mp4 list.txt render.log\n")
        sb.append(": > progress\n")
        scenes.forEachIndexed { i, s ->
            sb.append("echo '${b64(s.prompt)}' | base64 -d > s$i.prompt\n")
            sb.append("echo '${b64(s.narration.ifBlank { " " })}' | base64 -d > s$i.narr\n")
        }
        sb.append("N=${scenes.size}\n")
        sb.append(
            """
            enc() { python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.stdin.read()))'; }
            i=0
            while [ ${'$'}i -lt ${'$'}N ]; do
              echo "Scene ${'$'}((i+1))/${'$'}N — image + voice…" >> progress
              penc=${'$'}(enc < s${'$'}i.prompt)
              nenc=${'$'}(enc < s${'$'}i.narr)
              curl -s -A Arin -e https://arin.app "https://image.pollinations.ai/prompt/${'$'}penc?width=1280&height=720&nologo=true&model=flux&seed=${'$'}((i+7))" -o s${'$'}i.jpg
              curl -s -A Arin -e https://arin.app "https://text.pollinations.ai/${'$'}nenc?model=openai-audio&voice=$voice" -o s${'$'}i.mp3
              if ! ffprobe -v error -show_entries format=duration -of csv=p=0 s${'$'}i.mp3 >/dev/null 2>&1; then
                ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 4 -c:a libmp3lame s${'$'}i.mp3 >> render.log 2>&1
              fi
              dur=${'$'}(ffprobe -v error -show_entries format=duration -of csv=p=0 s${'$'}i.mp3 2>/dev/null)
              case "${'$'}dur" in ''|*[!0-9.]*) dur=4 ;; esac
              frames=${'$'}(python3 -c "print(max(50,int(float('${'$'}dur')*25)))" 2>/dev/null || echo 100)
              echo "Scene ${'$'}((i+1))/${'$'}N — rendering…" >> progress
              if [ -s s${'$'}i.jpg ] && ffmpeg -v error -i s${'$'}i.jpg -f null - >/dev/null 2>&1; then
                ffmpeg -y -loop 1 -i s${'$'}i.jpg -i s${'$'}i.mp3 -filter_complex "[0:v]scale=1600:900:force_original_aspect_ratio=increase,crop=1600:900,zoompan=z='min(zoom+0.0010,1.15)':d=${'$'}frames:s=1280x720:fps=25,format=yuv420p[v]" -map "[v]" -map 1:a -t ${'$'}dur -r 25 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -ar 44100 -shortest s${'$'}i.mp4 >> render.log 2>&1
              else
                ffmpeg -y -f lavfi -i color=c=0x111317:s=1280x720:r=25 -i s${'$'}i.mp3 -t ${'$'}dur -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -ar 44100 -shortest s${'$'}i.mp4 >> render.log 2>&1
              fi
              i=${'$'}((i+1))
            done
            echo "Stitching ${'$'}N scenes…" >> progress
            : > list.txt
            j=0
            while [ ${'$'}j -lt ${'$'}N ]; do [ -s s${'$'}j.mp4 ] && echo "file 's${'$'}j.mp4'" >> list.txt; j=${'$'}((j+1)); done
            ffmpeg -y -f concat -safe 0 -i list.txt -c copy movie.mp4 >> render.log 2>&1 || ffmpeg -y -f concat -safe 0 -i list.txt -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac movie.mp4 >> render.log 2>&1
            if [ -s movie.mp4 ]; then echo "DONE" >> progress; else echo "FAILED" >> progress; fi
            """.trimIndent(),
        )
        sb.append("\n")
        return sb.toString()
    }

    /** Stage and launch the render in the background. Returns false if it couldn't start. */
    suspend fun startRender(context: Context, scenes: List<Scene>, voice: String = "nova"): Boolean {
        val enc = b64(script(scenes, voice))
        val r = Shell.run(
            context,
            "mkdir -p $DIR && echo '$enc' | base64 -d > $DIR/render.sh && " +
                "pkill -f render.sh 2>/dev/null; setsid bash $DIR/render.sh >/dev/null 2>&1 & echo started",
            60_000,
        )
        return r.stdout.contains("started")
    }

    suspend fun progress(context: Context): String =
        Shell.run(context, "tail -n 3 $DIR/progress 2>/dev/null", 30_000).stdout.trim()

    suspend fun isDone(context: Context): Boolean =
        Shell.run(context, "grep -q DONE $DIR/progress 2>/dev/null && echo yes || echo no", 30_000).stdout.contains("yes")

    suspend fun failed(context: Context): Boolean =
        Shell.run(context, "grep -q FAILED $DIR/progress 2>/dev/null && echo yes || echo no", 30_000).stdout.contains("yes")

    private fun outputFile(context: Context): File = File(Linux.rootfs(context), "root/movie/movie.mp4")

    /** Copy the finished movie to a shareable cache file and open it in a video player. */
    fun play(context: Context): Boolean {
        val src = outputFile(context)
        if (!src.exists() || src.length() < 1000) return false
        val dir = File(context.cacheDir, "movies").apply { mkdirs() }
        val out = File(dir, "arin-movie-${System.currentTimeMillis()}.mp4")
        runCatching { src.copyTo(out, overwrite = true) }.getOrElse { return false }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Copy the finished movie out so it can be shared to other apps. */
    fun shareIntent(context: Context): Intent? {
        val src = outputFile(context)
        if (!src.exists() || src.length() < 1000) return null
        val dir = File(context.cacheDir, "movies").apply { mkdirs() }
        val out = File(dir, "arin-movie.mp4")
        runCatching { src.copyTo(out, overwrite = true) }.getOrElse { return null }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
        return Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
