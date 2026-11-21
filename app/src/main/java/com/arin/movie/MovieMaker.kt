package com.arin.movie

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arin.terminal.Linux
import com.arin.terminal.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Arin's film studio (the "Erin blueprint", sized to what free tools can really do).
 *
 *  - Your own model (e.g. DeepSeek) is the only creative brain: it writes a production bible —
 *    characters with fixed looks and voices, then a shot list with shot size, camera move, action and the
 *    spoken line. See [DIRECTOR].
 *  - assets/movie-render.py renders it inside Arin's Linux: photoreal stills + voices from free keyless
 *    Pollinations, optional real motion from a Hugging Face ZeroGPU Space on the person's own free quota,
 *    quality checks, scene-level resume, subtitles, and the ffmpeg final cut.
 *  - Nothing paid is ever called. When the free GPU quota is used up, shots fall back to camera moves and
 *    can be upgraded later with "Add motion".
 */
object MovieMaker {
    const val DEFAULT_SPACE = "zerogpu-aoti/wan2-2-fp8da-aoti-faster"
    val VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    data class Character(val id: String, val name: String, val look: String, val voice: String)
    data class Shot(
        val characters: List<String>,
        val shot: String,
        val camera: String,
        val action: String,
        val location: String,
        val line: String,
        val speaker: String,
        val seconds: Double,
    )
    data class Bible(
        val title: String,
        val style: String,
        val narratorVoice: String,
        val characters: List<Character>,
        val shots: List<Shot>,
    )

    data class Summary(val shots: Int, val seconds: Double, val motion: Int, val still: Int, val missing: Int)

    private const val DIR = "/root/movie"
    private fun dir(context: Context) = File(Linux.rootfs(context), "root/movie")

    /** The production-planning instructions given to the person's own model. */
    val DIRECTOR = """
        You are the director, screenwriter and cinematographer of a short live-action film made with AI tools.
        Turn the user's idea or script into a production bible. If they gave a script, keep its story and dialogue.

        Reply with ONLY one JSON object, no prose, no code fences:
        {
          "title": "...",
          "style": "one line: era, colour grade, lens look, mood (always photorealistic live action)",
          "narrator_voice": one of ${VOICES.joinToString("|")},
          "characters": [
            {"id": "c1", "name": "...", "voice": one of ${VOICES.joinToString("|")},
             "look": "fixed visual description reused in every shot: adult age, ethnicity, face, hair, build, exact wardrobe, accessories"}
          ],
          "shots": [
            {"characters": ["c1"], "shot": "wide establishing | medium | close-up | over-the-shoulder | POV ...",
             "camera": "slow push in | pull out | pan left | pan right | tracking | crane up | handheld | static",
             "action": "what physically happens in the frame, who faces whom, where things are",
             "location": "place and time of day", "speaker": "c1 or narrator",
             "line": "the dialogue or voiceover spoken during this shot (one or two sentences, may be empty)",
             "seconds": 4}
          ]
        }

        Rules: every character is a realistic adult human actor — never cartoon, anime or stylised.
        Keep each character's look text identical across the film; record deliberate changes in the action.
        Use many short shots (3-6 s each) rather than few long ones; keep screen direction and geography consistent
        between consecutive shots. Use as many shots as the story needs (a longer script makes a longer film).
        No real people, no sexual content, nothing a free public video service would refuse.
    """.trimIndent()

    // ---------- bible <-> JSON ----------

