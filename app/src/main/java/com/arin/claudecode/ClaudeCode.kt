package com.arin.claudecode

import android.content.Context
import com.arin.terminal.Shell
import kotlinx.coroutines.delay

/**
 * Runs Anthropic's Claude Code CLI inside Arin's built-in Linux, signed in with the person's own
 * Claude Pro/Max subscription (no API key, no API billing). Prompts run non-interactively with
 * `claude -p` and the answer comes back into the app.
 *
 * Login uses `claude setup-token`, which prints an OAuth URL and, once the person signs in, a
 * one-year token. PRoot shares the phone's network, so the browser's localhost redirect usually
 * reaches the CLI and the token appears on its own; a paste-the-code box is there as a fallback.
 */
object ClaudeCode {
    private const val TOKEN_FILE = "/root/.cc_token"
    private const val OUT = "/root/.cc_login_out"
    private const val FIFO = "/root/.cc_login_fifo"

    private fun esc(s: String) = s.replace("'", "'\\''")

    suspend fun installed(context: Context): Boolean =
        Shell.run(context, "command -v claude >/dev/null 2>&1 && echo yes || echo no", 60_000).stdout.contains("yes")

    suspend fun connected(context: Context): Boolean =
        Shell.run(context, "[ -s $TOKEN_FILE ] && echo yes || echo no", 30_000).stdout.contains("yes")

    /** Install the CLI (Node is already in Arin's Linux). Returns an error message, or null on success. */
    suspend fun install(context: Context, log: (String) -> Unit): String? {
        log("Installing Claude Code with npm (this can take a few minutes)…")
        val r = Shell.run(
            context,
            "export npm_config_yes=true; " +
                "npm install -g @anthropic-ai/claude-code --no-fund --no-audit 2>&1 | tail -n 20; " +
                "apt-get install -y ripgrep >/dev/null 2>&1 || true; " +
                "command -v claude >/dev/null 2>&1 && echo ARIN_OK || echo ARIN_FAIL",
            timeoutMs = 20 * 60_000L,
        )
        r.stdout.lines().filter { it.isNotBlank() && !it.contains("ARIN_OK") && !it.contains("ARIN_FAIL") }
            .takeLast(12).forEach(log)
        return if (r.stdout.contains("ARIN_OK")) { log("Claude Code installed ✓"); null }
        else "Install failed. " + (r.stderr.ifBlank { r.stdout }).takeLast(300)
    }

    /** Start the browser login in the background. Returns the current output (should hold the OAuth URL). */
    suspend fun startLogin(context: Context): String {
        Shell.run(
            context,
            "pkill -f 'claude setup-token' 2>/dev/null; rm -f $OUT $FIFO; mkfifo $FIFO; " +
                "( sleep 1800 > $FIFO & ) ; " +
                "setsid bash -c 'claude setup-token < $FIFO > $OUT 2>&1' >/dev/null 2>&1 & echo started",
            60_000,
        )
        delay(2800)
        return loginOutput(context)
    }

    suspend fun loginOutput(context: Context): String =
        Shell.run(context, "cat $OUT 2>/dev/null", 30_000).stdout

    /** The OAuth URL printed by the login, if any. */
    fun urlIn(output: String): String? =
        Regex("https://[\\w.-]*claude[\\w./?=&%+-]*", RegexOption.IGNORE_CASE).find(output)?.value

    /** Feed the pasted code into the waiting login process. */
    suspend fun pasteCode(context: Context, code: String) {
        Shell.run(context, "printf '%s\\n' '${esc(code.trim())}' > $FIFO 2>/dev/null; echo ok", 30_000)
    }

    /** Look for the one-year token in the login output and save it. Returns true if captured. */
    suspend fun captureToken(context: Context): Boolean {
        val token = tokenIn(loginOutput(context)) ?: return false
        return saveToken(context, token)
    }

    private fun tokenIn(output: String): String? =
        Regex("sk-ant-[A-Za-z0-9_-]{20,}").find(output)?.value

    /** Save a token the person pasted (from `claude setup-token` run anywhere). */
    suspend fun saveToken(context: Context, token: String): Boolean {
        val t = tokenIn(token) ?: token.trim()
        if (t.isBlank()) return false
        return Shell.run(context, "printf '%s' '${esc(t)}' > $TOKEN_FILE && echo ok", 30_000).stdout.contains("ok")
    }

    suspend fun logout(context: Context) {
        Shell.run(context, "rm -f $TOKEN_FILE; pkill -f 'claude setup-token' 2>/dev/null; echo ok", 60_000)
    }

    /** Run a prompt non-interactively and return Claude Code's answer. */
    suspend fun ask(context: Context, prompt: String): String {
        val r = Shell.run(
            context,
            "unset ANTHROPIC_API_KEY; export CLAUDE_CODE_OAUTH_TOKEN=\"$(cat $TOKEN_FILE 2>/dev/null)\"; " +
                "cd /root/files 2>/dev/null || cd /root; " +
                "claude -p '${esc(prompt)}' 2>&1",
            timeoutMs = 10 * 60_000L,
        )
        return (r.stdout.ifBlank { r.stderr }).ifBlank { r.error ?: "No output from Claude Code." }
    }
}
