package com.arin.analyst

import android.content.Context
import com.arin.ArinApp
import com.arin.CrashLog
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Puts the analyst lane on the phone, idempotently, and repairs it when it drifts.
 *
 * Everything here lives inside the app's own Linux:
 *  - `/root/.arin/analyst.sh`            one-shot headless question, stamped, hedged, timed out
 *  - `/root/.arin/analyst-mcp.mjs`       the `analyst_ask` tool (stdio)
 *  - `/root/.arin/mcp-bridge.mjs`        the same tool over http on port 8791
 *  - `/root/.arin/cc-mcp.json`           registration as server `analyst` (first copy kept as .bak)
 *  - `/root/.arin/analyst-mcp-up.sh`     restart helper, called on every app start
 *  - `/root/.claude/CLAUDE.md`           standing rules (marker block, so it is safe to re-run)
 *  - skills `arin-analyst` and the analyst section of `reverse-engineer-anything`
 *
 * The skill file is copied byte-identically into the rea npm package too, so REA never flags it stale.
 */
object AnalystSetup {

    private const val BASE = "/root/.arin"
    private const val MARK_BEGIN = "<!-- analyst:begin -->"
    private const val MARK_END = "<!-- analyst:end -->"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Called from [ArinApp.onCreate]: re-stage the files and lift the bridge again after a reboot. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        ArinApp.scope.launch(Dispatchers.IO) {
            runCatching {
                if (!Linux.installed(app)) return@launch
                ensure(app)
                up(app)
            }.onFailure { CrashLog.record(app, it, "analyst start-up (kept running)") }
        }
    }

    fun ensureAsync(context: Context) {
        val app = context.applicationContext
        ArinApp.scope.launch(Dispatchers.IO) {
            runCatching { ensure(app) }.onFailure { CrashLog.record(app, it, "analyst setup (kept running)") }
        }
    }

    /** Lifts the bridge again (also used by the one-tap repair). */
    fun restartAsync(context: Context) {
        val app = context.applicationContext
        ArinApp.scope.launch(Dispatchers.IO) { runCatching { up(app) } }
    }

    /** Puts cc-mcp.json back the way it was before the analyst server was added. */
    fun restoreAsync(context: Context) {
        val app = context.applicationContext
        ArinApp.scope.launch(Dispatchers.IO) {
            runCatching {
                val root = Linux.rootfs(app)
                val bak = File(root, "$BASE/cc-mcp.json.bak")
                val live = File(root, "$BASE/cc-mcp.json")
                if (bak.exists()) {
                    bak.copyTo(live, overwrite = true)
                    up(app)
                }
            }
        }
    }

    private var staged = false

    @Synchronized
    suspend fun ensure(context: Context) {
        if (!Linux.installed(context)) return
        val root = Linux.rootfs(context)
        val stamp = File(root, "$BASE/.staged")
        val version = ArinApp.versionLabel(context)
        if (staged && stamp.readText().orEmpty() == version) return

        val dir = File(root, BASE).apply { mkdirs() }
        copyAsset(context, "analyst/analyst.sh", File(dir, "analyst.sh"), executable = true)
        copyAsset(context, "analyst/analyst-mcp.mjs", File(dir, "analyst-mcp.mjs"), executable = false)
        copyAsset(context, "analyst/mcp-bridge.mjs", File(dir, "mcp-bridge.mjs"), executable = false)
        copyAsset(context, "analyst/analyst-mcp-up.sh", File(dir, "analyst-mcp-up.sh"), executable = true)
        File(dir, "analyst.log").takeIf { !it.exists() }?.writeText("")
        File(dir, "analyst.count").takeIf { !it.exists() }?.writeText("0")

        registerServer(File(dir, "cc-mcp.json"))
        writeRules(root)
        writeSkills(context, root)
        stamp.writeText(version)
        staged = true
    }

    /** Adds the `analyst` server to cc-mcp.json, keeping the previous file once as .bak. */
    private fun registerServer(config: File) {
        runCatching {
            val old = if (config.exists()) runCatching { Json.parseToJsonElement(config.readText()).jsonObject }.getOrNull() else null
            if (config.exists() && !File(config.parentFile, "cc-mcp.json.bak").exists()) {
                config.copyTo(File(config.parentFile, "cc-mcp.json.bak"), overwrite = true)
            }
            val servers = (old?.get("mcpServers") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            servers["analyst"] = buildJsonObject {
                put("type", "sse")
                put("url", "http://127.0.0.1:8791/sse")
            }
            config.writeText(json.encodeToString(JsonObject(old.orEmpty() + ("mcpServers" to JsonObject(servers)))))
        }
    }

    private fun writeRules(root: File) {
        val file = File(root, "root/.claude/CLAUDE.md").apply { parentFile?.mkdirs() }
        val block = runCatching { readAssetBlock("analyst/claude-md-section.md") }.getOrNull() ?: return
        val current = if (file.exists()) file.readText() else ""
        val kept = if (current.contains(MARK_BEGIN) && current.contains(MARK_END)) {
            current.substringBefore(MARK_BEGIN) + current.substringAfter(MARK_END)
        } else {
            current
        }
        file.writeText(kept.trimEnd() + "\n\n" + MARK_BEGIN + "\n" + block.trimEnd() + "\n" + MARK_END + "\n")
    }

    private suspend fun writeSkills(context: Context, root: File) {
        val skill = runCatching { readAssetBlock("analyst/arin-analyst.SKILL.md") }.getOrNull() ?: return
        val section = runCatching { readAssetBlock("analyst/rea-analyst-section.md") }.getOrNull() ?: ""
        val targets = listOf(
            File(root, "root/.claude/skills/arin-analyst/SKILL.md"),
            File(root, "root/.npm-global/lib/node_modules/rea/skills/arin-analyst/SKILL.md"),
            File(root, "root/usr/local/lib/node_modules/rea/skills/arin-analyst/SKILL.md"),
            File(root, "root/usr/lib/node_modules/rea/skills/arin-analyst/SKILL.md"),
        )
        targets.forEach { f ->
            if (f.parentFile?.parentFile?.exists() != true) return@forEach
            f.parentFile?.mkdirs()
            // Byte-identical everywhere, so REA never flags the copy stale.
            f.writeBytes(skill.toByteArray())
        }
        listOf(
            File(root, "root/.claude/skills/reverse-engineer-anything/SKILL.md"),
            File(root, "root/.npm-global/lib/node_modules/rea/skills/reverse-engineer-anything/SKILL.md"),
        ).forEach { f ->
            if (!f.exists() || section.isBlank()) return@forEach
            val text = f.readText()
            val kept = if (text.contains(MARK_BEGIN) && text.contains(MARK_END)) {
                text.substringBefore(MARK_BEGIN) + text.substringAfter(MARK_END)
            } else {
                text
            }
            f.writeText(kept.trimEnd() + "\n\n" + MARK_BEGIN + "\n" + section.trimEnd() + "\n" + MARK_END + "\n")
        }
        // `rea setup` overwrites the bundled skill; sync both copies again straight away.
        runCatching { Linux.run(context, "cd /root && (rea sync 2>/dev/null || true)", timeoutMs = 60_000L) }
    }

    private suspend fun up(context: Context) {
        withContext(Dispatchers.IO) {
            if (!Linux.installed(context)) return@withContext
            runCatching { Linux.run(context, "bash $BASE/analyst-mcp-up.sh", timeoutMs = 40_000L) }
        }
    }

    private fun copyAsset(context: Context, asset: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()
        val bytes = context.assets.open(asset).use { it.readBytes() }
        if (!dest.exists() || !dest.readBytes().contentEquals(bytes)) {
            dest.writeBytes(bytes)
        }
        if (executable) dest.setExecutable(true, false)
    }

    private fun readAssetBlock(asset: String): String = runCatching {
        ArinApp.instance.assets.open(asset).use { it.readBytes() }.toString(Charsets.UTF_8)
    }.getOrDefault("")
}
