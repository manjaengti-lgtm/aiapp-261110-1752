package com.arin.voice

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ChatGPT-style natural voice. Each sentence is turned into speech by Pollinations' free OpenAI-voice TTS
 * (model=openai-audio, voices alloy/echo/fable/onyx/nova/shimmer — no key) and played in order. When a sentence
 * can't be fetched (offline), it calls [onFallback] so the caller can use the phone's own voice instead.
 */
class NeuralVoice(private val cacheDir: File) {
    /** One of: alloy, echo, fable, onyx, nova, shimmer. */
    @Volatile var voice = "nova"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private var player: MediaPlayer? = null
    @Volatile private var gen = 0
    private var worker: Job? = null
    private var onClipDone: () -> Unit = {}
    private var onFallback: (String) -> Unit = {}

    fun start(onClipDone: () -> Unit, onFallback: (String) -> Unit) {
        this.onClipDone = onClipDone
        this.onFallback = onFallback
        worker = scope.launch {
            for ((g, text) in queue) {
                if (g != gen) continue
                val bytes = withContext(Dispatchers.IO) { fetch(text) }
                if (g != gen) continue
                if (bytes == null) { onFallback(text); continue }
                playAndWait(bytes)
                if (g == gen) onClipDone()
            }
        }
    }

    fun speak(text: String) { queue.trySend(gen to text) }

    /** Stop immediately and drop anything queued (barge-in, or a new turn). */
    fun stop() {
        gen++
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    fun close() {
        stop()
        worker?.cancel()
        scope.cancel()
    }

    private fun fetch(text: String): ByteArray? = runCatching {
        val t = text.take(900)
        val url = "https://text.pollinations.ai/" + URLEncoder.encode(t, "UTF-8") + "?model=openai-audio&voice=" + voice
        val req = Request.Builder().url(url).header("User-Agent", "Arin-Android").build()
        http.newCall(req).execute().use { r ->
            val body = r.body ?: return null
            val bytes = body.bytes()
            val type = r.header("Content-Type").orEmpty()
            // A real clip is audio; an error usually comes back as a short bit of text/JSON.
            if (!r.isSuccessful || (!type.startsWith("audio") && bytes.size < 2_000)) null else bytes
        }
    }.getOrNull()

    private suspend fun playAndWait(bytes: ByteArray) = withContext(Dispatchers.Main) {
        val file = File.createTempFile("arin-tts", ".mp3", cacheDir)
        runCatching { file.writeBytes(bytes) }
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            player = mp
            fun finish() {
                runCatching { mp.release() }
                if (player === mp) player = null
                runCatching { file.delete() }
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                finish()
            }
            cont.invokeOnCancellation { runCatching { mp.release(); file.delete() } }
        }
    }

    companion object {
        val VOICES = listOf("nova", "shimmer", "alloy", "echo", "fable", "onyx")
    }
}
