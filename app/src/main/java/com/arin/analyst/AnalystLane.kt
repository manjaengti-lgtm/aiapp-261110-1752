package com.arin.analyst

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * The "Talk to Claude" analyst lane.
 *
 * A per-chat switch: when it is ON, that chat may ask Claude a question through the `analyst_ask`
 * tool (a headless one-shot `claude -p` behind the phone line). When it is OFF the chat answers alone
 * and says so — never silently.
 *
 * The lane is deliberately independent of reverse engineering: REA on + lane off is a valid state
 * (tools without the phone line), and neither turns the other on.
 *
 * Plain words only, always shown to the person:
 *  - before a call:  "📞 Asking Claude…" + the quoted question + the file name
 *  - on an answer:   "📞 Claude's answer — call N" + the body
 *  - on a failure:   "📞 Claude unreachable — carrying on without it"
 *  - when skipped:   "✍️ DeepSeek answered alone — Claude not asked."
 *  - end of a turn that used the lane: "📞 Claude consulted N times this turn"
 *
 * The budget is enforced here, in code, never left to the model: 6 calls per turn normally
 * (chat and the reverse-engineering tab), 3 in the Update Arin tab.
 */
object AnalystLane {

    /** Where the lane is being used. Each lane carries its own per-turn budget. */
    enum class Lane(val cap: Int) {
        /** Normal chat. */
        CHAT(6),

        /** Reverse-engineering tab: same lane, same budget as chat. */
        REA(6),

        /** Update Arin tab: read-only questions before editing Arin's own source, at most 3. */
        UPDATE(3),
    }

    const val ASKING = "📞 Asking Claude…"
    const val UNREACHABLE = "📞 Claude unreachable — carrying on without it"
    const val SKIP_LINE = "✍️ DeepSeek answered alone — Claude not asked."

    /** Passed to Claude Code for the reverse-engineering tab so a flash model isn't flooded by 264 tools. */
    const val REA_TOOL_PROFILE = "--tool-catalog-profile hot-cold"

    /** Setting name (kept for the settings store and for the chat that reads it). */
    const val SETTING = "analyst_assist"

    private val assist = mutableStateMapOf<String, Boolean>()
    private val turns = mutableStateMapOf<String, Int>()
    private val counts = mutableStateMapOf<String, Int>()

    /** Is the lane armed for this chat? */
    fun enabled(chatId: String?): Boolean = chatId != null && assist[chatId] == true

    /** Arms/disarms the lane for one chat. Arming makes sure the phone line exists. */
    fun set(context: Context, chatId: String?, on: Boolean) {
        if (chatId == null) return
        assist[chatId] = on
        if (on) AnalystSetup.ensureAsync(context)
    }

    /** A new chat starts with the lane off, as if the switch had never been touched. */
    fun newChat(chatId: String?) {
        if (chatId == null) return
        assist[chatId] = false
        turns.remove(chatId)
        counts.remove(chatId)
    }

    /** The chat was deleted: forget everything about it. */
    fun forget(chatId: String) {
        assist.remove(chatId)
        turns.remove(chatId)
        counts.remove(chatId)
    }

    private fun key(chatId: String) = "$chatId#${turns[chatId] ?: 0}"

    /** Each new turn gets a fresh budget. */
    fun startTurn(chatId: String?) {
        if (chatId == null) return
        turns[chatId] = (turns[chatId] ?: 0) + 1
        counts[key(chatId)] = 0
    }

    /** How many times Claude was already consulted during this turn. */
    fun consulted(chatId: String?): Int = chatId?.let { counts[key(it)] } ?: 0

    fun remaining(chatId: String?, lane: Lane): Int = (lane.cap - consulted(chatId)).coerceAtLeast(0)

    /**
     * Takes one call out of this turn's budget.
     * False means the cap is reached and the tab has to carry on by itself.
     */
    fun take(chatId: String?, lane: Lane): Boolean {
        if (chatId == null) return false
        val used = consulted(chatId)
        if (used >= lane.cap) return false
        counts[key(chatId)] = used + 1
        return true
    }

    /** Said when the cap is hit: plain words, then the tab carries on manually. */
    fun cappedNote(lane: Lane): String =
        "📞 Claude consulted ${lane.cap} times this turn — cap reached, carrying on without it."

    /** The end-of-turn tally, or null when Claude wasn't consulted. */
    fun tally(chatId: String?): String? {
        val n = consulted(chatId)
        return when {
            n <= 0 -> null
            n == 1 -> "📞 Claude consulted 1 time this turn"
            else -> "📞 Claude consulted $n times this turn"
        }
    }

    // ---- Hedge detection (mirror of the check in analyst.sh, so the app agrees with the script) ----

    private val contentMarkers = listOf("`", "grep", "const/", ".method", ".field", "->", "fun ", "val ", "def ", "/")
    private val hedges = listOf(
        "as an ai", "legal", "i think", "i believe", "probably", "roughly", "approximately",
        "may vary", "should work", "might work", "in general", "it depends", "caveat",
        "disclaimer", "note that", "from memory", "hard to tell", "not sure",
    )

    /**
     * True when the start of an answer is all cushion and no substance. Real content wins:
     * a backtick, `grep`, `.method`, `const/` or a path in the head means it is not a hedge.
     */
    fun hedged(answer: String): Boolean {
        val head = answer.trimStart().lineSequence().filter { it.isNotBlank() }.take(6)
            .joinToString(" ").lowercase()
        if (head.isBlank()) return true
        if (contentMarkers.any { head.contains(it) }) return false
        return hedges.any { head.contains(it) }
    }

    // ---- The standing rules handed to the model ----

    /** The rule block for a tab. [reaOn] only changes the wording, never the budget. */
    fun rules(lane: Lane, reaOn: Boolean = false): String = buildString {
        appendLine("TALK TO CLAUDE (analyst lane) — ON for this chat.")
        appendLine("Ask Claude for a second opinion with the MCP tool `analyst_ask` — always by tool name, never by the shell path: tabs have no shell.")
        appendLine("Arguments: `question` (required), `file` (absolute path under /root/files, optional), `lines` like \"120-140\" (optional).")
        appendLine("It returns the stamped answer unchanged. It is one headless question: Claude does not remember the last one.")
        appendLine()
        appendLine("Show it exactly like this, in plain words:")
        appendLine("1. Before calling: `$ASKING`, then the quoted question and the file name.")
        appendLine("2. The answer: the tool's text exactly as returned, stamp and all. Never paraphrase an answer without showing the stamp.")
        appendLine("3. If the tool fails: `$UNREACHABLE` and answer yourself. Never go silent.")
        appendLine("4. If you don't call it this turn: `$SKIP_LINE` on its own line.")
        appendLine("5. End any turn that used the lane with `📞 Claude consulted N times this turn`.")
        appendLine()
        if (lane == Lane.UPDATE) {
            appendLine("This is the Update Arin tab: ask READ-ONLY questions first (\"what does this class do\", \"what breaks if I change it\") before you edit Arin's own source.")
            appendLine("Budget: at most ${lane.cap} calls this turn. When the cap is hit, say so and carry on manually.")
        } else {
            appendLine("Budget: at most ${lane.cap} calls this turn. When the cap is hit, say so and carry on manually.")
        }
        if (reaOn) appendLine("Reverse engineering is on too: use its tools freely, and $REA_TOOL_PROFILE is already set for the tab.")
        appendLine("Attached files are listed for this turn with their path under /root/files. Read them with Bash if the answer depends on them.")
    }.trimEnd()
}
