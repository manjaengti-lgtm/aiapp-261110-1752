package com.arin.analyst

import android.content.Context
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One question to Claude through the phone line (`/root/.arin/analyst.sh`).
 *
 * The script is stateless by design: every call is a new `claude -p`, never `--continue`. It already
 * does the hedge stripping and the four escalating rungs, and it stamps whatever comes back. This
 * class only enforces the per-turn budget and turns the script's JSON into something showable.
 */
object AnalystCall {

    sealed interface Outcome {
        /** Claude answered; [text] is the stamped text, to be shown unchanged. */
        data class Answered(val text: String, val call: Int) : Outcome
        /** The line was dead or the call timed out. */
        data object Unreachable : Outcome
        /** The lane is off for this chat. */
        data object Skipped : Outcome
        /** The turn's budget is spent. */
        data class Capped(val note: String) : Outcome
    }

    private const val SCRIPT = "/root/.arin/analyst.sh"
    private const val TIMEOUT_MS = 320_000L

    /** The line as the model should see it before the call, e.g. "📞 Asking Claude…\n> why …\nfile: Foo.kt". */
    fun askingLine(question: String, file: String?): String = buildString {
        appendLine(AnalystLane.ASKING)
        append("> ").appendLine(question.trim().replace("\n", "\n> "))
        if (!file.isNullOrBlank()) append("file: ").append(file.substringAfterLast('/'))
    }.trimEnd()

    suspend fun ask(
        context: Context,
        chatId: String?,
        lane: AnalystLane.Lane,
        question: String,
        file: String? = null,
        lines: String? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!AnalystLane.enabled(chatId)) return@withContext Outcome.Skipped
        if (question.isBlank()) return@withContext Outcome.Unreachable
        if (!AnalystLane.take(chatId, lane)) return@withContext Outcome.Capped(AnalystLane.cappedNote(lane))
        if (!Linux.installed(context)) return@withContext Outcome.Unreachable
        runCatching { AnalystSetup.ensure(context) }
        val cmd = buildString {
            append("bash ").append(SCRIPT)
            if (!file.isNullOrBlank()) append(" -f '").append(file.replace("'", "'\\''")).append('\'')
            if (!lines.isNullOrBlank()) append(" -l '").append(lines.replace("'", "'\\''")).append('\'')
            append(" '").append(question.replace("'", "'\\''")).append('\'')
        }
        val run = runCatching { Linux.run(context, cmd, timeoutMs = TIMEOUT_MS) }.getOrNull()
            ?: return@withContext Outcome.Unreachable
        val out = run.stdout.trim()
        if (out.isEmpty()) return@withContext Outcome.Unreachable
        // The script prints one JSON line; a bare answer is fine too.
        val json = out.lines().lastOrNull { it.trimStart().startsWith("{") }
        if (json != null) {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            val text = obj?.get("text")?.jsonPrimitive?.content
            if (!text.isNullOrBlank()) {
                return@withContext Outcome.Answered(text, obj["call"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            }
        }
        if (run.exitCode != 0) return@withContext Outcome.Unreachable
        Outcome.Answered(out, 0)
    }
}
