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

    /**
     * Install the CLI. Returns an error message, or null on success.
     *
     * Not with `npm install -g`: the npm package now pulls in a ~380 MB native binary, and npm runs out of
     * memory unpacking it under PRoot on a phone ("Aborted"). Instead fetch that same platform package
     * straight from the npm registry with curl and stream just the binary out of it with tar. If that fails,
     * fall back to Anthropic's official native installer.
     */
    suspend fun install(context: Context, log: (String) -> Unit): String? {
        log("Installing Claude Code (about 100 MB download, a few minutes)…")
        val script = """
            set -o pipefail
            case "${'$'}(uname -m)" in
              aarch64|arm64) P=linux-arm64 ;;
              x86_64|amd64) P=linux-x64 ;;
              *) echo "This phone's CPU (${'$'}(uname -m)) has no Claude Code build."; echo ARIN_FAIL; exit 0 ;;
            esac
            V=${'$'}(curl -fsSL --retry 3 https://registry.npmjs.org/@anthropic-ai/claude-code/latest | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)
            ok=0
            if [ -n "${'$'}V" ]; then
              echo "Downloading Claude Code ${'$'}V (${'$'}P)…"
              rm -rf /tmp/cc && mkdir -p /tmp/cc
              if curl -fL --retry 3 --retry-delay 3 -sS "https://registry.npmjs.org/@anthropic-ai/claude-code-${'$'}P/-/claude-code-${'$'}P-${'$'}V.tgz" \
                   | tar -xz -C /tmp/cc package/claude; then
                install -m 755 /tmp/cc/package/claude /usr/local/bin/claude && ok=1
              fi
              rm -rf /tmp/cc
            fi
            if [ "${'$'}ok" != 1 ]; then
              echo "Registry download failed — trying Anthropic's installer…"
              curl -fsSL https://claude.ai/install.sh | bash 2>&1 | tail -n 5
              [ -x /root/.local/bin/claude ] && ln -sf /root/.local/bin/claude /usr/local/bin/claude
            fi
            # Remove a broken npm attempt so it can't shadow the working binary.
            rm -f /usr/local/lib/node_modules/.bin/claude 2>/dev/null
            apt-get install -y ripgrep >/dev/null 2>&1 || true
            if claude --version 2>&1 | head -n 2; then echo ARIN_OK; else echo ARIN_FAIL; fi
        """.trimIndent()
        val r = Shell.run(context, script, timeoutMs = 25 * 60_000L)
        r.stdout.lines().filter { it.isNotBlank() && !it.contains("ARIN_OK") && !it.contains("ARIN_FAIL") }
            .takeLast(12).forEach(log)
        return if (r.stdout.contains("ARIN_OK")) { log("Claude Code installed ✓"); null }
        else "Install failed. " + (r.stdout.ifBlank { r.stderr }).trim().takeLast(300)
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

    /** A custom Anthropic-compatible backend (e.g. DeepSeek), used instead of the Pro login. */
    data class Backend(val base: String, val key: String, val model: String)

    /** Run a prompt non-interactively and return the answer. With [custom], the brain is that API. */
    suspend fun ask(context: Context, prompt: String, custom: Backend? = null): String {
        val setup = if (custom != null && custom.base.isNotBlank() && custom.key.isNotBlank()) {
            val model = custom.model.takeIf { it.isNotBlank() }?.let {
                "export ANTHROPIC_MODEL='${esc(it)}'; export ANTHROPIC_DEFAULT_OPUS_MODEL='${esc(it)}'; " +
                    "export ANTHROPIC_DEFAULT_SONNET_MODEL='${esc(it)}'; export ANTHROPIC_DEFAULT_HAIKU_MODEL='${esc(it)}'; "
            } ?: ""
            "unset CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY; " +
                "export ANTHROPIC_BASE_URL='${esc(custom.base)}'; export ANTHROPIC_AUTH_TOKEN='${esc(custom.key)}'; $model"
        } else {
            "unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_MODEL; " +
                "export CLAUDE_CODE_OAUTH_TOKEN=\"$(cat $TOKEN_FILE 2>/dev/null)\"; "
        }
        val r = Shell.run(
            context,
            setup + "cd /root/files 2>/dev/null || cd /root; claude -p '${esc(prompt)}' 2>&1",
            timeoutMs = 10 * 60_000L,
        )
        return (r.stdout.ifBlank { r.stderr }).ifBlank { r.error ?: "No output from Claude Code." }
    }
}