    /** Pull the bible out of the model's reply, tolerating code fences and stray prose. */
    fun parseBible(raw: String): Bible? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = JSONObject(raw.substring(start, end + 1))
            val chars = o.optJSONArray("characters").objects().mapIndexedNotNull { idx, c ->
                val look = c.optString("look").trim()
                if (look.isBlank()) null
                else Character(
                    id = c.optString("id").ifBlank { "c${idx + 1}" },
                    name = c.optString("name").ifBlank { "Character ${idx + 1}" },
                    look = look,
                    voice = c.optString("voice").takeIf { it in VOICES } ?: VOICES[idx % VOICES.size],
                )
            }
            val shots = o.optJSONArray("shots").objects().mapNotNull { s ->
                val action = s.optString("action").trim()
                val line = s.optString("line").trim()
                if (action.isBlank() && line.isBlank()) null
                else Shot(
                    characters = s.optJSONArray("characters").strings(),
                    shot = s.optString("shot").trim(),
                    camera = s.optString("camera").trim(),
                    action = action.ifBlank { line },
                    location = s.optString("location").trim(),
                    line = line,
                    speaker = s.optString("speaker").ifBlank { "narrator" },
                    seconds = s.optDouble("seconds", 4.0).takeIf { !it.isNaN() }?.coerceIn(2.0, 12.0) ?: 4.0,
                )
            }
            if (shots.isEmpty()) null
            else Bible(
                title = o.optString("title").ifBlank { "Untitled" },
                style = o.optString("style").trim(),
                narratorVoice = o.optString("narrator_voice").takeIf { it in VOICES } ?: "onyx",
                characters = chars,
                shots = shots,
            )
        }.getOrNull()
    }

    private fun Bible.toJson(): String = JSONObject().apply {
        put("title", title)
        put("style", style)
        put("narrator_voice", narratorVoice)
        put("characters", JSONArray(characters.map {
            JSONObject().put("id", it.id).put("name", it.name).put("look", it.look).put("voice", it.voice)
        }))
        put("shots", JSONArray(shots.map {
            JSONObject().put("characters", JSONArray(it.characters)).put("shot", it.shot).put("camera", it.camera)
                .put("action", it.action).put("location", it.location).put("line", it.line)
                .put("speaker", it.speaker).put("seconds", it.seconds)
        }))
    }.toString(1)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    // ---------- project on disk (persists across app restarts) ----------

    /** Start a new film: clear the old one and write the bible. */
    fun saveNewProject(context: Context, bible: Bible) {
        val d = dir(context)
        d.mkdirs()
        d.listFiles()?.forEach { it.deleteRecursively() }
        File(d, "project.json").writeText(bible.toJson())
    }

    fun hasProject(context: Context): Boolean = File(dir(context), "project.json").exists()

    fun projectTitle(context: Context): String? = runCatching {
        JSONObject(File(dir(context), "project.json").readText()).optString("title")
    }.getOrNull()

    fun summary(context: Context): Summary? = runCatching {
        val o = JSONObject(File(dir(context), "summary.json").readText())
        Summary(o.optInt("shots"), o.optDouble("seconds"), o.optInt("motion"), o.optInt("still"), o.optInt("missing"))
    }.getOrNull()

    // ---------- tools + render ----------

    suspend fun toolsReady(context: Context): Boolean =
        Shell.run(
            context,
            "command -v ffmpeg >/dev/null && command -v curl >/dev/null && command -v python3 >/dev/null && echo yes || echo no",
            60_000,
        ).stdout.contains("yes")

    suspend fun installTools(context: Context, log: (String) -> Unit): Boolean {
        log("Installing ffmpeg + python (one-time)…")
        val r = Shell.run(
            context,
            "apt-get update >/dev/null 2>&1; apt-get install -y ffmpeg curl python3 >/dev/null 2>&1; " +
                "command -v ffmpeg >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1 && echo ARIN_OK || echo ARIN_FAIL",
            timeoutMs = 20 * 60_000L,
        )
        val ok = r.stdout.contains("ARIN_OK")
        log(if (ok) "Tools ready ✓" else "Couldn't install ffmpeg/python.")
        return ok
    }

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    /**
     * Launch (or resume) the renderer in the background. [motion] = only upgrade still shots to real motion.
     * The HF token is passed in the environment, never written to disk.
     */
    suspend fun startRender(context: Context, hfToken: String, space: String, subtitles: Boolean, motion: Boolean): Boolean {
        val d = dir(context)
        if (!File(d, "project.json").exists()) return false
        context.assets.open("movie-render.py").use { input -> File(d, "render.py").outputStream().use { input.copyTo(it) } }
        val env = "ARIN_HF_TOKEN=\"$(echo '${b64(hfToken)}' | base64 -d)\" " +
            "ARIN_SPACE=\"$(echo '${b64(space)}' | base64 -d)\" " +
            "ARIN_SUBS=${if (subtitles) 1 else 0} ARIN_MODE=${if (motion) "motion" else "render"}"
        val r = Shell.run(
            context,
            "cd $DIR && pkill -f 'movie/[r]ender.py' 2>/dev/null; rm -f progress; " +
                "$env setsid python3 $DIR/render.py >/dev/null 2>&1 & echo started",
            60_000,
        )
        return r.stdout.contains("started")
    }

    suspend fun stop(context: Context) {
        Shell.run(context, "pkill -f 'movie/[r]ender.py' 2>/dev/null; echo ok", 30_000)
    }

    enum class State { Idle, Running, Done, Partial, Failed }

    /** Reads the renderer's progress file: last few lines + overall state. */
    suspend fun status(context: Context): Pair<State, List<String>> {
        val r = Shell.run(
            context,
            "cd $DIR 2>/dev/null && tail -n 4 progress 2>/dev/null; echo '@@'; pgrep -f 'movie/[r]ender.py' >/dev/null && echo RUN || echo IDLE",
            30_000,
        ).stdout
        val lines = r.substringBefore("@@").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val running = r.substringAfter("@@").contains("RUN")
        val last = lines.lastOrNull().orEmpty()
        val state = when {
            last == "DONE" -> State.Done
            last == "DONE_PARTIAL" -> State.Partial
            last == "FAILED" -> State.Failed
            running -> State.Running
            else -> State.Idle   // e.g. Arin was closed mid-render: Resume picks up where it stopped
        }
        return state to lines.filter { it !in setOf("DONE", "DONE_PARTIAL", "FAILED") }
    }

    private fun outputFile(context: Context): File = File(dir(context), "movie.mp4")

    fun hasMovie(context: Context): Boolean = outputFile(context).let { it.exists() && it.length() > 1000 }

    private fun exportCopy(context: Context, name: String): android.net.Uri? {
        val src = outputFile(context)
        if (!src.exists() || src.length() < 1000) return null
        val out = File(File(context.cacheDir, "movies").apply { mkdirs() }, name)
        runCatching { src.copyTo(out, overwrite = true) }.getOrElse { return null }
        return FileProvider.getUriForFile(context, context.packageName + ".files", out)
    }

    /** Copy the finished movie to a shareable cache file and open it in a video player. */
    fun play(context: Context): Boolean {
        val uri = exportCopy(context, "arin-movie-${System.currentTimeMillis()}.mp4") ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Copy the finished movie out so it can be shared to other apps. */
    fun shareIntent(context: Context): Intent? {
        val uri = exportCopy(context, "arin-movie.mp4") ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
