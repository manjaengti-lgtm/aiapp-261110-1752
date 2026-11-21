package com.arin.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arin.ArinApp
import com.arin.app.ReplyService
import com.arin.data.Attachment
import com.arin.data.Attachments
import com.arin.data.Chat
import com.arin.data.ChatStore
import com.arin.data.ChatSummary
import com.arin.data.Memory
import com.arin.data.MemoryStore
import com.arin.data.Message
import com.arin.data.Project
import com.arin.data.ProjectStore
import com.arin.data.Settings
import com.arin.data.Source
import com.arin.data.ThemeMode
import com.arin.llm.DefaultModels
import com.arin.llm.Delta
import com.arin.llm.Llm
import com.arin.llm.ModelOption
import com.arin.llm.Prompt
import com.arin.llm.Provider
import com.arin.llm.Turn
import com.arin.llm.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything the chat, the chats list, projects and Settings share. One instance for the whole app. */
/** Where a build runs. */
enum class BuildTarget { BUILD_TAB, CHAT, PHONE, AUTO, CODESPACE }

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    private val projectStore = ProjectStore(app)
    private val memoryStore = MemoryStore(app)
    val settings = Settings(app)

    // ---- Current chat ----
    var chatId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("New chat")
        private set
    /** The project the current chat belongs to, or null. */
    var chatProject by mutableStateOf<Project?>(null)
        private set
    val messages = mutableStateListOf<Message>()

    /** Files waiting to go with the next message. */
    val pending = mutableStateListOf<Attachment>()

    /** Text from another app's Share button, waiting to be placed in the message box. */
    var sharedText by mutableStateOf<String?>(null)

    /** Index of the sent message being edited, or null. */
    var editing by mutableStateOf<Int?>(null)
        private set

    /**
     * A reply being worked on in one chat: its live text, thinking, status, steps and steering messages. Each chat
     * has its own, so several chats can think, code and run commands at the same time; switching chats doesn't stop them.
     */
    inner class Run(val chat: String, val chatTitle: String, val base: List<Message>) {
        var liveText by mutableStateOf("")
        var liveThinking by mutableStateOf("")
        var status by mutableStateOf<String?>(null)
        val liveCommands = mutableStateListOf<com.arin.data.CommandRun>()
        val steering = mutableStateListOf<String>()
        @Volatile var steerPending = false
        var job: Job? = null
        var checkpointJob: Job? = null
    }

    /** Replies running now, by chat id (a temporary chat uses [TEMP_CHAT]). */
    val runs = androidx.compose.runtime.mutableStateMapOf<String, Run>()

    private fun chatKey(): String = chatId ?: TEMP_CHAT

    /** The open chat's running reply, if any. */
    fun runFor(key: String? = chatKey()): Run? = key?.let { runs[it] }

    /** True while the OPEN chat is working (other chats may be working too). */
    val streaming: Boolean get() = runFor() != null
    val liveText: String get() = runFor()?.liveText.orEmpty()
    val liveThinking: String get() = runFor()?.liveThinking.orEmpty()

    /** Status outside any reply (e.g. loading a model); a running reply's own status takes precedence. */
    private var idleStatus by mutableStateOf<String?>(null)
    /** What Arin is doing right now when it isn't writing, such as "Searching the web for …". */
    var status: String?
        get() = runFor()?.status ?: idleStatus
        private set(value) { idleStatus = value }
    var notice by mutableStateOf<String?>(null)

    /** Details of the last crash, shown once when Arin opens again. */
    var crashReport by mutableStateOf(com.arin.CrashLog.takeReport(app))

    fun dismissCrash() { crashReport = null }

    /** Set when a reply finishes; voice mode reads it aloud. */
    var lastFinished by mutableStateOf<Message?>(null)
        private set

    // ---- Chats and projects ----
    val chats = mutableStateListOf<ChatSummary>()
    var query by mutableStateOf("")
        private set
    val projects = mutableStateListOf<Project>()
    val memories = mutableStateListOf<Memory>()

    // ---- Settings ----
    var provider by mutableStateOf(settings.provider)
        private set
    var models by mutableStateOf(DefaultModels.of(settings.provider))
        private set
    var model by mutableStateOf(settings.model(settings.provider) ?: models.first().id)
        private set
    var theme by mutableStateOf(settings.theme)
        private set
    var showThinking by mutableStateOf(settings.showThinking)
        private set
    var webSearch by mutableStateOf(settings.webSearch)
        private set
    var memoryOn by mutableStateOf(settings.memoryOn)
        private set

    /**
     * Save credits WITHOUT losing memory. The model still receives the whole conversation every turn.
     * Savings come from two lossless tricks instead of trimming:
     *  1. Prompt caching (see Llm.stream cache flag): the repeated conversation prefix is billed at a
     *     fraction (~10%) on every provider that caches it (Claude cache_control; DeepSeek/OpenAI/Gemini
     *     cache automatically as long as the prefix stays stable — which it does, since we never trim).
     *  2. Duplicate-block collapsing (dedupeTurns): when the exact same large block (a re-pasted file,
     *     a re-fetched page) appears in several turns, only the LAST full copy is sent; earlier identical
     *     copies become a one-line marker. No unique information is dropped, so the model still has
     *     every bit it needs to build the project.
     */
    /** Test apps on an Android emulator after building (Settings → Build apps). */
    var testApps by mutableStateOf(settings.testApps)
        private set

    fun toggleTestApps(on: Boolean) {
        testApps = on
        settings.testApps = on
        notice = if (on) "Apps will be opened and tested on an Android emulator after each build (adds a few minutes)."
            else "Apps won't be tested after building (faster builds)."
    }

    var saveCredits by mutableStateOf(settings.saveCredits)
        private set

    fun toggleSaveCredits(on: Boolean) {
        saveCredits = on
        settings.saveCredits = on
        notice = if (on) "Save credits is on: the whole chat is still sent, but the repeated part is cached and re-pasted files are sent once, so it costs far fewer tokens."
            else "Save credits is off: caching and duplicate-collapsing are disabled."
    }

    /**
     * Lossless: if the same large text block (>= 600 chars) shows up in more than one turn, keep it only in
     * its LAST occurrence and replace the earlier identical copies with a short marker. The model keeps the
     * full latest copy plus the note that an identical one appeared earlier, so no real context is lost.
     */
    private fun dedupeTurns(turns: List<Turn>): List<Turn> {
        if (turns.size <= 2) return turns
        val minLen = 600
        // Last turn index in which each big block appears.
        val lastSeen = HashMap<String, Int>()
        turns.forEachIndexed { i, t ->
            t.text.split("\n\n").forEach { block ->
                val b = block.trim()
                if (b.length >= minLen) lastSeen[b] = i
            }
        }
        if (lastSeen.isEmpty()) return turns
        return turns.mapIndexed { i, t ->
            if (!t.text.contains("\n\n")) return@mapIndexed t
            var changed = false
            val rebuilt = t.text.split("\n\n").joinToString("\n\n") { block ->
                val b = block.trim()
                if (b.length >= minLen && lastSeen[b] != null && lastSeen[b] != i) {
                    changed = true
                    "[identical block shown later in this chat — omitted here to save tokens]"
                } else block
            }
            if (changed) t.copy(text = rebuilt) else t
        }
    }

    /** Custom instructions (Settings): about the person, and how to answer. */
    var aboutYou by mutableStateOf(settings.aboutYou)
        private set
    var howToAnswer by mutableStateOf(settings.howToAnswer)
        private set
    var replyStyle by mutableStateOf(settings.replyStyle)
        private set

    /** True while the voice screen is open: replies are spoken aloud, so answer conversationally. */
    var voiceMode = false

    private var voicePreview: com.arin.voice.NeuralVoice? = null

    /** Speak a one-line sample in the currently chosen voice engine, so the person can hear it from Settings. */
    fun previewVoice() {
        runCatching { voicePreview?.close() }
        val nv = com.arin.voice.NeuralVoice(getApplication<android.app.Application>().cacheDir)
        nv.voice = settings.voiceName
        if (settings.voiceEngine == "openai" && settings.voiceKey.isNotBlank()) {
            nv.engine = com.arin.voice.NeuralVoice.Engine.OpenAI
            nv.apiKey = settings.voiceKey
        }
        voicePreview = nv
        nv.start(
            onClipDone = {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (voicePreview === nv) { runCatching { nv.close() }; voicePreview = null }
                }, 400)
            },
            onFallback = {},
        )
        nv.speak("Hey! This is the voice I'll use when we talk. How do I sound?")
    }

    fun saveCustomInstructions(about: String, how: String) {
        aboutYou = about.trim()
        howToAnswer = how.trim()
        settings.aboutYou = aboutYou
        settings.howToAnswer = howToAnswer
        notice = "Custom instructions saved"
    }

    fun chooseStyle(style: String) {
        replyStyle = style
        settings.replyStyle = style
    }

    /** Deep research: many searches and page reads, then a report with sources. */
    var deepResearch by mutableStateOf(false)
        private set

    fun toggleDeepResearch(on: Boolean) {
        deepResearch = on
        if (on) notice = "Deep research is on: Arin searches and reads many pages, then writes a report."
    }

    /** A temporary chat isn't saved, doesn't use memory and doesn't appear in the list. */
    var temporary by mutableStateOf(false)
        private set

    /** Whether the open chat is pinned to the top of the list. */
    private var chatPinned = false

    fun togglePin(id: String, pinned: Boolean) {
        if (id == chatId) chatPinned = pinned
        viewModelScope.launch(Dispatchers.IO) {
            store.setPinned(id, pinned)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    var terminalOn by mutableStateOf(settings.terminalOn)
        private set
    var terminalAsk by mutableStateOf(settings.terminalAsk)
        private set

    private val noCommands = mutableStateListOf<com.arin.data.CommandRun>()
    /** Commands run so far in the open chat's reply (shown live). */
    val liveCommands: List<com.arin.data.CommandRun> get() = runFor()?.liveCommands ?: noCommands

    /** A command or tool call waiting for the person to allow or refuse it. */
    var commandToApprove by mutableStateOf<String?>(null)
        private set
    /** True when [commandToApprove] is an MCP tool call rather than a shell command. */
    var approvingTool by mutableStateOf(false)
        private set

    private fun shorten(text: String, max: Int = 20_000): String =
        if (text.length <= max) text
        else text.take(4_000) + "\n…(${text.length - max} characters skipped)…\n" + text.takeLast(max - 4_000)

    // ---- MCP servers ----

    var mcpServers by mutableStateOf(settings.mcpServers)
        private set

    /** Status line per server name, filled by [checkServer]. */
    val serverStatus = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun saveServer(server: com.arin.mcp.McpServer) {
        mcpServers = mcpServers.filterNot { it.name == server.name } + server
        settings.mcpServers = mcpServers
        com.arin.mcp.Mcp.forget(server.name)
    }

    fun removeServer(server: com.arin.mcp.McpServer) {
        mcpServers = mcpServers.filterNot { it.name == server.name }
        settings.mcpServers = mcpServers
        com.arin.mcp.Mcp.forget(server.name)
        serverStatus.remove(server.name)
    }

    fun toggleServer(server: com.arin.mcp.McpServer, on: Boolean) = saveServer(server.copy(enabled = on))

    /** Connects to [server] (starting it in Termux if needed) and records the result. */
    fun checkServer(server: com.arin.mcp.McpServer) {
        serverStatus[server.name] = "Connecting…"
        viewModelScope.launch {
            com.arin.mcp.Mcp.forget(server.name)
            val problems = com.arin.mcp.Mcp.connectAll(getApplication(), listOf(server.copy(enabled = true)), canStart = terminalOn)
            serverStatus[server.name] = com.arin.mcp.Mcp.status(server.name) ?: problems.firstOrNull() ?: "Not reachable"
        }
    }

    /**
     * Adds REA, turns on the terminal, and starts a chat where the model installs and checks it,
     * the way the setup is done with Claude Code.
     */
    // ---- Built-in Linux ----

    var linuxInstalled by mutableStateOf(com.arin.terminal.Linux.installed(app))
        private set
    var linuxReady by mutableStateOf(com.arin.terminal.Linux.nodeReady(app))
        private set
    /** True while installing Linux or REA. */
    var linuxBusy by mutableStateOf(false)
        private set
    /** Recent progress lines from installing. */
    val linuxLog = mutableStateListOf<String>()

    private fun logLine(line: String) {
        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            linuxLog += line
            while (linuxLog.size > 40) linuxLog.removeAt(0)
        }
    }

    /** Installs Ubuntu inside Arin (and REA too, with [withRea]). Keeps going if the screen closes. */
    fun installLinux(withRea: Boolean = false) {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                com.arin.terminal.Linux.install(app) { logLine(it) }
                linuxInstalled = true
                linuxReady = true
                toggleTerminal(true)
                if (withRea) installReaInLinux(app)
                notice = if (withRea) "Linux and REA are installed" else "Linux is installed"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "Linux install failed: ${e.message}"
            } finally {
                linuxInstalled = com.arin.terminal.Linux.installed(app)
                linuxReady = com.arin.terminal.Linux.nodeReady(app)
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    /** Stops the old REA bridge, reinstalls REA with the PRoot fixes, and reconnects. */
    fun repairRea() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                installReaInLinux(app)
                notice = "REA repaired"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "REA repair failed: ${e.message}"
            } finally {
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    private suspend fun installReaInLinux(app: Application) {
        logLine("Stopping any old REA bridge…")
        com.arin.mcp.Mcp.forget(com.arin.mcp.Mcp.REA.name)
        com.arin.terminal.Linux.run(app, "pkill -f mcp-bridge.mjs; sleep 1; echo stopped", timeoutMs = 30_000)
        val r = com.arin.terminal.Linux.installRea(app) { logLine(it) }
        if (r.exitCode != 0 || r.error != null) throw IllegalStateException("REA didn't install: " + (r.error ?: r.combined().takeLast(400)))
        // For APKs: JDK, JADX and apktool (REA's Android tools). Keep going if this part fails.
        val tools = com.arin.terminal.Linux.installAndroidTools(app) { logLine(it) }
        if (tools.exitCode != 0 || tools.error != null) logLine("Android tools didn't install fully; APK analysis may not work: " + (tools.error ?: tools.combined().takeLast(200)))
        logLine("Installing Arin's MCP bridge…")
        com.arin.mcp.Mcp.installBridge(app)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            saveServer(com.arin.mcp.Mcp.REA.copy(enabled = true))
        }
        logLine("Starting REA and connecting…")
        val problems = com.arin.mcp.Mcp.connectAll(app, listOf(com.arin.mcp.Mcp.REA), canStart = true)
        val status = com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name)
        serverStatus[com.arin.mcp.Mcp.REA.name] = status ?: problems.firstOrNull() ?: "Not reachable"
        logLine(status?.let { "REA is connected: $it" } ?: ("REA didn't connect: " + problems.joinToString("; ")))
    }

    var devToolsReady by mutableStateOf(com.arin.terminal.Linux.devToolsReady(app))
        private set

    /** Whether apps can be compiled to APKs in the built-in Linux. */
    var androidBuildReady by mutableStateOf(com.arin.terminal.Linux.androidBuildReady(app))
        private set

    /** Installs Java, Kotlin, Gradle and the Android SDK in the built-in Linux. */
    fun installDevTools() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                if (!com.arin.terminal.Linux.nodeReady(app)) com.arin.terminal.Linux.install(app) { logLine(it) }
                linuxInstalled = true
                linuxReady = true
                val r = com.arin.terminal.Linux.installDevTools(app) { logLine(it) }
                if (r.exitCode != 0 || r.error != null) throw IllegalStateException(r.error ?: r.combined().takeLast(400))
                devToolsReady = true
                // The Android SDK too, so apps compile to APKs right on the phone.
                val a = com.arin.terminal.Linux.installAndroidBuild(app) { logLine(it) }
                androidBuildReady = com.arin.terminal.Linux.androidBuildReady(app)
                toggleTerminal(true)
                notice = if (androidBuildReady) "Java, Kotlin, Gradle and the Android SDK are installed: apps now build on this phone too"
                    else "Java, Kotlin and Gradle are installed, but the Android SDK part didn't finish: " +
                        (a.error ?: a.combined().lines().lastOrNull { it.isNotBlank() } ?: "") + " Apps still build on GitHub."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "Developer tools install failed: ${e.message}"
            } finally {
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    fun removeLinux() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        ArinApp.scope.launch {
            com.arin.mcp.Mcp.forget(com.arin.mcp.Mcp.REA.name)
            com.arin.terminal.Linux.remove(app)
            linuxInstalled = false
            linuxReady = false
            devToolsReady = false
            androidBuildReady = false
            linuxBusy = false
            linuxLog.clear()
            notice = "Linux removed"
        }
    }

    // ---- Claude Code: run Anthropic's CLI in Arin's Linux on the person's own Pro/Max login ----

    var ccInstalled by mutableStateOf(false)
    var ccConnected by mutableStateOf(false)
    var ccBusy by mutableStateOf(false)
    var ccLoginUrl by mutableStateOf<String?>(null)
    var ccOutput by mutableStateOf("")
    val ccLog = mutableStateListOf<String>()

    private fun ccLogLine(line: String) {
        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ccLog += line
            while (ccLog.size > 30) ccLog.removeAt(0)
        }
    }

    fun refreshClaudeCode() {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            loadCcChat()
            ccInstalled = com.arin.terminal.Linux.installed(app) && com.arin.claudecode.ClaudeCode.installed(app)
            ccConnected = ccInstalled && com.arin.claudecode.ClaudeCode.connected(app)
            if (ccInstalled && !ccRunning) applyCcLinux()
        }
    }

    fun installClaudeCode() {
        if (ccBusy || linuxBusy) return
        val app = getApplication<Application>()
        ccBusy = true
        ccLog.clear()
        ArinApp.scope.launch {
            try {
                if (!com.arin.terminal.Linux.installed(app)) {
                    ccLogLine("Setting up Linux first…")
                    com.arin.terminal.Linux.install(app) { ccLogLine(it) }
                }
                val err = com.arin.claudecode.ClaudeCode.install(app) { ccLogLine(it) }
                ccInstalled = com.arin.claudecode.ClaudeCode.installed(app)
                if (err != null) notice = err else applyCcLinux()
            } catch (e: Exception) {
                notice = "Claude Code install failed: ${e.message}"
            } finally {
                ccBusy = false
            }
        }
    }

    /** What the sign-in is doing right now (or the error it showed), for Settings. */
    var ccLoginStatus by mutableStateOf("")

    /** The last meaningful line Claude Code's sign-in screen shows (skips borders and spinners). */
    private fun lastLoginLine(out: String): String =
        out.lines().map { it.trim() }
            .filter { line -> line.length > 3 && line.any { it.isLetter() } && !line.all { it in "─│╭╮╰╯ " } }
            .lastOrNull().orEmpty().take(200)

    fun startCcLogin() {
        if (ccBusy) return
        val app = getApplication<Application>()
        ccBusy = true
        ccLoginUrl = null
        ccLoginStatus = "Starting Claude Code's sign-in…"
        ArinApp.scope.launch {
            try {
                var out = com.arin.claudecode.ClaudeCode.startLogin(app)
                // Claude Code starts slowly in Arin's Linux: keep looking for the link for up to a minute.
                var tries = 0
                ccLoginUrl = com.arin.claudecode.ClaudeCode.urlIn(out)
                while (ccLoginUrl == null && tries++ < 30) {
                    kotlinx.coroutines.delay(2000)
                    out = com.arin.claudecode.ClaudeCode.loginOutput(app)
                    ccLoginUrl = com.arin.claudecode.ClaudeCode.urlIn(out)
                }
                if (ccLoginUrl == null) {
                    ccLoginStatus = "Couldn't get the sign-in link. " + lastLoginLine(out).ifBlank { "Is Claude Code installed?" }
                    return@launch
                }
                ccLoginStatus = "Open the sign-in page, sign in with Google, then copy the code it shows and paste it here."
                // Wait for the code to be pasted and the token to appear (up to 15 minutes).
                repeat(300) {
                    kotlinx.coroutines.delay(3000)
                    out = com.arin.claudecode.ClaudeCode.loginOutput(app)
                    if (com.arin.claudecode.ClaudeCode.captureToken(app)) {
                        ccConnected = true
                        ccLoginUrl = null
                        ccLoginStatus = ""
                        applyCcLinux()
                        notice = "Signed in to Claude Code ✓"
                        return@launch
                    }
                    val line = lastLoginLine(out)
                    if (line.contains("error", true) || line.contains("invalid", true) || line.contains("failed", true)) ccLoginStatus = line
                }
                ccLoginStatus = "Sign-in timed out. Tap the button to try again."
            } catch (e: Exception) {
                ccLoginStatus = "Sign-in error: ${e.message}"
            } finally {
                ccBusy = false
            }
        }
    }

    fun pasteCcCode(code: String) {
        val app = getApplication<Application>()
        ccLoginStatus = "Checking the code…"
        ArinApp.scope.launch { com.arin.claudecode.ClaudeCode.pasteCode(app, code) }
    }

    fun saveCcToken(token: String) {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            ccConnected = com.arin.claudecode.ClaudeCode.saveToken(app, token)
            if (ccConnected) applyCcLinux()
            notice = if (ccConnected) "Connected to Claude Code ✓" else "That didn't look like a valid token."
        }
    }

    fun ccLogout() {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            com.arin.claudecode.ClaudeCode.logout(app)
            ccConnected = false
            applyCcLinux()
        }
    }

    // ---- Claude Code chat: the brain is the person's custom API or Claude Pro login; Claude Code is the tool ----

    data class CcMessage(val fromUser: Boolean, val text: String, val steps: List<String> = emptyList(), val running: Boolean = false, val error: Boolean = false)

    val ccMessages = mutableStateListOf<CcMessage>()
    /** "pro" = Claude Pro/Max (Google account) login, "custom" = own API. Chosen in Settings. */
    var ccBackend by mutableStateOf(settings.ccBackend)
        private set
    private var ccCustomModels by mutableStateOf(settings.ccCustomModels)
    private var ccCustomModelState by mutableStateOf(settings.ccCustomModel)
    private var ccProModelState by mutableStateOf(settings.ccProModel)
    private var ccCustomReady by mutableStateOf(settings.ccCustomBase.isNotBlank() && settings.ccCustomKey.isNotBlank() && settings.ccCustomModel.isNotBlank())

    /** Models to pick from in the Claude Code header, for the login in use. */
    val ccModels: List<String> get() = if (ccBackend == "pro") com.arin.claudecode.ClaudeCode.PRO_MODELS else ccCustomModels
    val ccModel: String get() = if (ccBackend == "pro") ccProModelState else ccCustomModelState
    /** Signed in with the login chosen in Settings, so the chat can run. */
    val ccSignedIn: Boolean get() = if (ccBackend == "pro") ccConnected else ccCustomReady
    /** The custom API is connected (shown in Settings). */
    val ccApiConnected: Boolean get() = ccCustomReady
    val ccCustomModelCount: Int get() = ccCustomModels.size

    var ccConnecting by mutableStateOf(false)
    var ccConnectError by mutableStateOf<String?>(null)
    var ccRunning by mutableStateOf(false)
    private var ccJob: kotlinx.coroutines.Job? = null

    /** Keep the `claude` command in Arin's Linux on the same brain as the Claude Code tab. */
    private fun applyCcLinux() {
        val app = getApplication<Application>()
        val b = when {
            ccBackend == "pro" && ccConnected -> com.arin.claudecode.ClaudeCode.Backend("pro", "", "", ccProModelState)
            ccBackend == "custom" && ccCustomReady ->
                com.arin.claudecode.ClaudeCode.Backend(settings.ccCustomKind, settings.ccCustomBase, settings.ccCustomKey, ccCustomModelState)
            else -> null
        }
        ArinApp.scope.launch { runCatching { com.arin.claudecode.ClaudeCode.applyLinuxConfig(app, b) } }
    }

    fun chooseCcBackend(kind: String) {
        if (ccRunning) return
        ccBackend = kind
        settings.ccBackend = kind
        applyCcLinux()
    }

    /** The API wouldn't list its models, so Settings asks for the model id. */
    var ccNeedsModel by mutableStateOf(false)

    /** Connect with a URL and key (plus a model id only if the API won't list its models). */
    fun connectCcApi(baseUrl: String, key: String, typedModel: String = "") {
        if (ccConnecting || baseUrl.isBlank() || key.isBlank()) return
        ccConnecting = true
        ccConnectError = null
        val cleanKey = key.trim().replace(Regex("\\s"), "")
        ArinApp.scope.launch {
            try {
                val c = com.arin.claudecode.ClaudeCode.connect(baseUrl, cleanKey, typedModel)
                settings.ccCustomKind = c.kind
                settings.ccCustomBase = c.base
                settings.ccCustomKey = cleanKey
                settings.ccCustomModels = c.models
                ccCustomModels = c.models
                val pick = typedModel.trim().takeIf { it in c.models } ?: ccCustomModelState.takeIf { it in c.models } ?: c.models.first()
                ccCustomModelState = pick
                settings.ccCustomModel = pick
                ccCustomReady = true
                ccNeedsModel = false
                chooseCcBackend("custom")
            } catch (e: com.arin.claudecode.ClaudeCode.NeedsModel) {
                ccNeedsModel = true
                ccConnectError = e.message
            } catch (e: Exception) {
                ccConnectError = e.message ?: "Couldn't connect."
            } finally {
                ccConnecting = false
            }
        }
    }

    /** The chat tab's saved APIs with their keys, to reuse one for Claude Code without retyping. */
    fun ccSavedApiChoices(): List<Triple<String, String, Pair<String, String>>> =
        savedApis.filter { !it.free }.mapNotNull { api ->
            val key = settings.apiKey(api)
            if (key.isBlank()) null else Triple(api.name, api.baseUrl, key to api.model)
        }

    fun disconnectCcApi() {
        settings.ccCustomKey = ""
        ccCustomReady = false
        applyCcLinux()
    }

    fun selectCcModel(id: String) {
        if (ccBackend == "pro") { ccProModelState = id; settings.ccProModel = id }
        else { ccCustomModelState = id; settings.ccCustomModel = id }
        applyCcLinux()
    }

    private val ccChatFile get() = java.io.File(getApplication<Application>().filesDir, "claude_code_chat.json")

    /** Keep the Claude Code conversation across restarts, like the chat tab. */
    private fun saveCcChat() {
        runCatching {
            val arr = org.json.JSONArray(ccMessages.filter { !it.running }.map {
                org.json.JSONObject().put("u", it.fromUser).put("t", it.text).put("e", it.error)
                    .put("s", org.json.JSONArray(it.steps))
            })
            ccChatFile.writeText(arr.toString())
        }
    }

    private fun loadCcChat() {
        if (ccMessages.isNotEmpty()) return
        runCatching {
            val arr = org.json.JSONArray(ccChatFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val steps = o.optJSONArray("s")?.let { s -> (0 until s.length()).map { s.optString(it) } } ?: emptyList()
                ccMessages += CcMessage(o.optBoolean("u"), o.optString("t"), steps, false, o.optBoolean("e"))
            }
        }
    }

    fun newCcChat() {
        if (ccRunning) return
        ccMessages.clear()
        runCatching { ccChatFile.delete() }
    }

    /** Send a message. Follow-ups continue the same Claude Code conversation. */
    fun askClaudeCode(prompt: String) {
        val text = prompt.trim()
        if (ccRunning || text.isEmpty() || !ccSignedIn) return
        val app = getApplication<Application>()
        val continueChat = ccMessages.any { !it.fromUser && !it.error }
        ccMessages += CcMessage(true, text)
        ccMessages += CcMessage(false, "", running = true)
        ccRunning = true
        val backend = if (ccBackend == "pro") com.arin.claudecode.ClaudeCode.Backend("pro", "", "", ccModel)
        else com.arin.claudecode.ClaudeCode.Backend(settings.ccCustomKind, settings.ccCustomBase, settings.ccCustomKey, ccModel)
        ReplyService.start(app)
        ccJob = ArinApp.scope.launch {
            fun show(t: com.arin.claudecode.ClaudeCode.Turn, running: Boolean) {
                val idx = ccMessages.lastIndex
                if (idx >= 0) ccMessages[idx] = CcMessage(false, t.text, t.steps, running, t.error)
            }
            try {
                if (!com.arin.claudecode.ClaudeCode.startTurn(app, text, backend, continueChat)) {
                    show(com.arin.claudecode.ClaudeCode.Turn("Couldn't start Claude Code. Check Settings → Claude Code.", emptyList(), true, true), false)
                    return@launch
                }
                val deadline = System.currentTimeMillis() + 30 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(1200)
                    val t = com.arin.claudecode.ClaudeCode.readTurn(app)
                    show(t, !t.done)
                    if (t.done) {
                        if (t.text.isBlank()) show(t.copy(text = "(No answer — check the model and API key.)", error = true), false)
                        return@launch
                    }
                }
                show(com.arin.claudecode.ClaudeCode.Turn("Stopped after 30 minutes.", emptyList(), true, true), false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                show(com.arin.claudecode.ClaudeCode.Turn("Error: ${e.message}", emptyList(), true, true), false)
            } finally {
                ccRunning = false
                saveCcChat()
                ReplyService.stop(app)
            }
        }
    }

    fun stopClaudeCode() {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            com.arin.claudecode.ClaudeCode.stopTurn(app)
            ccJob?.cancel()
            val idx = ccMessages.lastIndex
            if (idx >= 0 && ccMessages[idx].running) ccMessages[idx] = ccMessages[idx].copy(running = false, text = ccMessages[idx].text.ifBlank { "Stopped." })
            ccRunning = false
            ReplyService.stop(app)
        }
    }

    // ---- Movie Maker (film studio): your model directs; free tools render; ffmpeg cuts ----

    enum class MoviePhase { Idle, Planning, Filming }
    data class MovieChatLine(val fromUser: Boolean, val text: String)

    var movieBusy by mutableStateOf(false)
    var moviePhase by mutableStateOf(MoviePhase.Idle)
    var movieStep by mutableStateOf("")            // e.g. "Writing the film — part 3 of 8"
    var movieFraction by mutableStateOf<Float?>(null) // real progress 0..1, or null while unknown
    var movieActivity by mutableStateOf("")        // live detail: "Thinking…", "Writing shots… 1.2k characters"
    var movieError by mutableStateOf<String?>(null)
    var movieProgress by mutableStateOf("")
    var movieReady by mutableStateOf(false)   // a finished movie.mp4 is available to play
    var moviePartial by mutableStateOf(false) // finished, but some shots are missing (Resume retries them)
    var movieHasProject by mutableStateOf(false)
    var movieTitle by mutableStateOf("")
    var movieSummary by mutableStateOf<com.arin.movie.MovieMaker.Summary?>(null)
    val movieLog = mutableStateListOf<String>()
    val movieChat = mutableStateListOf<MovieChatLine>()
    var movieSteering by mutableStateOf(false)
    private val movieNotes = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The model directing the film: the one selected in chat. */
    val movieDirectorLabel: String get() = model

    var movieHfToken by mutableStateOf(settings.movieHfToken)
        private set
    var movieSpace by mutableStateOf(settings.movieSpace)
        private set
    var movieSubtitles by mutableStateOf(settings.movieSubtitles)
        private set

    fun updateMovieHfToken(v: String) { movieHfToken = v; settings.movieHfToken = v }
    fun updateMovieSpace(v: String) { movieSpace = v; settings.movieSpace = v }
    fun updateMovieSubtitles(v: Boolean) { movieSubtitles = v; settings.movieSubtitles = v }

    private fun movieLogLine(line: String) {
        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) { movieLog += line; while (movieLog.size > 30) movieLog.removeAt(0) }
    }

    private fun movieStatus(step: String, fraction: Float?) {
        movieStep = step
        movieFraction = fraction
        com.arin.movie.MovieService.update(getApplication<Application>(), step, fraction)
    }

    private fun movieFail(msg: String) {
        movieError = msg
        notice = msg
    }

    /** One call to the person's own model (the director). Shows live activity while it streams. */
    private suspend fun askDirector(system: String, user: String): String {
        val p = provider
        val key = settings.key(p)
        if (p == Provider.Free) syncFreePool()
        val endpoint = if (p == Provider.Custom) com.arin.llm.CustomApi.current() else null
        // Each part is short, but leave room for thinking models; free pools keep their own limits.
        val maxTokens = if (p == Provider.Free) null else 16_000
        val sb = StringBuilder()
        var thinking = 0
        movieActivity = "Waiting for ${model}…"
        com.arin.llm.Llm.stream(p, key, model, system, listOf(com.arin.llm.Turn(true, user)), maxTokens = maxTokens, endpoint = endpoint)
            .collect {
                when (it) {
                    is com.arin.llm.Delta.Thinking -> { thinking += it.text.length; movieActivity = "Thinking… ${thinking / 100 / 10.0}k" }
                    is com.arin.llm.Delta.Text -> { sb.append(it.text); movieActivity = "Writing… ${sb.length / 100 / 10.0}k characters" }
                    else -> {}
                }
            }
        movieActivity = ""
        return sb.toString()
    }

    /** Linux + ffmpeg + python, installed once. */
    private suspend fun movieToolsReady(app: Application): Boolean {
        if (!com.arin.terminal.Linux.installed(app)) {
            movieStatus("Setting up Linux (one-time)", null)
            com.arin.terminal.Linux.install(app) { movieLogLine(it) }
        }
        if (!com.arin.movie.MovieMaker.toolsReady(app)) {
            movieStatus("Installing ffmpeg (one-time)", null)
            if (!com.arin.movie.MovieMaker.installTools(app) { movieLogLine(it) }) {
                movieFail("Couldn't install ffmpeg. Check your connection and try again.")
                return false
            }
        }
        return true
    }

    /**
     * Plan the film in parts (cast first, then the script part by part, so long scripts never overflow one
     * reply and the progress is real), then render it. Runs on the app scope with a foreground service, so it
     * keeps going with Arin in the background or the screen off.
     */
    fun makeMovie(script: String) {
        if (movieBusy || script.isBlank()) return
        val app = getApplication<Application>()
        movieBusy = true
        movieReady = false
        moviePartial = false
        movieSummary = null
        movieError = null
        movieProgress = ""
        movieLog.clear()
        movieChat.clear()
        movieNotes.clear()
        moviePhase = MoviePhase.Planning
        com.arin.movie.MovieService.start(app, "Getting ready…")
        ArinApp.scope.launch {
            var finished = false
            try {
                if (!movieToolsReady(app)) return@launch
                val parts = com.arin.movie.MovieMaker.splitScript(script)
                val steps = parts.size + 1
                movieStatus("Planning: cast and look (step 1 of $steps)", 0f)
                val castRaw = askDirector(com.arin.movie.MovieMaker.CAST, script)
                val cast = com.arin.movie.MovieMaker.parseCast(castRaw)
                    ?: com.arin.movie.MovieMaker.parseCast(askDirector(com.arin.movie.MovieMaker.CAST, script))
                if (cast == null) { movieFail("${model} didn't return a usable cast. Try again, or pick another model in chat."); return@launch }
                movieTitle = cast.title
                movieChat += MovieChatLine(false, "“${cast.title}” — ${cast.characters.joinToString { it.name }.ifBlank { "narrated" }}. " +
                    "Planning ${parts.size} part${if (parts.size == 1) "" else "s"}. Type here any time to steer.")
                val castJson = com.arin.movie.MovieMaker.castJson(cast)
                val shots = mutableListOf<com.arin.movie.MovieMaker.Shot>()
                for ((k, part) in parts.withIndex()) {
                    movieStatus("Planning: writing shots — part ${k + 1} of ${parts.size}", (k + 1f) / steps)
                    val previous = shots.takeLast(2).joinToString(" / ") { it.action }
                    var got = com.arin.movie.MovieMaker.parseShots(askDirector(com.arin.movie.MovieMaker.shotsPrompt(castJson, previous, movieNotes.toList()), part))
                    if (got.isEmpty()) got = com.arin.movie.MovieMaker.parseShots(askDirector(com.arin.movie.MovieMaker.shotsPrompt(castJson, previous, movieNotes.toList()), part))
                    if (got.isEmpty()) { movieFail("${model} didn't return shots for part ${k + 1}. Tap Make movie to try again."); return@launch }
                    shots += got
                    movieLogLine("Part ${k + 1}: ${got.size} shots (${shots.size} so far)")
                }
                val bible = cast.copy(shots = shots.take(200))
                com.arin.movie.MovieMaker.saveNewProject(app, bible)
                movieHasProject = true
                movieLogLine("Plan ready: ${bible.characters.size} characters, ${bible.shots.size} shots.")
                finished = renderAndWatch(app, motion = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                movieFail("Movie error: ${e.message}")
            } finally {
                movieBusy = false
                moviePhase = MoviePhase.Idle
                movieActivity = ""
                com.arin.movie.MovieService.stop(app)
                if (finished) com.arin.movie.MovieService.notifyDone(app, "Your movie is ready 🎬", movieTitle)
                else if (movieError != null) com.arin.movie.MovieService.notifyDone(app, "Movie stopped", movieError.orEmpty())
            }
        }
    }

    /** Continue an interrupted film, retrying failed shots. Finished shots are kept. */
    fun resumeMovie() = runMovieJob(motion = false)

    /** Re-render only the camera-move shots with real motion, using the free GPU quota left today. */
    fun addMotionToMovie() {
        if (movieHfToken.isBlank()) { movieFail("Add your free Hugging Face token under Real motion first."); return }
        runMovieJob(motion = true)
    }

    fun stopMovie() {
        val app = getApplication<Application>()
        ArinApp.scope.launch { com.arin.movie.MovieMaker.stop(app); refreshMovie() }
    }

    private fun runMovieJob(motion: Boolean) {
        if (movieBusy) return
        val app = getApplication<Application>()
        movieBusy = true
        movieError = null
        movieLog.clear()
        com.arin.movie.MovieService.start(app, "Getting ready…")
        ArinApp.scope.launch {
            var finished = false
            try {
                if (movieToolsReady(app)) finished = renderAndWatch(app, motion)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                movieFail("Movie error: ${e.message}")
            } finally {
                movieBusy = false
                moviePhase = MoviePhase.Idle
                com.arin.movie.MovieService.stop(app)
                if (finished) com.arin.movie.MovieService.notifyDone(app, "Your movie is ready 🎬", movieTitle)
            }
        }
    }

    /** Start the renderer and follow it. True when a movie came out. */
    private suspend fun renderAndWatch(app: Application, motion: Boolean): Boolean {
        movieReady = false
        moviePartial = false
        moviePhase = MoviePhase.Filming
        movieStatus("Filming: starting", 0f)
        if (!com.arin.movie.MovieMaker.startRender(app, movieHfToken, movieSpace, movieSubtitles, motion)) {
            movieFail("Couldn't start rendering.")
            return false
        }
        return watchMovie(app)
    }

    /** "Shot 12/40: voice" -> 12/40 of the way; the final cut is the last 5%. */
    private fun renderFraction(lines: List<String>): Float? {
        val last = lines.lastOrNull().orEmpty()
        if (last.startsWith("Final cut")) return 0.97f
        val m = lines.reversed().firstNotNullOfOrNull { Regex("""Shot (\d+)/(\d+)""").find(it) } ?: return null
        val (i, n) = m.destructured
        return ((i.toFloat() - 1f) / n.toFloat() * 0.95f).coerceIn(0f, 0.95f)
    }

    /** Poll the renderer until it finishes. Free GPU queues can be slow, so allow hours, not minutes. */
    private suspend fun watchMovie(app: Application): Boolean {
        val deadline = System.currentTimeMillis() + 5 * 3600_000L
        kotlinx.coroutines.delay(2000)
        while (System.currentTimeMillis() < deadline) {
            val (state, lines) = com.arin.movie.MovieMaker.status(app)
            movieProgress = lines.takeLast(2).joinToString("\n")
            val shot = lines.lastOrNull { it.startsWith("Shot ") }?.substringBefore(":")
            movieStatus("Filming: " + (shot?.lowercase() ?: lines.lastOrNull().orEmpty()), renderFraction(lines))
            when (state) {
                com.arin.movie.MovieMaker.State.Done, com.arin.movie.MovieMaker.State.Partial -> {
                    movieReady = true
                    moviePartial = state == com.arin.movie.MovieMaker.State.Partial
                    movieSummary = com.arin.movie.MovieMaker.summary(app)
                    movieStatus("Finished", 1f)
                    return true
                }
                com.arin.movie.MovieMaker.State.Failed -> { movieFail("Rendering failed. Check your connection and tap Resume."); return false }
                com.arin.movie.MovieMaker.State.Idle -> return false   // stopped; Resume continues it
                com.arin.movie.MovieMaker.State.Running -> kotlinx.coroutines.delay(3000)
            }
        }
        return false
    }

    /**
     * The director chat. While planning, the note shapes the parts not yet written. While filming (or after),
     * the model rewrites the shots that haven't been filmed yet, and the renderer picks them up.
     */
    fun sendMovieNote(text: String) {
        val note = text.trim()
        if (note.isEmpty() || movieSteering) return
        val app = getApplication<Application>()
        movieChat += MovieChatLine(true, note)
        if (moviePhase == MoviePhase.Planning) {
            movieNotes += note
            movieChat += MovieChatLine(false, "Got it — I'll follow that for the rest of the plan.")
            return
        }
        movieSteering = true
        ArinApp.scope.launch {
            try {
                val bible = com.arin.movie.MovieMaker.loadProject(app)
                if (bible == null) { movieChat += MovieChatLine(false, "Make a movie first, then you can steer it here."); return@launch }
                val running = moviePhase == MoviePhase.Filming
                val locked = (if (running) com.arin.movie.MovieMaker.lockedShots(app) else 0).coerceAtMost(bible.shots.size)
                val remaining = bible.shots.drop(locked)
                if (remaining.isEmpty()) {
                    movieChat += MovieChatLine(false, "Every shot is already filmed. Describe the change and tap Make movie for a new cut.")
                    return@launch
                }
                val raw = askDirector(
                    com.arin.movie.MovieMaker.revisePrompt(com.arin.movie.MovieMaker.castJson(bible), note),
                    com.arin.movie.MovieMaker.shotsJson(remaining),
                )
                val revision = com.arin.movie.MovieMaker.parseRevision(raw)
                if (revision == null) { movieChat += MovieChatLine(false, "I couldn't apply that — try saying it another way."); return@launch }
                // Re-check what got filmed while the model was thinking, so nothing filmed is replaced.
                val nowLocked = (if (running) com.arin.movie.MovieMaker.lockedShots(app) else 0).coerceAtMost(bible.shots.size)
                val keep = bible.shots.take(maxOf(locked, nowLocked))
                val newShots = revision.second.drop(maxOf(0, nowLocked - locked))
                com.arin.movie.MovieMaker.writeProject(app, bible.copy(shots = (keep + newShots).take(200)))
                movieChat += MovieChatLine(false, revision.first + if (running) "" else " Tap Resume to film it.")
            } catch (e: Exception) {
                movieChat += MovieChatLine(false, "Couldn't reach ${model}: ${e.message}")
            } finally {
                movieActivity = ""
                movieSteering = false
            }
        }
    }

    /** Called when the screen opens: show the saved film and keep watching a render that is still going. */
    fun refreshMovie() {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            movieHasProject = com.arin.movie.MovieMaker.hasProject(app)
            movieTitle = com.arin.movie.MovieMaker.projectTitle(app).orEmpty()
            if (!movieHasProject || !com.arin.terminal.Linux.installed(app) || movieBusy) return@launch
            val (state, lines) = com.arin.movie.MovieMaker.status(app)
            movieProgress = lines.takeLast(2).joinToString("\n")
            movieReady = com.arin.movie.MovieMaker.hasMovie(app) &&
                state in setOf(com.arin.movie.MovieMaker.State.Done, com.arin.movie.MovieMaker.State.Partial)
            moviePartial = state == com.arin.movie.MovieMaker.State.Partial
            movieSummary = if (movieReady) com.arin.movie.MovieMaker.summary(app) else null
            if (state == com.arin.movie.MovieMaker.State.Running) {
                // A render is still going (e.g. Arin was reopened): follow it again in the background.
                movieBusy = true
                moviePhase = MoviePhase.Filming
                com.arin.movie.MovieService.start(app, "Filming…")
                try { watchMovie(app) } finally {
                    movieBusy = false
                    moviePhase = MoviePhase.Idle
                    com.arin.movie.MovieService.stop(app)
                }
            }
        }
    }

    fun playMovie() {
        val app = getApplication<Application>()
        if (!com.arin.movie.MovieMaker.play(app)) notice = "No finished movie to play yet."
    }

    fun shareMovie() {
        val app = getApplication<Application>()
        com.arin.movie.MovieMaker.shareIntent(app)?.let {
            runCatching { app.startActivity(android.content.Intent.createChooser(it, "Share movie").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } ?: run { notice = "No finished movie to share yet." }
    }

    fun setUpRea() {
        toggleTerminal(true)
        if (mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name }) saveServer(com.arin.mcp.Mcp.REA)
        val app = getApplication<Application>()
        // REA always goes in Arin's built-in Linux when the phone supports it: Termux's Node.js comes from its own
        // package list (e.g. 26.3.1), which current REA refuses, while built-in Linux installs a Node REA accepts.
        if (com.arin.terminal.Linux.supported()) {
            if (linuxBusy) return
            linuxBusy = true
            linuxLog.clear()
            ReplyService.start(app)
            ArinApp.scope.launch {
                try {
                    if (!com.arin.terminal.Linux.nodeReady(app)) com.arin.terminal.Linux.install(app) { logLine(it) }
                    linuxInstalled = true
                    linuxReady = true
                    installReaInLinux(app)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        newChat()
                        send(REA_CHECK_PROMPT)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logLine("Failed: ${e.message}")
                    notice = "REA setup failed: ${e.message}"
                } finally {
                    linuxBusy = false
                    ReplyService.stop(app)
                }
            }
            return
        }
        newChat()
        viewModelScope.launch {
            // Put Arin's bridge in Termux first, so the model can start and test REA through it.
            if (com.arin.terminal.Shell.problem(app) == null) com.arin.mcp.Mcp.installBridge(app)
            send(REA_SETUP_PROMPT)
        }
    }
    private var approval: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun answerCommand(allow: Boolean, always: Boolean = false) {
        if (always) toggleTerminalAsk(false)
        approval?.complete(allow)
    }

    /** Several chats can work at once: their approval requests take turns, one dialog at a time. */
    private val approvalLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun askToRun(command: String, tool: Boolean, force: Boolean = false, fromChat: String? = null): Boolean {
        if (!terminalAsk && !force) return true
        return approvalLock.withLock {
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
            approval = d
            approvingTool = tool
            val label = fromChat?.takeIf { it != chatKey() }?.let { id ->
                (runs[id]?.chatTitle ?: chats.firstOrNull { it.id == id }?.title)?.let { "From the chat \"$it\":\n\n" }
            }.orEmpty()
            commandToApprove = label + command
            try {
                d.await()
            } finally {
                commandToApprove = null
                approval = null
            }
        }
    }

    fun toggleTerminal(on: Boolean) {
        terminalOn = on
        settings.terminalOn = on
    }

    fun toggleTerminalAsk(on: Boolean) {
        terminalAsk = on
        settings.terminalAsk = on
    }

    /** Runs a quick check in Termux; returns what it printed or the problem. */
    suspend fun testTerminal(): String {
        val r = com.arin.terminal.Shell.run(
            getApplication(),
            "echo \"Termux is connected\"; uname -m; (node -v 2>/dev/null && npm -v 2>/dev/null) || echo 'Node not installed yet'; python --version 2>/dev/null || echo 'Python not installed yet'",
            timeoutMs = 30_000,
        )
        return r.combined()
    }
    var notifyOnReply by mutableStateOf(settings.notifyOnReply)
        private set

    init {
        refreshChats()
        refreshProjects()
        memories.addAll(memoryStore.list())
        loadModels()
    }

    // ---------------- Sending ----------------

    // ---- Build mode (apps compiled to APKs on GitHub) ----

    init {
        // When a build ends, post the result in this chat with real download links.
        com.arin.builder.BuildRunner.onFinished = { result, project, session -> postBuildResult(result, project, session) }
    }

    // ---------------- Builds started from chat stay in chat ----------------

    /** The chat a build was started from (null: none, or it was started in the Build tab). */
    /** A build shown in a chat: which chat, its live progress message, and the job updating it. Several at once. */
    private class Follow(val session: String, val chat: String?, var msg: String?, var job: Job? = null)
    private val follows = mutableMapOf<String, Follow>()

    /** The build session this chat works on (saved, so it survives restarts). */
    fun chatBuildSession(): com.arin.builder.BuildSession? =
        linkedBuild(chatId)?.let { com.arin.builder.BuildRunner.sessionFor(getApplication(), it) }

    fun chatBuildSessionId(): String? = linkedBuild(chatId)

    private val retryWords = Regex("""\b(try again|retry|again|continue|resume|fix( it| the)?( build| error)?s?|keep going|carry on)\b""", RegexOption.IGNORE_CASE)

    // ---- Chat builds survive restarts: each chat remembers its build project, so it can resume or restart ----

    private fun buildLinks() = getApplication<Application>().getSharedPreferences("chat_builds", android.content.Context.MODE_PRIVATE)
    private fun linkBuild(chat: String, project: String) = buildLinks().edit().putString(chat, project).apply()
    private fun linkedBuild(chat: String?): String? = chat?.let { buildLinks().getString(it, null) }

    /** What the last build of this chat's app left behind: shown above the message box with Resume / Restart. */
    data class ChatBuildState(val projectId: String, val appName: String, val outcome: String, val canResume: Boolean)

    var chatBuildState by mutableStateOf<ChatBuildState?>(null)
        private set

    /** Re-reads this chat's build status (on opening a chat, and after a build ends). */
    fun refreshChatBuild() {
        val id = linkedBuild(chatId)
        if (id == null || com.arin.builder.BuildRunner.session(id)?.running == true) { chatBuildState = null; return }
        val app = getApplication<Application>()
        viewModelScope.launch {
            val saved = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.arin.builder.BuildRunner.list(app).firstOrNull { it.id == id }
            }
            if (saved == null) { chatBuildState = null; return@launch }
            val lastUser = saved.timeline.indexOfLast { it is com.arin.builder.TimelineItem.User }
            val lastResult = saved.timeline.indexOfLast { it is com.arin.builder.TimelineItem.Result }
            val result = saved.timeline.getOrNull(lastResult) as? com.arin.builder.TimelineItem.Result
            val outcome = when {
                lastUser > lastResult -> "stopped before it finished"
                result?.success == true -> "succeeded"
                else -> "failed"
            }
            chatBuildState = ChatBuildState(id, saved.title, outcome, canResume = saved.project != null)
        }
    }

    /**
     * Continues this chat's last build from its checkpoint (code already on GitHub, the fix round it reached, files
     * that hadn't uploaded), or with [restart] runs the last request again from the beginning on the same app and repo.
     */
    fun resumeChatBuild(restart: Boolean) {
        val state = chatBuildState ?: return
        val app = getApplication<Application>()
        val session = com.arin.builder.BuildRunner.sessionFor(app, state.projectId) ?: run { notice = "Couldn't open the saved build."; return }
        if (session.running) { notice = "${state.appName} is already building."; return }
        val project = session.project
        val lastRequest = session.timeline.filterIsInstance<com.arin.builder.TimelineItem.User>()
            .map { it.text.trim() }.lastOrNull { it.isNotBlank() && !retryWords.containsMatchIn(it.take(40)) }
        val token = githubToken()
        when {
            token.isBlank() && project?.owner != com.arin.builder.BuildAgent.LOCAL_OWNER -> { notice = "Sign in with GitHub in Settings first."; return }
            provider.needsKey && settings.key(provider).isBlank() -> { notice = "Add your ${provider.label} key in Settings."; return }
        }
        com.arin.builder.BuildService.start(
            app,
            com.arin.builder.BuildRequest(
                description = if (restart) (lastRequest ?: project?.requirements?.ifBlank { null } ?: project?.description ?: "Build the app") else "Resume",
                appName = project?.appName ?: state.appName,
                provider = provider,
                model = model,
                apiKey = settings.key(provider),
                customBaseUrl = if (provider == Provider.Custom) customBaseUrl else "",
                customFormat = customFormat.name,
                testApp = testApps,
                effort = effort,
                githubToken = token,
                allowOtherSites = true,
                project = if (restart) project?.copy(round = 0, pendingPaths = emptyList()) else project,
                resume = !restart && project != null,
                localOnly = project?.owner == com.arin.builder.BuildAgent.LOCAL_OWNER,
            ),
            session,
        )
        chatBuildState = null
        followBuildInChat((if (restart) "Restarting " else "Resuming ") + (project?.appName ?: state.appName), session)
    }

    /** True while this chat's build is running (the chat shows a Stop button). Other chats' builds don't count. */
    fun chatBuildRunning(): Boolean = linkedBuild(chatId)?.let { com.arin.builder.BuildRunner.session(it)?.running } == true

    fun chatBuildAppName(): String = chatBuildSession()?.project?.appName ?: "the app"

    /** Stops this chat's build only; what's built so far stays saved, so it can be resumed. */
    fun stopChatBuild() {
        val app = getApplication<Application>()
        val sid = linkedBuild(chatId) ?: return
        val session = com.arin.builder.BuildRunner.session(sid)
        com.arin.builder.BuildService.stop(app, sid)
        follows.remove(sid)?.let { f ->
            f.job?.cancel()
            replaceBuildMessage(f, buildProgressText("Build", done = true, session) + "\n\n⏹ **Stopped.** Everything built so far is saved — tap Resume to carry on.", save = true)
            f.msg = null
        }
        viewModelScope.launch {
            // Give the service a moment to wind down and save its checkpoint.
            repeat(20) { if (session?.running != true) return@repeat; kotlinx.coroutines.delay(500) }
            if (session != null) com.arin.builder.BuildRunner.saveNow(app, session)
            refreshChatBuild()
        }
    }

    /** Shows [session]'s steps live in this chat, like Build mode does. Other builds keep their own messages. */
    private fun followBuildInChat(title: String, session: com.arin.builder.BuildSession) {
        if (chatId == null) persist()
        chatId?.let { linkBuild(it, session.id) }
        val msg = Message(fromUser = false, text = "🛠️ **$title…**\n\nStarting.")
        messages += msg
        persist()
        follows[session.id]?.job?.cancel()
        val f = Follow(session.id, chatId, msg.id)
        follows[session.id] = f
        f.job = viewModelScope.launch {
            var last = ""
            while (true) {
                val text = buildProgressText(title, done = false, session)
                if (text != last) {
                    last = text
                    replaceBuildMessage(f, text, save = false)
                }
                if (!session.running && session.pending == null) {
                    kotlinx.coroutines.delay(1_500)
                    if (!session.running) break
                }
                kotlinx.coroutines.delay(700)
            }
        }
    }

    private fun buildProgressText(title: String, done: Boolean, session: com.arin.builder.BuildSession?): String = buildString {
        append(if (done) "🛠️ **$title — steps**\n\n" else "🛠️ **$title…**\n\n")
        val events = (session?.timeline?.toList() ?: emptyList())
            .filterIsInstance<com.arin.builder.TimelineItem.Event>().map { it.event }
            .filter { it.kind != com.arin.builder.BuildEvent.Kind.THINKING }
        val shown = events.takeLast(if (done) 25 else 14)
        if (events.size > shown.size) append("_…${events.size - shown.size} earlier steps_\n")
        shown.forEach { e ->
            val icon = when (e.kind) {
                com.arin.builder.BuildEvent.Kind.ERROR -> "⚠️"
                com.arin.builder.BuildEvent.Kind.DONE -> "✅"
                com.arin.builder.BuildEvent.Kind.BUILD -> "⚙️"
                com.arin.builder.BuildEvent.Kind.FILE -> "📄"
                com.arin.builder.BuildEvent.Kind.WEB -> "🌐"
                com.arin.builder.BuildEvent.Kind.DOWNLOAD, com.arin.builder.BuildEvent.Kind.LIBRARY -> "📦"
                else -> "•"
            }
            val line = e.text.lines().first().take(240)
            append("- $icon ").append(line).append('\n')
        }
        if (!done) {
            val p = session?.progressText.orEmpty()
            if (p.isNotBlank()) append("\n_${p.take(120)}_\n")
            append("\nYou can keep chatting (or start another build); I'll post the APK and source links here when it's done.")
        }
    }

    /** Puts [text] in a chat build's progress message, in whichever chat it lives. */
    private fun replaceBuildMessage(f: Follow, text: String, save: Boolean) {
        val id = f.msg ?: return
        val target = f.chat
        if (target == chatId) {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = text)
            if (save) persist()
        } else if (save && target != null) {
            ArinApp.scope.launch(Dispatchers.IO) {
                store.load(target)?.let { c -> store.save(c.copy(messages = c.messages.map { if (it.id == id) it.copy(text = text) else it })) }
            }
        }
    }

    /** Adds [msg] to the chat the build came from, even if another chat is open now. */
    private fun postToBuildChat(target: String?, msg: Message, progressId: String?, steps: String) {
        if (target == null || target == chatId) {
            messages += msg
            persist()
        } else {
            ArinApp.scope.launch(Dispatchers.IO) {
                store.load(target)?.let { c ->
                    val updated = c.messages.map { if (it.id == progressId) it.copy(text = steps) else it } + msg
                    store.save(c.copy(messages = updated, updated = System.currentTimeMillis()))
                }
                withContext(Dispatchers.Main) { refreshChats() }
            }
        }
    }

    private fun postBuildResult(result: com.arin.builder.BuildResult, project: com.arin.builder.Project?, sessionId: String) {
        refreshChatBuild()
        // Started in the Build tab: the result stays there.
        val f = follows.remove(sessionId) ?: return
        if (f.msg == null) return
        f.job?.cancel()
        val title = when {
            project?.selfUpdate == true -> "Updating Arin"
            else -> "Building ${project?.appName ?: "the app"}"
        }
        val steps = buildProgressText(title, done = true, com.arin.builder.BuildRunner.session(sessionId))
        val progressId = f.msg
        if (f.chat == chatId) replaceBuildMessage(f, steps, save = false)
        f.msg = null
        val name = project?.appName ?: "The app"
        val phoneOnly = project?.owner == com.arin.builder.BuildAgent.LOCAL_OWNER
        val text = if (result.success && phoneOnly) buildString {
            append("✅ **$name is built on this phone.**\n\n")
            result.phoneApk?.let { append("- **APK:** `$it`\n") }
            result.phoneSourceZip?.let { append("- **Source code (zip):** `$it`\n") }
            append("\nAndroid's install screen opens now. Both files are in your Download/Arin folder.")
        } else if (result.success && project != null) buildString {
            append(if (project.selfUpdate) "✅ **The new version of Arin is built.**\n\n" else "✅ **$name is built.**\n\n")
            result.apkUrl?.let { append("- **APK:** [Download $name APK]($it)\n  $it\n") }
            val zip = com.arin.builder.BuildRunner.sourceZipUrl(project)
            append("- **Source code (zip):** [Download source zip]($zip)\n  $zip\n")
            append("- **GitHub repository:** [Open on GitHub](${com.arin.builder.BuildRunner.repoPage(project)})\n")
            result.phoneApk?.let { append("- **Also compiled on this phone:** `$it`\n") }
            result.codespaceUrl?.let { append("- **Codespace:** [Open the Codespace]($it)\n") }
            if (result.testNote.isNotBlank()) append("\n**App test:** ${result.testNote}\n")
            result.testScreenshot?.let { append("- **Screenshot on the emulator:** [Open]($it)\n") }
            append(if (project.selfUpdate) "\nTap the APK link (or Install in Build mode) to update Arin." else "\nTap the APK link to download and install it.")
        } else {
            buildString {
                append("⚠️ **The build of $name failed.**\n\n**Why it failed:** ${result.message}\n")
                if (result.log.isNotBlank()) {
                    val lines = result.log.lines().filter { it.isNotBlank() }
                    append("\n**All build errors** (${lines.size} line${if (lines.size == 1) "" else "s"}):\n```\n")
                    append(lines.joinToString("\n") { it.take(500) }.replace("```", "` ` `"))
                    append("\n```\n")
                }
                result.runUrl?.let { append("\n- **Full raw log on GitHub:** [Open the run]($it)\n") }
                result.repoUrl?.let { append("- **GitHub repository:** [Open on GitHub]($it)\n") }
                append("\nTell me what to fix (or say \"try again\" / \"fix these errors\") and I\'ll correct them and rebuild.")
            }
        }
        lastFailure = if (result.success) "" else
            "The last Build mode run ($name) FAILED. Reason: ${result.message}" +
                (if (result.log.isNotBlank()) " Error log (the full list is in the chat message):\n${result.log.take(4_000)}\n" else "") +
                (result.runUrl?.let { " Full log: $it ." } ?: "") +
                " When the person asks why it failed, explain from this log in plain words; to fix it, emit a <build> tag that says what to change.\n\n"
        if (result.success && result.testNote.startsWith("⚠️")) {
            lastFailure = "The last build ($name) produced an APK, but its emulator test found a problem: ${result.testNote} " +
                "When the person asks to fix it, emit a <build> tag naming the same app that says to fix this crash.\n\n"
        }
        postToBuildChat(f.chat, Message(fromUser = false, text = text), progressId, steps)
        if (result.success) result.phoneInstallPath?.let { path ->
            runCatching { com.arin.builder.ApkInstaller.install(getApplication(), java.io.File(path)) }
        }
        // Codespace (and cloud) builds: save the APK straight to the phone, so you never fight a file manager.
        if (result.success && result.autoDownloadApk) result.apkUrl?.let { url ->
            val app = getApplication<Application>()
            ArinApp.scope.launch {
                runCatching { com.arin.builder.ApkInstaller.download(app, url) {} }
                    .onSuccess {
                        postToBuildChat(f.chat, Message(fromUser = false, text =
                            "⬇️ **$name saved to your phone** in Download/Arin (`${it.name}`). Opening the installer…"), null, "")
                        runCatching { com.arin.builder.ApkInstaller.install(app, it) }
                    }
                    .onFailure { notice = "Built, but couldn't auto-save the APK: ${it.message}. Tap the APK link above." }
            }
        }
    }

    /** Details of the last failed build, for the model to explain or fix. */
    private var lastFailure = ""

    /** The latest build's links, for the model to give when asked. */
    private fun latestBuildInfo(): String {
        val mine = com.arin.builder.BuildRunner.session(linkedBuild(chatId))
        if (mine?.running == true) {
            val step = mine.timeline.lastOrNull { it is com.arin.builder.TimelineItem.Event }
                as? com.arin.builder.TimelineItem.Event
            return "A build of ${mine.project?.appName ?: "an app"} is running now in this chat" +
                (step?.let { " (current step: ${it.event.text.take(150)})" } ?: "") +
                ". Its steps show in the chat and the links will be posted when it's done. Don't send another <build> for THIS app " +
                "until it finishes (a different app can be built at the same time); answer other questions normally.\n\n"
        }
        if (lastFailure.isNotEmpty()) return lastFailure
        val p = (mine ?: com.arin.builder.BuildRunner.current).project ?: return ""
        val apk = p.lastApkUrl ?: return ""
        return "Latest app built in Build mode: ${p.appName}" + (if (p.selfUpdate) " (Arin itself)" else "") +
            ". APK download: $apk . Source code zip: ${com.arin.builder.BuildRunner.sourceZipUrl(p)} . " +
            "GitHub repository: ${com.arin.builder.BuildRunner.repoPage(p)} . When the person asks for the links, the APK or the " +
            "source code, give these exact links (never make links up).\n\n"
    }

    /** How hard Build mode works (more fix rounds when higher). */
    var effort by mutableStateOf(com.arin.builder.Effort.HIGH)
        private set

    fun chooseEffort(e: com.arin.builder.Effort) {
        effort = e
    }

    fun key(p: Provider): String = settings.key(p)

    fun githubToken(): String = settings.secret("GITHUB")

    /** Set when chat starts a build, so the screen can switch to Build mode. */
    var openBuild by mutableStateOf(false)

    /** Starts building an app from chat; returns a problem to show, or null when it started. */
    fun startBuild(name: String, description: String, target: BuildTarget = BuildTarget.CHAT): String? {
        val token = githubToken()
        val app = getApplication<Application>()
        val phoneReady = com.arin.terminal.Linux.androidBuildReady(app)
        // Where to build: the phone (built-in Linux), or GitHub's cloud shown in the chat or the Build tab. Auto picks one.
        val onPhone = target == BuildTarget.PHONE || (target == BuildTarget.AUTO && phoneReady && token.isBlank())
        val inBuildTab = target == BuildTarget.BUILD_TAB
        val inCodespace = target == BuildTarget.CODESPACE
        return when {
            target == BuildTarget.PHONE && !phoneReady ->
                "To build on the phone, install the Android build tools in Settings → Linux first."
            inCodespace && token.isBlank() ->
                "To build in a Codespace, sign in with GitHub in Settings → GitHub first."
            !onPhone && token.isBlank() && !phoneReady ->
                "To build apps, sign in with GitHub in Settings → GitHub, or install the Android build tools in Settings → Linux to build on this phone. Then ask again."
            chatBuildRunning() && (name.isBlank() || name.equals(chatBuildSession()?.project?.appName, ignoreCase = true)) ->
                "${chatBuildAppName()} is still building in this chat. Wait for it or tap Stop — a different app can be built at the same time."
            provider.needsKey && settings.key(provider).isBlank() -> "Add your ${provider.label} key in Settings."
            else -> {
                // A follow-up in the same chat ("try again", "add dark mode") keeps working on the same app.
                // This chat's saved build (survives restarts). Other builds may be running at the same time.
                val linked = chatBuildSession()
                val current = linked?.project
                val same = linked != null && current != null && !current.selfUpdate && !linked.running &&
                    (name.isBlank() || name.equals(current.appName, ignoreCase = true) ||
                        retryWords.containsMatchIn(description.take(80)))
                val tl = linked?.timeline?.toList() ?: emptyList()
                val lastFailed = tl.indexOfLast { it is com.arin.builder.TimelineItem.User } > tl.indexOfLast { it is com.arin.builder.TimelineItem.Result } ||
                    (tl.lastOrNull { it is com.arin.builder.TimelineItem.Result } as? com.arin.builder.TimelineItem.Result)?.success == false
                val session = if (same) linked!! else com.arin.builder.BuildRunner.createSession()
                val appName = if (same) current!!.appName
                    else name.ifBlank { description.split(Regex("\\s+")).take(3).joinToString(" ").take(30) }
                com.arin.builder.BuildService.start(
                    app,
                    com.arin.builder.BuildRequest(
                        description = description,
                        appName = appName,
                        provider = provider,
                        model = model,
                        apiKey = settings.key(provider),
                        customBaseUrl = if (provider == Provider.Custom) customBaseUrl else "",
                        customFormat = customFormat.name,
                        testApp = testApps,
                        effort = effort,
                        githubToken = token,
                        allowOtherSites = true,
                        project = if (same) current else null,
                        resume = same && lastFailed && retryWords.containsMatchIn(description.take(80)),
                        // The whole conversation goes to the builder, so nothing that was discussed gets lost.
                        requirements = buildRequirements(description),
                        requirementsComplete = true,
                        localOnly = onPhone,
                        codespace = inCodespace,
                    ),
                    session,
                )
                if (inBuildTab) { com.arin.builder.BuildRunner.open(app, session.id); openBuild = true }
                else followBuildInChat((if (same) "Working on $appName again" else "Building $appName") +
                    when { onPhone -> " on this phone"; inCodespace -> " in a Codespace"; else -> "" }, session)
                null
            }
        }
    }

    /** The person tapped "Build now" (or "Update Arin now") under a reply that prepared a build. */
    fun confirmBuildAt(message: Message, target: BuildTarget) {
        val (name, spec) = message.pendingBuild.split('\u0000', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (spec.isBlank()) return
        val problem = if (name == "update") startSelfUpdate(spec) else startBuild(name, spec, target)
        if (problem != null) { notice = problem; return }
        val i = messages.indexOfFirst { it.id == message.id }
        if (i >= 0) messages[i] = messages[i].copy(pendingBuild = "", text = messages[i].text.substringBefore("\n\n_Where should I build"))
        persist()
    }

    /**
     * Everything the app must do, for the builder: the specification written when the person said "build", plus the
     * conversation itself (what they asked for and what Arin proposed), so details the specification skipped still get built.
     */
    private fun buildRequirements(spec: String): String {
        val talk = messages.filter { m ->
            !m.failed && m.text.isNotBlank() &&
                !(m.text.startsWith("🛠️") || m.text.startsWith("✅ **") || m.text.startsWith("⚠️ **The build"))
        }
        // Newest first within a budget, so a very long chat keeps what was said last; then back in order.
        fun pick(fromUser: Boolean, each: Int, total: Int): List<Message> {
            var left = total
            return talk.filter { it.fromUser == fromUser }.asReversed().takeWhile { m ->
                (left > 0).also { left -= minOf(m.text.length, each) }
            }
        }
        val keep = (pick(true, 4_000, 18_000) + pick(false, 2_500, 12_000)).map { it.id }.toSet()
        return buildString {
            appendLine("SPECIFICATION (written when the person said \"build\"):")
            appendLine(spec.trim())
            appendLine()
            appendLine("THE CONVERSATION ABOUT THIS APP, in order. Everything the person asked for, and everything Arin proposed that the")
            appendLine("person didn't reject, must be in the app, including details the specification above left out. Where two messages")
            appendLine("conflict, the later one wins. Ignore parts that are not about this app.")
            var n = 0
            talk.filter { it.id in keep }.forEach { m ->
                n++
                val files = m.attachments.takeIf { it.isNotEmpty() }?.joinToString { it.name }?.let { " [attached: $it]" }.orEmpty()
                if (m.fromUser) appendLine("[$n] PERSON: " + m.text.trim().take(4_000) + files)
                else appendLine("[$n] ARIN: " + m.text.trim().take(2_500))
            }
        }.trim()
    }

    /**
     * Downloads [url] for the person and for terminal tools: into the built-in Linux (/root/files) when it's
     * installed, otherwise into Download/Arin. Returns a line for the model.
     */
    private suspend fun downloadFile(app: Application, url: String, name: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fileName = name.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "download" } }
                .replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(120)
            try {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Arin").build()
                com.arin.builder.Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext "Download failed: HTTP ${r.code} for $url"
                    val body = r.body ?: return@withContext "Download failed: empty response from $url"
                    val linuxPath = if (com.arin.terminal.Linux.installed(app)) {
                        body.byteStream().use { com.arin.terminal.Linux.importFile(app, it, fileName) }
                    } else null
                    if (linuxPath != null) {
                        "Downloaded $fileName (${r.header("Content-Length")?.toLongOrNull()?.let { com.arin.data.Attachments.readableSize(it) } ?: "size unknown"}) to $linuxPath in Linux. Terminal tools can use it there."
                    } else {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Arin")
                        }
                        val dest = if (android.os.Build.VERSION.SDK_INT >= 29) {
                            app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        } else null
                        if (dest != null) {
                            app.contentResolver.openOutputStream(dest)?.use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                            "Downloaded $fileName to the phone's Download/Arin folder (/storage/emulated/0/Download/Arin/$fileName)."
                        } else {
                            val f = java.io.File(app.getExternalFilesDir(null), fileName)
                            f.outputStream().use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                            "Downloaded $fileName to ${f.absolutePath}."
                        }
                    }
                }
            } catch (e: Exception) {
                "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    // ---- Arin updating itself ----

    /** Where Arin's own source lives on GitHub (owner/repo and branch). */
    fun selfRepo(): String = settings.secret("SELF_REPO").ifBlank { DEFAULT_SELF_REPO }

    fun selfBranch(): String = settings.secret("SELF_BRANCH").ifBlank { DEFAULT_SELF_BRANCH }

    fun saveSelfRepo(repo: String, branch: String) {
        settings.setSecret("SELF_REPO", repo.trim().removePrefix("https://github.com/").trimEnd('/'))
        settings.setSecret("SELF_BRANCH", branch.trim())
    }

    /** Changes Arin's own code on GitHub, builds a new version and offers to install it. Returns a problem, or null. */
    fun startSelfUpdate(change: String): String? {
        val token = githubToken()
        val app = getApplication<Application>()
        return when {
            change.isBlank() -> "Describe what to change in Arin."
            token.isBlank() -> "To update Arin, sign in with GitHub in Settings → GitHub, then ask again."
            com.arin.builder.BuildRunner.sessions.values.any { it.running && (it.project?.selfUpdate == true || it.pending?.selfUpdate == true) } ->
                "An update of Arin is already building. Wait for it to finish."
            provider.needsKey && settings.key(provider).isBlank() -> "Add your ${provider.label} key in Settings."
            else -> {
                val session = com.arin.builder.BuildRunner.createSession()
                com.arin.builder.BuildService.start(
                    app,
                    com.arin.builder.BuildRequest(
                        description = change.trim(),
                        appName = "Arin",
                        provider = provider,
                        model = model,
                        apiKey = settings.key(provider),
                        customBaseUrl = if (provider == Provider.Custom) customBaseUrl else "",
                        customFormat = customFormat.name,
                        testApp = testApps,
                        effort = effort,
                        githubToken = token,
                        allowOtherSites = true,
                        selfUpdate = true,
                        selfRepo = selfRepo(),
                        selfBranch = selfBranch(),
                    ),
                    session,
                )
                followBuildInChat("Updating Arin", session)
                null
            }
        }
    }

    // ---- Sign in with GitHub (device flow) ----

    var githubUser by mutableStateOf(settings.secret("GITHUB_USER"))
        private set
    var githubCode by mutableStateOf<com.arin.builder.GitHubLogin.DeviceCode?>(null)
        private set
    var githubLoginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: kotlinx.coroutines.Job? = null

    fun githubClientId(): String = settings.secret("GITHUB_CLIENT_ID").ifBlank { com.arin.builder.GitHubLogin.DEFAULT_CLIENT_ID }

    fun saveGithubClientId(value: String) = settings.setSecret("GITHUB_CLIENT_ID", value)

    fun saveGithubToken(value: String) {
        settings.setSecret("GITHUB", value)
        if (value.isBlank()) githubUser = "" else refreshGithubUser()
    }

    /** Starts sign-in; [openBrowser] opens github.com/login/device where you enter the code. */
    fun signInGithub(openBrowser: (String) -> Unit) {
        val clientId = githubClientId()
        loginJob?.cancel()
        githubLoginError = null
        loginJob = viewModelScope.launch {
            try {
                val code = com.arin.builder.GitHubLogin.start(clientId)
                githubCode = code
                openBrowser(code.verificationUri)
                val token = com.arin.builder.GitHubLogin.waitForToken(clientId, code)
                settings.setSecret("GITHUB", token)
                refreshGithubUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                githubLoginError = e.message ?: "Sign-in failed."
            } finally {
                githubCode = null
            }
        }
    }

    fun signOutGithub() {
        loginJob?.cancel()
        settings.setSecret("GITHUB", "")
        settings.setSecret("GITHUB_USER", "")
        githubUser = ""
    }

    private fun refreshGithubUser() {
        val token = settings.secret("GITHUB")
        viewModelScope.launch {
            var result: Result<String> = Result.failure(Exception())
            for (attempt in 0 until 3) {
                result = runCatching { com.arin.builder.GitHubClient(token).login() }
                if (result.isSuccess) break
                kotlinx.coroutines.delay(3000)
            }
            result.onSuccess {
                githubUser = it
                settings.setSecret("GITHUB_USER", it)
            }
        }
    }

    // ---- Fixing errors automatically ----

    /**
     * What the model is told after a failure: Arin searches the web for the error itself, every time, and passes on what
     * it found with the instruction to apply a fix and retry, so known solutions are used straight away.
     */
    private suspend fun fixAdvice(streak: Int, errorQuery: String, sources: MutableList<Source>): String {
        val base = "Fix this yourself and keep going: find the cause in the output, apply a fix (install what's missing, change " +
            "the command or flags, correct the code or config) and try again. Don't stop to ask unless it truly needs the person."
        if (errorQuery.isBlank()) {
            return "$base If you're not sure of the fix, search the web for the error with <search>…</search>."
        }
        status = "Searching the web for the error: “${errorQuery.take(60)}”"
        val found = runCatching { WebSearch.searchForModel(errorQuery) }.getOrNull()
        status = null
        found?.first?.take(4)?.forEach { h -> if (sources.none { it.url == h.url }) sources += Source(h.title, h.url) }
        val web = found?.second?.take(7_000).orEmpty()
        return if (web.isBlank()) {
            "$base A web search for the error found nothing; search with other words (<search>) or try a different approach."
        } else {
            "I searched the web for this error (\"$errorQuery\"). Results:\n$web\n\n" +
                "Use them to fix it: read the most relevant page with <fetch> if you need the details, apply the fix and " +
                "try again." + if (streak >= 2) " This has failed $streak times in a row, so try a different approach than before." else ""
        }
    }

    // ---- Steering: messages sent while a reply is running ----

    private val noSteering = mutableStateListOf<String>()
    /** Messages waiting to be given to the open chat's model at its next step. */
    val steering: List<String> get() = runFor()?.steering ?: noSteering

    /** Adds a message for the open chat's running reply; the model sees it at its next step (or right away, if it's writing). */
    fun steer(text: String) {
        val t = text.trim()
        val r = runFor() ?: return
        if (t.isEmpty()) return
        r.steering += t
        r.steerPending = true
    }

    fun removeSteer(index: Int) {
        val r = runFor() ?: return
        if (index in r.steering.indices) r.steering.removeAt(index)
        r.steerPending = r.steering.isNotEmpty()
    }

    private suspend fun takeSteering(r: Run): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val list = r.steering.toList()
        r.steering.clear()
        r.steerPending = false
        list
    }

    /** Thrown inside a stream to stop it when the person steers. */
    private class SteerInterrupt : Exception()

    fun send(text: String) {
        if (streaming) {
            steer(text)
            return
        }
        val edit = editing?.takeIf { it in messages.indices && messages[it].fromUser }
        val kept = edit?.let { messages[it].attachments }.orEmpty()
        val files = kept + pending.toList()
        if (text.isBlank() && files.isEmpty()) return
        pending.clear()
        if (edit != null) {
            // Replace the edited message and everything after it.
            val dropped = messages.subList(edit, messages.size).toList()
            Attachments.deleteFiles(dropped.flatMap { it.attachments }.filterNot { it in kept })
            while (messages.size > edit) messages.removeAt(messages.lastIndex)
            editing = null
        }
        messages += Message(fromUser = true, text = text.trim(), attachments = files)
        if (messages.size == 1 && !isReverseEngineeringChat()) title = makeTitle(text, files)
        persist()
        reply()
    }

    /** Start editing a sent message; returns its text for the message box. */
    fun startEdit(index: Int): String? {
        if (streaming || index !in messages.indices || !messages[index].fromUser) return null
        editing = index
        return messages[index].text
    }

    fun cancelEdit() {
        editing = null
    }

    /** Ask again for the last reply. */
    fun retry() {
        if (streaming) return
        while (messages.isNotEmpty() && !messages.last().fromUser) messages.removeAt(messages.lastIndex)
        if (messages.isEmpty()) return
        persist()
        reply()
    }

    /** Stops the open chat's reply (other chats keep working). */
    fun stop() {
        runFor()?.job?.cancel()
    }

    /** Writes [r]'s chat to disk with the reply-so-far as a resumable (interrupted) message. */
    private suspend fun saveCheckpoint(r: Run, text: String, steps: List<com.arin.data.CommandRun>, thinkingSoFar: String) {
        if (r.chat == TEMP_CHAT || r.base.isEmpty()) return
        val id = r.chat
        val partial = Message(
            fromUser = false,
            text = text.ifBlank { "Working…" } + "\n\n⚠️ **Arin stopped here** (closed, crashed or lost connection). " +
                "Tap **Resume** to carry on from the last step, or **Start over**.",
            thinking = thinkingSoFar,
            commands = steps,
            interrupted = true,
        )
        withContext(Dispatchers.IO) {
            if (runs[r.chat] !== r) return@withContext
            runCatching {
                val saved = store.load(id)
                val chat = saved?.copy(messages = r.base + partial, updated = System.currentTimeMillis())
                    ?: Chat(id = id, title = r.chatTitle, updated = System.currentTimeMillis(), messages = r.base + partial)
                store.save(chat)
            }
        }
    }

    /** Carries on an interrupted reply from its last completed step (the steps done are sent to the model). */
    fun resumeInterrupted() {
        if (streaming) return
        send("Resume the task from exactly where it stopped. The steps already completed are listed in your last reply — " +
            "don't redo them (check the current state quickly if you need to), then continue to the end.")
    }

    /** Throws away an interrupted reply and runs the request again from the beginning. */
    fun startOverInterrupted() {
        if (streaming) return
        retry()
    }

    private fun reply() {
        val history = messages.filterNot { it.failed }
        val p = provider
        val m = model
        val key = settings.key(p)
        // This chat's API stays fixed for the whole reply, even if another chat switches to a different one.
        if (p == Provider.Free) syncFreePool()
        val endpoint = if (p == Provider.Custom) com.arin.llm.CustomApi.current() else null
        val project = chatProject
        // REA is active in the chat named "Reverse engineering", or wherever it's switched on from the ⋮ menu.
        val reaChat = reaActive()
        val useTerminal = terminalOn || reaChat
        // REA (reverse engineering) is allowed only in the chat named "Reverse engineering"; other chats run normal.
        val servers = mcpServers.filter { it.enabled && (reaChat || it.name != com.arin.mcp.Mcp.REA.name) } +
            (if (reaChat && mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name && it.enabled }) listOf(com.arin.mcp.Mcp.REA) else emptyList())
        val activeNames = servers.map { it.name }.toSet()
        val memoryFlag = memoryOn && !temporary
        val memoryList = if (memoryFlag) memories.toList() else emptyList()
        val deep = deepResearch
        val searchFlag = webSearch || deep
        val useSearch = webSearch || deep
        // What the person said this turn: only an order to build ("build", "make the APK") lets a build start.
        val userSaid = history.lastOrNull { it.fromUser }?.text.orEmpty()
        val hasChatBuild = history.any { !it.fromUser && (it.text.startsWith("🛠️") || it.text.startsWith("⚠️ **The build")) }
        val app = getApplication<Application>()
        // Each chat runs its own reply; others keep going in the background.
        val run = Run(chatKey(), title, messages.toList())
        if (runs.containsKey(run.chat)) return
        runs[run.chat] = run
        run.liveText = ""
        run.liveThinking = ""
        run.status = null
        ReplyService.start(app)
        // App-wide scope: the reply finishes even if this screen goes away.
        run.job = ArinApp.scope.launch {
            val thinking = StringBuilder()
            var thinkStart = 0L
            var answer = ""
            val sources = mutableListOf<Source>()
            val ran = mutableListOf<com.arin.data.CommandRun>()
            val foundMedia = mutableListOf<com.arin.data.MediaItem>()
            var webSteps = 0
            var nudges = 0
            var buildNudges = 0
            var researchNudges = 0
            var stopped = false
            var failStreak = 0
            run.liveCommands.clear()
            var failure: String? = null
            try {
                val turns = Attachments.toTurns(p, history, projectMedia(project)).toMutableList()
                // Save progress every few seconds, so a crash or a killed app leaves a reply that can be resumed.
                run.checkpointJob?.cancel()
                run.checkpointJob = launch {
                    var lastSig = ""
                    while (true) {
                        kotlinx.coroutines.delay(3_000)
                        val sig = "${run.liveCommands.size}:${run.liveText.length / 300}"
                        if (sig != lastSig && (run.liveCommands.any { it.kind != "steer" } || run.liveText.length > 300)) {
                            lastSig = sig
                            saveCheckpoint(run, Prompt.clean(run.liveText), run.liveCommands.toList(), thinking.toString())
                        }
                    }
                }
                if (servers.isNotEmpty()) {
                    run.status = "Connecting to tools…"
                    val problems = com.arin.mcp.Mcp.connectAll(app, servers, canStart = useTerminal)
                    run.status = null
                    reaMissing = reaChat && com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name) == null
                    val others = problems.filter { it != com.arin.mcp.Mcp.REA_MISSING }
                    if (others.isNotEmpty()) notice = others.joinToString("\n")
                }
                val offline = p == Provider.Custom && com.arin.llm.LocalModel.isLocal(customBaseUrl)
                val wanted = if (offline) offlineModelFor(currentSavedApi()) else null
                // This chat's offline model must be the one loaded (another chat may have loaded a different one).
                if (offline && (!com.arin.llm.LocalModel.isUp() || (wanted != null && com.arin.llm.LocalModel.lastModel(app)?.name != wanted.name))) {
                    val file = wanted ?: com.arin.llm.LocalModel.lastModel(app)
                    val problem = if (file == null) "No offline model is downloaded yet. Open Settings → Offline models."
                        else com.arin.llm.LocalModel.start(app, file) { run.status = it }
                    run.status = null
                    if (problem != null) throw com.arin.llm.LlmException(problem)
                }
                var system = if (offline) Prompt.localSystem(memoryList, aboutYou, howToAnswer, replyStyle) else Prompt.system(
                    searchFlag, memoryFlag, memoryList, project, useTerminal, com.arin.mcp.Mcp.promptSection(activeNames, compact = saveCredits),
                    terminalInfo = com.arin.terminal.Shell.describe(app),
                    pastChats = if (memoryFlag) withContext(Dispatchers.IO) { runCatching { store.recentTitles(run.chat) }.getOrDefault("") } else "",
                    aboutPerson = aboutYou,
                    howToAnswer = howToAnswer,
                    style = replyStyle,
                    deepResearch = deep,
                    lean = saveCredits,
                    topics = Prompt.topics(
                        history.filter { it.fromUser }.takeLast(3).joinToString("\n") { it.text } +
                            // A pending build failure keeps the build rules in.
                            (if (lastFailure.isNotEmpty()) "\nbuild" else "") + (if (deep) "\nfetch" else ""),
                        hasBuild = hasChatBuild,
                        usedTerminal = history.takeLast(8).any { it.commands.isNotEmpty() },
                        reaChat = reaChat,
                    ),
                ) + latestBuildInfo() + (if (reaChat && com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name) == null)
                    "\n\nREA STATUS (live, from the app): REA is switched ON for this chat but it is NOT installed or not running, " +
                    "so its tools are unavailable. Don't claim it works and don't try to install it with terminal commands. " +
                    "If asked, say it isn't installed yet and that the person should tap \"Install REA\" above the message box " +
                    "(or Settings → Linux → Install Linux + REA)."
                else if (reaChat) "\n\nREA STATUS (live, from the app): REA is ON and connected in this chat." else
                    "\n\nREA STATUS (live, from the app — this overrides anything in memory or past chats): REA is OFF in this chat. " +
                    "It is not connected here and its tools can't be called. Any remembered REA facts (tool counts, versions, " +
                    "'connected') describe OTHER chats, not this one. If asked whether REA is active here, answer plainly: no, " +
                    "and it can be switched on for this chat from the ⋮ menu → Activate reverse engineering (REA).")
                if (voiceMode) system += VOICE_SPOKEN_STYLE
                var recalls = 0
                var searches = 0
                while (true) {
                    // Messages the person sent while Arin was working go in before the next step.
                    val notes = takeSteering(run)
                    if (notes.isNotEmpty()) {
                        val joined = "[While you were working, the person sent this. Follow it; it overrides earlier plans where they conflict.]\n" +
                            notes.joinToString("\n")
                        val last = turns.lastIndex
                        if (last >= 0 && turns[last].fromUser) turns[last] = turns[last].copy(text = turns[last].text + "\n\n" + joined)
                        else turns += Turn(true, joined)
                        notes.forEach { n ->
                            val s = com.arin.data.CommandRun(n, "", null, kind = "steer")
                            ran += s
                            run.liveCommands += s
                        }
                    }
                    val text = StringBuilder()
                    var streamRetries = 0
                    val interrupted = try {
                        while (true) {
                            // After a cut-off reply, send back what came so far and ask the model to carry on, so a
                            // retry continues from where it stopped instead of starting over.
                            val streamTurns = if (text.isEmpty()) turns
                                else turns + Turn(false, text.toString()) +
                                    Turn(true, "Your reply above was cut off mid-way. Continue it from exactly where it stopped. Do not repeat anything already written and do not start over — just carry straight on.")
                            try {
                                val sent = if (saveCredits) dedupeTurns(streamTurns) else streamTurns
                                // Speed check: how big the request is and how long the service takes to start answering.
                                val t0 = System.currentTimeMillis()
                                var firstAt = 0L
                                var lastUi = 0L
                                val sentChars = system.length + sent.sumOf { it.text.length }
                                val waitJob = launch {
                                    while (firstAt == 0L) {
                                        kotlinx.coroutines.delay(1_000)
                                        val secs = (System.currentTimeMillis() - t0) / 1000
                                        if (firstAt == 0L && secs >= 3) run.status = "Waiting for ${m.substringAfterLast('/')} to start answering… ${secs}s"
                                    }
                                }
                                try {
                                    Llm.stream(p, key, m, system, sent, cache = saveCredits, endpoint = endpoint).collect { d ->
                                        if (run.steerPending) throw SteerInterrupt()
                                        if (firstAt == 0L) { firstAt = System.currentTimeMillis(); waitJob.cancel(); run.status = null }
                                        val nowMs = System.currentTimeMillis()
                                        // Redraw at most ~15 times a second: re-rendering the whole reply on every token
                                        // made long answers crawl on the phone.
                                        val redraw = nowMs - lastUi >= 66
                                        when (d) {
                                            is Delta.Text -> {
                                                text.append(d.text)
                                                if (redraw) {
                                                    lastUi = nowMs
                                                    val now = text.toString()
                                                    // Hold back what may be a search request; show everything else as it arrives.
                                                    run.liveText = if (useSearch && searches < Prompt.MAX_SEARCHES && Prompt.maybeSearch(now)) "" else Prompt.clean(now)
                                                }
                                            }
                                            is Delta.Thinking -> {
                                                if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                                                thinking.append(d.text)
                                                if (redraw) { lastUi = nowMs; run.liveThinking = thinking.toString() }
                                            }
                                        }
                                    }
                                } finally {
                                    waitJob.cancel()
                                    if (run.status?.startsWith("Waiting for ") == true) run.status = null
                                }
                                // Show the final text even if the last tokens fell between redraws.
                                text.toString().let { now -> run.liveText = if (useSearch && searches < Prompt.MAX_SEARCHES && Prompt.maybeSearch(now)) "" else Prompt.clean(now) }
                                if (thinking.isNotEmpty()) run.liveThinking = thinking.toString()
                                val end = System.currentTimeMillis()
                                runCatching {
                                    com.arin.CrashLog.note(app, "speed", "Reply speed: ${m.substringAfterLast('/')}",
                                        "Service: ${if (p == Provider.Custom) com.arin.llm.CustomApi.label() else p.label}\n" +
                                            "Model: $m\nSent: ~${sentChars / 4} tokens ($sentChars chars, ${sent.size} turns)\n" +
                                            "Time until the first word: ${if (firstAt == 0L) "none" else "%.1f s".format((firstAt - t0) / 1000.0)}\n" +
                                            "Total time: ${"%.1f".format((end - t0) / 1000.0)} s\nReply: ${text.length} chars, thinking: ${thinking.length} chars")
                                }
                                break
                            } catch (e: SteerInterrupt) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Free models sometimes send an incomplete or invalid reply the gateway drops: retry a
                                // couple of times, continuing from the partial text so nothing already written is lost.
                                // A dropped connection or a flaky gateway: retry a few times, briefly, then show the error
                                // (don't make the person wait minutes). Progress so far is kept for Resume.
                                val network = e is java.io.IOException || Prompt.isRetryable(e.message)
                                val maxTries = 3
                                if (streamRetries < maxTries && network) {
                                    streamRetries++
                                    val wait = listOf(2, 3, 5)[streamRetries - 1]
                                    for (left in wait downTo 1) {
                                        run.status = if (text.isEmpty()) "Retrying… (${streamRetries}/$maxTries)"
                                            else "Reply cut off — continuing where it stopped… (${streamRetries}/$maxTries)"
                                        kotlinx.coroutines.delay(1_000)
                                    }
                                    run.status = null
                                    continue
                                }
                                throw e
                            }
                        }
                        false
                    } catch (e: SteerInterrupt) {
                        true
                    }
                    if (interrupted) {
                        // Keep what was written so far, then continue with the person's new message.
                        val partial = text.toString()
                        if (partial.isNotBlank()) turns += Turn(false, partial + "\n[Stopped here because the person sent a message.]")
                        run.liveText = ""
                        continue
                    }
                    val full = text.toString()
                    val underLimit = ran.count { it.kind != "steer" } < Prompt.MAX_COMMANDS
                    // Install / read / write / edit (Claude Code-style tools), and terminal commands. They work even when
                    // the terminal is switched off: the person is asked once, and Arin's Linux is set up if it's missing.
                    val action = if (underLimit) Prompt.agentAction(full) else null
                    val cmd = if (underLimit && action == null) Prompt.command(full) else null
                    if (action != null || cmd != null) {
                        run.liveText = ""
                        val label = when (action) {
                            is Prompt.AgentAction.Install -> "install " + action.spec
                            is Prompt.AgentAction.Read -> "read " + action.path + if (action.from > 1) " (from line ${action.from})" else ""
                            is Prompt.AgentAction.Write -> "write " + action.path + " (${action.content.lines().size} lines)"
                            is Prompt.AgentAction.Edit -> "edit " + action.path
                            null -> cmd!!
                        }
                        var allowed = true
                        if (!terminalOn) {
                            allowed = askToRun("Let Arin use the terminal for this task?\n\n$label", tool = false, force = true, fromChat = run.chat)
                            if (allowed) toggleTerminal(true)
                        } else if (action !is Prompt.AgentAction.Read) {
                            allowed = askToRun(if (action is Prompt.AgentAction.Write) label + "\n\n" + action.content.take(1_500) else
                                if (action is Prompt.AgentAction.Edit) "$label\n\n− ${action.old.take(600)}\n+ ${action.new.take(600)}" else label, tool = false, fromChat = run.chat)
                        }
                        val shellProblem = if (allowed) com.arin.terminal.AgentTools.ensureShell(app) { run.status = it } else null
                        val step = if (!allowed) {
                            com.arin.data.CommandRun(label, "The person chose not to allow this.", null, ran = false)
                        } else if (shellProblem != null) {
                            com.arin.data.CommandRun(label, shellProblem, 1)
                        } else {
                            run.status = when (action) {
                                is Prompt.AgentAction.Install -> "Installing " + action.spec.take(60)
                                is Prompt.AgentAction.Read -> "Reading " + action.path.take(60)
                                is Prompt.AgentAction.Write -> "Writing " + action.path.take(60)
                                is Prompt.AgentAction.Edit -> "Editing " + action.path.take(60)
                                null -> "Running: " + cmd!!.lineSequence().first().take(80)
                            }
                            val r = when (action) {
                                is Prompt.AgentAction.Install -> com.arin.terminal.AgentTools.install(app, action.spec)
                                is Prompt.AgentAction.Read -> com.arin.terminal.AgentTools.read(app, action.path, action.from)
                                is Prompt.AgentAction.Write -> com.arin.terminal.AgentTools.write(app, action.path, action.content)
                                is Prompt.AgentAction.Edit -> com.arin.terminal.AgentTools.edit(app, action.path, action.old, action.new)
                                null -> com.arin.terminal.Shell.run(app, cmd!!)
                            }
                            run.status = null
                            com.arin.data.CommandRun(label, r.forModel(), r.exitCode)
                        }
                        ran += step
                        run.liveCommands += step
                        turns += Turn(false, full)
                        val failed = step.ran && step.exitCode != 0
                        failStreak = if (failed) failStreak + 1 else 0
                        turns += Turn(
                            true,
                            when {
                                !step.ran -> "I chose not to allow that. Continue without it, or suggest another way."
                                !failed -> "Done: $label (exit code ${step.exitCode ?: "unknown"}). Output:\n```\n${step.output}\n```\n" +
                                    "Continue the task: run the next command if needed, or answer when you're done."
                                else -> "Command FAILED (exit code ${step.exitCode ?: "unknown"}). Output:\n```\n${step.output}\n```\n" +
                                    fixAdvice(failStreak, Prompt.errorQuery(step.output, label), sources)
                            },
                        )
                        continue
                    }
                    val call = if (ran.count { it.kind != "steer" } < Prompt.MAX_COMMANDS && com.arin.mcp.Mcp.connected(activeNames).isNotEmpty()) Prompt.toolCall(full) else null
                    if (call != null) {
                        run.liveText = ""
                        val (name, body) = call
                        val step = if (name == "find_tools") {
                            com.arin.data.CommandRun("find_tools: $body", com.arin.mcp.Mcp.find(body, allowed = activeNames), 0, kind = "tool")
                        } else {
                            val args = runCatching {
                                kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject
                            }.getOrNull()
                            val label = "$name ${body.take(400)}"
                            when {
                                args == null -> com.arin.data.CommandRun(label, "The arguments weren't one valid JSON object. Send them as {\"name\": value}.", 1, kind = "tool")
                                !askToRun(label, tool = true, fromChat = run.chat) -> com.arin.data.CommandRun(label, "The person chose not to allow this tool call.", null, ran = false, kind = "tool")
                                else -> {
                                    run.status = "Using $name"
                                    val r = com.arin.mcp.Mcp.call(app, name, args, activeNames)
                                    run.status = null
                                    com.arin.data.CommandRun(label, shorten(r.text), if (r.isError) 1 else 0, kind = "tool")
                                }
                            }
                        }
                        ran += step
                        run.liveCommands += step
                        turns += Turn(false, full)
                        turns += Turn(
                            true,
                            if (!step.ran) "I didn't allow that tool call. Continue without it, or suggest another way."
                            else if (step.exitCode != 0) {
                                failStreak++
                                "Result of $name (FAILED):\n```\n${step.output}\n```\n" + fixAdvice(failStreak, Prompt.errorQuery(step.output, name), sources)
                            } else {
                                failStreak = 0
                                "Result of $name:\n```\n${step.output}\n```\n" +
                                    "Continue the task: call the next tool or command if needed, or answer when you're done."
                            },
                        )
                        continue
                    }
                    // Past chats: read what was said before.
                    val recallQuery = if (memoryFlag && recalls < 4) Prompt.recall(full) else null
                    if (recallQuery != null) {
                        recalls++
                        run.liveText = ""
                        run.status = "Remembering “$recallQuery”"
                        val found = withContext(Dispatchers.IO) { runCatching { store.recall(recallQuery, run.chat) }.getOrDefault("") }
                        run.status = null
                        val result = if (found.isBlank())
                            "No past chat mentions \"$recallQuery\". Try other words once, or answer from this chat and ask the person for the detail."
                        else "Matching parts of past chats:\n\n$found\n\nNow answer using these where they help."
                        val step = com.arin.data.CommandRun("recall: $recallQuery", result.take(2_000), 0, kind = "tool")
                        ran += step
                        run.liveCommands += step
                        turns += Turn(false, full)
                        turns += Turn(true, result)
                        continue
                    }
                    // Internet steps: show pictures or videos, read a page, download a file.
                    if (webSteps < Prompt.MAX_WEB_STEPS) {
                        val media = Prompt.mediaRequest(full)
                        val page = if (media == null) Prompt.fetchUrl(full) else null
                        val file = if (media == null && page == null) Prompt.download(full) else null
                        if (media != null || page != null || file != null) {
                            webSteps++
                            run.liveText = ""
                            val result: String = when {
                                media != null -> {
                                    val (video, q) = media
                                    run.status = if (video) "Finding videos of “$q”" else "Finding pictures of “$q”"
                                    val found = runCatching {
                                        if (video) com.arin.media.MediaSearch.videos(q) else com.arin.media.MediaSearch.images(q)
                                    }.getOrDefault(emptyList())
                                    foundMedia += found.filter { f -> foundMedia.none { it.url == f.url } }
                                    if (found.isEmpty()) "No ${if (video) "videos" else "pictures"} were found for \"$q\". Try other words, or tell the person."
                                    else "Found ${found.size} ${if (video) "videos" else "pictures"} for \"$q\" (" +
                                        found.take(6).joinToString("; ") { it.title.take(60) } +
                                        "). They are shown to the person right under your reply. Now finish your answer; don't list their links."
                                }
                                page != null -> {
                                    run.status = "Reading " + page.removePrefix("https://").removePrefix("http://").take(60)
                                    "Content of $page:\n\n" + runCatching { com.arin.builder.WebTools.fetch(page) }.getOrElse { "Couldn't open it: ${it.message}" }.take(24_000)
                                }
                                else -> {
                                    val (name, url) = file!!
                                    run.status = "Downloading " + url.substringAfterLast('/').substringBefore('?').take(60)
                                    downloadFile(app, url, name)
                                }
                            }
                            run.status = null
                            val label = when {
                                media != null -> (if (media.first) "videos: " else "pictures: ") + media.second
                                page != null -> "fetch $page"
                                else -> "download ${file!!.second}"
                            }
                            val step = com.arin.data.CommandRun(label, result.take(2_000), 0, kind = "tool")
                            ran += step
                            run.liveCommands += step
                            turns += Turn(false, full)
                            turns += Turn(true, result)
                            continue
                        }
                    }
                    val working = ran.any { it.kind != "steer" && !it.command.startsWith("recall: ") }
                    val q = when {
                        deep && searches < Prompt.MAX_DEEP_SEARCHES -> Prompt.searchQueryAnywhere(full)
                        (useSearch || useTerminal) && working && searches < Prompt.MAX_TASK_SEARCHES -> Prompt.searchQueryAnywhere(full)
                        useSearch && searches < Prompt.MAX_SEARCHES -> Prompt.searchQuery(full)
                        else -> null
                    }
                    if (q == null) {
                        val saidNow = listOf(userSaid) + ran.filter { it.kind == "steer" }.map { it.command }
                        val mayBuild = saidNow.any { Prompt.saysBuild(it, hasChatBuild) }
                        // The person said "build" but the reply has no <build> tag: ask for it once.
                        if (buildNudges < 2 && saidNow.any { Prompt.ordersBuild(it) } &&
                            Prompt.buildRequest(full) == null && Prompt.selfUpdate(full) == null &&
                            !chatBuildRunning() && !Prompt.clean(full).trimEnd().endsWith("?")
                        ) {
                            buildNudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "The person told you to build/push to GitHub, but your reply had no <build> tag, so nothing started. " +
                                    "STOP using the terminal for this — git and the GitHub token do NOT work from the terminal. Send the tag " +
                                    "NOW: <build name=\"App name\">the complete numbered specification of EVERYTHING discussed</build>, with at " +
                                    "most one short sentence before it. Build mode pushes to GitHub, builds the APK and posts the APK + zip " +
                                    "links here. If they meant something else, just answer them.",
                            )
                            run.liveText = ""
                            continue
                        }
                        // Announced a build or update ("Build spec going out now:") but left out the tag? Ask for it.
                        if (nudges < 3 && Prompt.promisedBuildWithoutTag(full) && (mayBuild || full.takeLast(300).contains("updat", true))) {
                            nudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "You said the build is starting, but your reply had no <build> tag, so nothing started. Send it now: " +
                                    "<build name=\"App name\">the full description of the app</build> (or <update_arin>…</update_arin> " +
                                    "for a change to Arin). Only the tag and at most one short sentence.",
                            )
                            run.liveText = ""
                            continue
                        }
                        // Research: it stopped with a short status line instead of searching or writing the report. Push it on.
                        val stalled = if (deep) !Prompt.looksLikeReport(full) else searches > 0 && Prompt.isShortStatus(full)
                        if ((deep || useSearch) && researchNudges < 8 && searches < Prompt.MAX_DEEP_SEARCHES &&
                            Prompt.searchQueryAnywhere(full) == null && stalled
                        ) {
                            researchNudges++
                            turns += Turn(false, full)
                            val mustReport = searches >= 4 || researchNudges >= 4
                            turns += Turn(
                                true,
                                if (mustReport)
                                    "Stop searching now and WRITE THE FULL ANSWER using everything you've found so far. Put the actual " +
                                        "information (names, numbers, addresses, facts — not a summary of your steps), with headings and a " +
                                        "Sources list, and end with one line telling me what I can do next. Do not reply with another status line."
                                else
                                    "You stopped with a status line instead of finishing. Don't stop. Do ONE now: run the next search as the " +
                                        "ONLY thing in your reply — <search>your query</search> — and nothing else; OR, if you have enough, write " +
                                        "the FULL answer now with the actual information you found (not a summary of your steps), with headings and " +
                                        "a Sources list, and end by telling me what I can do next. Never reply with just a short status line again.",
                            )
                            run.liveText = ""
                            continue
                        }
                        // Stopped mid-task ("fixing it by…:" with no command)? Ask the model to carry on.
                        if (working && nudges < 3 && (useTerminal || com.arin.mcp.Mcp.connected(activeNames).isNotEmpty()) && Prompt.looksUnfinished(full)) {
                            nudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "You described your next step but didn't send it. Do it now: put the <run> command or <tool> call " +
                                    "as the last thing in your reply. If the task is actually finished, give the final answer instead.",
                            )
                            run.liveText = ""
                            continue
                        }
                        answer = full
                        break
                    }
                    searches++
                    run.status = "Searching the web for “$q”"
                    run.liveText = ""
                    val (hits, results) = WebSearch.searchForModel(q)
                    hits.take(5).forEach { h -> if (sources.none { it.url == h.url }) sources += Source(h.title, h.url) }
                    run.status = null
                    turns += Turn(false, "<search>$q</search>")
                    val trimmed = results
                    turns += Turn(true, trimmed + "\n\nNow answer my earlier message using these results where they help.")
                }
                if (Prompt.clean(answer).isBlank() && ran.isEmpty()) failure = "The model sent an empty reply. Tap Retry, or pick another model."
            } catch (e: CancellationException) {
                stopped = true
                answer = run.liveText // Stop pressed: keep whatever arrived.
            } catch (e: Throwable) {
                failure = e.message ?: "Something went wrong."
                com.arin.CrashLog.note(getApplication(), "Reply failed", "${p.label} · $m: ${failure}", e.stackTraceToString().take(4_000))
            } finally {
                run.checkpointJob?.cancel()
                run.checkpointJob = null
                val seconds = if (thinkStart > 0) ((System.currentTimeMillis() - thinkStart) / 1000).toInt() else 0
                if (memoryFlag) Prompt.memories(answer).forEach { remember(it) }
                // The model asked to change Arin itself: start a self-update build.
                // Updating Arin itself: only on the person's order, otherwise offer an "Update Arin now" button.
                var pendingBuild = ""
                if (failure == null && !stopped) Prompt.selfUpdate(answer)?.let { change ->
                    if (listOf(userSaid).any { Prompt.ordersUpdate(it) }) {
                        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startSelfUpdate(change)?.let { notice = it }
                        }
                    } else pendingBuild = "update\u0000$change"
                }
                // The model asked to build an app: start Build mode.
                // (Launched separately: this block also runs after Stop, when this coroutine is cancelled.)
                // It starts only when the person said so ("build", "make the APK", or "try again" after a build).
                val saidAll = listOf(userSaid) + ran.filter { it.kind == "steer" }.map { it.command }
                val buildAsked = if (failure == null && !stopped) Prompt.buildRequest(answer) else null
                var buildNote = ""
                if (buildAsked != null) {
                    val ordered = saidAll.any { Prompt.saysBuild(it, hasChatBuild) }
                    // If they said where ("build on github", "build on phone"), start there straight away.
                    val named = saidAll.firstNotNullOfOrNull { Prompt.namedBuildTarget(it) }
                    if (ordered && named != null && run.chat == chatKey()) {
                        val target = runCatching { BuildTarget.valueOf(named) }.getOrDefault(BuildTarget.CHAT)
                        val (name, description) = buildAsked
                        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startBuild(name, description, target)?.let { notice = it }
                        }
                    } else {
                        // Prepared, but nothing starts until the person picks where to build it.
                        pendingBuild = buildAsked.first + "\u0000" + buildAsked.second
                        buildNote = if (ordered) "\n\n_Where should I build it? Pick below — nothing builds until you choose._"
                        else "\n\n_Where should I build it? Pick below when you're ready._"
                    }
                }
                // Pictures and videos: found by searches, plus any the model linked in its text, plus ones it drew.
                foundMedia += com.arin.media.MediaSearch.linked(answer).filter { l -> foundMedia.none { it.url == l.url } }
                foundMedia += Prompt.drawings(answer).filter { d -> foundMedia.none { it.url == d.url } }
                val shown = com.arin.media.MediaSearch.withoutImages(Prompt.clean(answer)) + buildNote
                val didWork = ran.any { it.kind != "steer" }
                val msg = if (failure != null && shown.isBlank() && !didWork) {
                    Message(fromUser = false, text = failure!!, failed = true)
                } else if (failure != null) {
                    // Cut off mid-task: keep everything done so far, and offer Resume / Start over.
                    Message(
                        fromUser = false,
                        text = shown.ifBlank { "Did ${ran.count { it.kind != "steer" }} step(s) before stopping." } +
                            "\n\n⚠️ **Stopped:** ${failure!!.take(300)}",
                        thinking = thinking.toString(),
                        thoughtSeconds = seconds,
                        sources = sources,
                        commands = ran.toList(),
                        media = foundMedia.toList(),
                        interrupted = true,
                    )
                } else {
                    if (failure != null) notice = failure
                    Message(
                        fromUser = false,
                        text = shown.ifBlank { if (ran.isNotEmpty()) "Ran ${ran.size} command${if (ran.size == 1) "" else "s"}." else "(Stopped)" },
                        thinking = thinking.toString(),
                        thoughtSeconds = seconds,
                        sources = sources,
                        commands = ran.toList(),
                        media = foundMedia.toList(),
                        pendingBuild = pendingBuild,
                    )
                }
                runs.remove(run.chat)
                if (run.chat == chatKey()) {
                    // This chat is open: show the answer here. Append in place (no full-list rebuild) when nothing
                    // moved; only realign if the user navigated away and back during the reply.
                    if (messages.size != run.base.size || messages.lastOrNull()?.id != run.base.lastOrNull()?.id) {
                        messages.clear(); messages.addAll(run.base)
                    }
                    messages.add(msg)
                    persist()
                    lastFinished = msg
                } else if (run.chat != TEMP_CHAT) {
                    // The person moved to another chat: save the answer into its own chat.
                    ArinApp.scope.launch(Dispatchers.IO) {
                        store.load(run.chat)?.let { c -> store.save(c.copy(messages = run.base + msg, updated = System.currentTimeMillis())) }
                        withContext(Dispatchers.Main) { refreshChats() }
                    }
                }
                if (runs.isEmpty()) ReplyService.stop(app)
                // Messages sent after the model's last step become the next message.
                ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (run.steering.isNotEmpty() && run.chat == chatKey() && !streaming) {
                        val rest = run.steering.joinToString("\n")
                        run.steering.clear()
                        run.steerPending = false
                        send(rest)
                    }
                }
                // Tell the person when a chat they aren't looking at finishes.
                if ((!ArinApp.inForeground || run.chat != chatKey()) && notifyOnReply && run.chat != TEMP_CHAT && !msg.failed) {
                    ReplyService.notifyReply(app, run.chat, run.chatTitle, msg.text)
                }
            }
        }
    }

    /** Every project file goes with the first message: text, documents, photos and PDFs. */
    private fun projectMedia(project: Project?): List<Attachment> = project?.files.orEmpty()

    private fun makeTitle(text: String, files: List<Attachment>): String {
        val base = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: files.firstOrNull()?.name ?: "New chat"
        return if (base.length > 48) base.take(47).trimEnd() + "…" else base
    }

    private fun persist() {
        if (messages.isEmpty() || temporary) return
        val id = chatId ?: java.util.UUID.randomUUID().toString().also { chatId = it }
        val chat = Chat(id = id, title = title, updated = System.currentTimeMillis(), messages = messages.toList(), projectId = chatProject?.id, pinned = chatPinned, reaOn = reaOn,
            provider = provider.name, model = model, apiId = currentSavedApi()?.slotId.orEmpty())
        ArinApp.scope.launch(Dispatchers.IO) {
            store.save(chat)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    // ---------------- Attachments ----------------

    /** Name of the file being read right now (big files take a moment), or null. */
    var readingFile by mutableStateOf<String?>(null)
        private set

    fun attach(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                readingFile = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file"
                try {
                    pending += Attachments.read(getApplication(), uri, forTerminal = terminalOn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notice = e.message ?: "Couldn't read that file."
                } finally {
                    readingFile = null
                }
            }
        }
    }

    /**
     * Attaches an installed app's APK (plus split APKs) to the message, copied where REA and the terminal can
     * read it (Arin's Linux home, or Download/Arin), for analysis in a reverse-engineering chat.
     */
    fun attachInstalledApp(app: com.arin.data.InstalledApps.App) {
        viewModelScope.launch {
            readingFile = "${app.label}.apk"
            try {
                val ctx = getApplication<Application>()
                val files = com.arin.data.InstalledApps.copyApks(ctx, app)
                files.forEach { f ->
                    val a = Attachments.read(ctx, android.net.Uri.fromFile(f), forTerminal = true)
                    pending += a.copy(size = f.length())
                }
                files.forEach { it.delete() }
                notice = "Attached ${app.label} ${app.version}".trim() +
                    if (files.size > 1) " (${files.size} APK parts: base + ${files.size - 1} split)" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice = "Couldn't copy ${app.label}'s APK: ${e.message}"
            } finally {
                readingFile = null
            }
        }
    }

    fun removePending(a: Attachment) {
        pending.remove(a)
        Attachments.deleteFiles(listOf(a))
    }

    // ---------------- Chats list ----------------

    /** Start a new chat, optionally inside [project]. */
    fun newChat(project: Project? = null, temporary: Boolean = false) {
        if (this.temporary) runs[TEMP_CHAT]?.job?.cancel() // other chats keep working in the background
        this.temporary = temporary
        reaOn = false
        reaMissing = false
        chatBuildState = null
        chatPinned = false
        if (temporary) notice = "Temporary chat: it isn't saved and doesn't use memory."
        chatId = null
        title = "New chat"
        chatProject = project
        messages.clear()
        Attachments.deleteFiles(pending.toList())
        pending.clear()
        editing = null
    }

    /** The one chat where REA (reverse engineering) works. Everywhere else it stays off. */
    fun openReverseEngineering() {
        val existing = chats.firstOrNull { it.title.equals(REVERSE_ENG_TITLE, ignoreCase = true) }
        if (existing != null) {
            open(existing.id)
        } else {
            newChat()
            title = REVERSE_ENG_TITLE
            notice = "Reverse engineering chat: REA tools work here. Other chats stay normal."
        }
    }

    /** True when the open chat is the reverse-engineering chat (the only one that may use REA). */
    private fun isReverseEngineeringChat() = title.equals(REVERSE_ENG_TITLE, ignoreCase = true)

    /** REA is switched on in the open chat but isn't installed/connected: the chat shows an Install REA bar. */
    var reaMissing by mutableStateOf(false)

    /** REA turned on for THIS chat from the ⋮ menu (the "Reverse engineering" chat always has it on). */
    var reaOn by mutableStateOf(false)
        private set

    /** Whether REA is active in the open chat (its name, or the per-chat switch). */
    fun reaActive() = isReverseEngineeringChat() || reaOn

    fun toggleRea(on: Boolean) {
        reaOn = on
        reaMissing = on && !com.arin.terminal.Linux.installed(getApplication())
        if (on) {
            if (!terminalOn) { terminalOn = true; settings.terminalOn = true }
            notice = when {
                !com.arin.terminal.Linux.installed(getApplication()) ->
                    "REA is on for this chat. Install Linux and REA in Settings → Linux first, then it will work here."
                com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name) == null ->
                    "REA is on for this chat. It starts on your next message (first start takes a few seconds)."
                else -> "REA is on for this chat: all its tools are available here."
            }
        } else {
            notice = "REA is off for this chat."
        }
        persist()
    }

    fun open(id: String) {
        if (id == chatId) return
        if (temporary) runs[TEMP_CHAT]?.job?.cancel() // other chats keep working in the background
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) } ?: run {
                notice = "That chat couldn't be opened."
                return@launch
            }
            temporary = false
            reaOn = chat.reaOn
            title = chat.title
            chatBuildState = null
            reaMissing = reaActive() && !com.arin.terminal.Linux.installed(getApplication())
            chatPinned = chat.pinned
            chatId = chat.id
            title = chat.title
            chatProject = chat.projectId?.let { pid -> projects.firstOrNull { it.id == pid } }
            messages.clear()
            // A chat that's still working: show what it started from (its live reply shows under it), not a checkpoint copy.
            messages.addAll(runs[chat.id]?.base ?: chat.messages)
            applyChatSelection(chat)
            refreshChatBuild()
            Attachments.deleteFiles(pending.toList())
            pending.clear()
            editing = null
        }
    }

    fun rename(id: String, newTitle: String) {
        val t = newTitle.trim().ifEmpty { return }
        if (id == chatId) title = t
        viewModelScope.launch(Dispatchers.IO) {
            store.rename(id, t)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun moveToProject(id: String, project: Project?) {
        if (id == chatId) chatProject = project
        viewModelScope.launch(Dispatchers.IO) {
            store.moveToProject(id, project?.id)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun delete(id: String) {
        if (id == chatId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun deleteAll() {
        newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteAll()
            projectStore.deleteAll()
            withContext(Dispatchers.Main) {
                refreshChats()
                refreshProjects()
            }
        }
    }

    fun search(q: String) {
        query = q
        refreshChats()
    }

    private fun refreshChats() {
        val q = query
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.search(q) }
            if (q == query) {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

    fun chatsIn(project: Project): List<ChatSummary> = chats.filter { it.projectId == project.id }

    // ---------------- Projects ----------------

    private fun refreshProjects() {
        projects.clear()
        projects.addAll(projectStore.list())
        chatProject = chatProject?.let { cur -> projects.firstOrNull { it.id == cur.id } }
    }

    fun createProject(name: String): Project {
        val p = Project(name = name.trim().ifEmpty { "New project" })
        projectStore.save(p)
        refreshProjects()
        return p
    }

    fun updateProject(p: Project) {
        projectStore.save(p)
        refreshProjects()
    }

    fun deleteProject(p: Project) {
        if (chatProject?.id == p.id) chatProject = null
        viewModelScope.launch(Dispatchers.IO) {
            store.detachProject(p.id)
            projectStore.delete(p.id)
            withContext(Dispatchers.Main) {
                refreshProjects()
                refreshChats()
            }
        }
    }

    fun addProjectFiles(p: Project, uris: List<Uri>) {
        viewModelScope.launch {
            var current = projects.firstOrNull { it.id == p.id } ?: p
            uris.forEach { uri ->
                try {
                    current = current.copy(files = current.files + Attachments.read(getApplication(), uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notice = e.message ?: "Couldn't read that file."
                }
            }
            updateProject(current)
        }
    }

    fun removeProjectFile(p: Project, file: Attachment) {
        Attachments.deleteFiles(listOf(file))
        updateProject(p.copy(files = p.files - file))
    }

    // ---------------- Memory ----------------

    private fun remember(fact: String) {
        if (memoryStore.add(fact)) {
            memories.clear()
            memories.addAll(memoryStore.list())
        }
    }

    fun addMemory(fact: String) = remember(fact)

    fun forget(m: Memory) {
        memoryStore.remove(m.id)
        memories.remove(m)
    }

    fun forgetAll() {
        memoryStore.clear()
        memories.clear()
    }

    // ---------------- Settings ----------------

    fun chooseProvider(p: Provider) {
        provider = p
        settings.provider = p
        models = DefaultModels.of(p)
        model = settings.model(p) ?: models.first().id
        loadModels()
        rememberChatSelection()
    }

    // ---- Custom API ----

    /** Custom APIs saved by the person; one tap switches to any of them (each keeps its own key and model). */
    val savedApis = androidx.compose.runtime.mutableStateListOf<com.arin.data.SavedApi>().apply {
        addAll(settings.savedApis)
        // An API set up in an older version (base URL + key, never added to the list): put it at the top so it isn't lost.
        val base = settings.customBaseUrl
        val oldKey = settings.keyFor(base)
        if (oldKey.isNotBlank() && none { it.baseUrl == base }) {
            val api = com.arin.data.SavedApi(
                com.arin.llm.CustomApi.label(base), base, settings.customFormat.name,
                settings.customModel.ifBlank { settings.model(Provider.Custom).orEmpty() }, java.util.UUID.randomUUID().toString(),
            )
            settings.setApiKey(api, oldKey)
            add(0, api)
            settings.savedApis = toList()
            if (settings.activeApiId.isBlank()) settings.activeApiId = api.slotId
        }
    }

    /** Saves the API in use now under [name] as a NEW entry (or renames the one in use if it has that name). */
    fun saveCurrentApi(name: String) {
        val n = name.trim().ifBlank { com.arin.llm.CustomApi.label(customBaseUrl) }
        val cur = currentSavedApi()
        val api = if (cur != null && cur.name == n) cur.copy(format = customFormat.name, model = customModel)
            else com.arin.data.SavedApi(n, customBaseUrl, customFormat.name, customModel, java.util.UUID.randomUUID().toString())
        settings.setApiKey(api, settings.key(Provider.Custom))
        val i = savedApis.indexOfFirst { it.slotId == api.slotId }
        if (i >= 0) savedApis[i] = api else savedApis.add(0, api)
        settings.savedApis = savedApis.toList()
        settings.activeApiId = api.slotId
        notice = "Saved \"$n\""
    }

    fun removeSavedApi(api: com.arin.data.SavedApi) {
        syncFreePoolSoon()
        savedApis.removeAll { it.slotId == api.slotId }
        settings.savedApis = savedApis.toList()
        settings.setApiKey(api, "")
        if (settings.activeApiId == api.slotId) settings.activeApiId = ""
    }

    // ---- Each chat keeps its own model and API ----

    /** True while a chat's saved choice is being put back (opening a chat): nothing is written then. */
    private var restoringSelection = false
    /** A model id to keep when the model list loads (a chat's choice that the service may not list). */
    private var pinnedModel: Pair<Provider, String>? = null

    /** Puts back the model and API [c] was last using. Other chats keep their own. */
    private fun applyChatSelection(c: Chat) {
        val p = runCatching { Provider.valueOf(c.provider) }.getOrNull() ?: return
        restoringSelection = true
        try {
            val api = if (p == Provider.Custom) savedApis.firstOrNull { it.slotId == c.apiId } else null
            if (api != null) useSavedApi(api, quiet = true) else if (p != provider || p == Provider.Custom) chooseProvider(p)
            if (c.model.isNotBlank()) {
                pinnedModel = p to c.model
                model = c.model
                settings.setModel(p, c.model)
            }
        } finally {
            restoringSelection = false
        }
    }

    /** Saves the open chat's model/API choice on that chat only (without moving it up the chats list). */
    private fun rememberChatSelection() {
        if (restoringSelection || temporary) return
        val id = chatId ?: return
        val prov = provider.name; val mod = model; val api = currentSavedApi()?.slotId.orEmpty()
        ArinApp.scope.launch(Dispatchers.IO) {
            runCatching {
                store.load(id)?.let { c -> if (c.provider != prov || c.model != mod || c.apiId != api) store.save(c.copy(provider = prov, model = mod, apiId = api)) }
            }
        }
    }

    /** Switches replies to a saved API, with its own key and model (even when another API shares its address). */
    fun useSavedApi(api: com.arin.data.SavedApi, quiet: Boolean = false) {
        val format = runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI)
        val key = settings.apiKey(api)
        settings.activeApiId = api.slotId
        setCustomBase(api.baseUrl, format)
        // The request code reads the key/model stored for the address: load THIS API's into it.
        settings.setKeyFor(api.baseUrl, key)
        if (api.model.isNotBlank()) settings.setModelFor(api.baseUrl, api.model)
        chooseProvider(Provider.Custom)
        activeApiTick++
        if (!quiet) notice = "Using ${api.name} in this chat" + if (key.isBlank()) " (no API key saved for it: tap Edit to add one)" else ""
        rememberChatSelection()
        if (!quiet) offlineModelFor(api)?.let { file ->
            val app = getApplication<Application>()
            ArinApp.scope.launch {
                if (com.arin.llm.LocalModel.lastModel(app)?.name == file.name && com.arin.llm.LocalModel.isUp()) return@launch
                offlineStatus = "Loading ${file.nameWithoutExtension}…"
                val problem = com.arin.llm.LocalModel.start(app, file) { offlineStatus = it }
                offlineStatus = problem ?: "${file.nameWithoutExtension} is running on this phone."
                notice = problem ?: "${api.name} is ready (offline)."
            }
        }
    }

    // ---- Offline models (llama.cpp in Linux) ----

    /** The GGUF file behind a saved offline API ("local:<file name>" id), if it's on the phone. */
    fun offlineModelFor(api: com.arin.data.SavedApi?): java.io.File? {
        val name = api?.id?.takeIf { it.startsWith("local:") }?.removePrefix("local:") ?: return null
        return java.io.File(com.arin.llm.LocalModel.modelsDir(getApplication()), name).takeIf { it.exists() }
    }

    var offlineBusy by mutableStateOf(false)
        private set
    var offlineStatus by mutableStateOf("")
        private set

    fun offlineDownloaded(): List<java.io.File> { activeApiTick; return com.arin.llm.LocalModel.downloaded(getApplication()) }

    /**
     * Gets an offline model ready end to end: Linux engine (once), the model download (resumes), starts it,
     * and adds it to Your APIs so it can be picked from the chat like any other API.
     */
    fun setUpOfflineModel(repo: String, label: String) {
        if (offlineBusy) return
        val app = getApplication<Application>()
        if (!com.arin.terminal.Linux.installed(app)) {
            offlineStatus = "Install Linux first: Settings → Linux → Install Linux. Then come back here."
            return
        }
        offlineBusy = true
        ArinApp.scope.launch {
            try {
                if (!com.arin.llm.LocalModel.serverInstalled(app)) {
                    val p = com.arin.llm.LocalModel.installServer(app) { offlineStatus = it }
                    if (p != null) { offlineStatus = p; return@launch }
                }
                val file = com.arin.llm.LocalModel.downloadModel(app, repo) { offlineStatus = it }
                val name = label.ifBlank { file.nameWithoutExtension }
                val api = com.arin.data.SavedApi("📱 $name (offline)", com.arin.llm.LocalModel.BASE_URL,
                    com.arin.llm.ApiFormat.OpenAI.name, com.arin.llm.LocalModel.MODEL_ID, "local:${file.name}")
                settings.setApiKey(api, com.arin.llm.LocalModel.DUMMY_KEY)
                val i = savedApis.indexOfFirst { it.slotId == api.slotId }
                if (i >= 0) savedApis[i] = api else savedApis.add(0, api)
                settings.savedApis = savedApis.toList()
                kotlinx.coroutines.withContext(Dispatchers.Main) { useSavedApi(api) }
            } catch (e: Exception) {
                offlineStatus = "Stopped: ${e.message ?: e.javaClass.simpleName}. Tap the model again to resume the download."
            } finally {
                offlineBusy = false
            }
        }
    }

    /** Adds a model file that's already on the phone to Your APIs and switches to it. */
    fun useOfflineFile(file: java.io.File) {
        val api = com.arin.data.SavedApi("📱 ${file.nameWithoutExtension} (offline)", com.arin.llm.LocalModel.BASE_URL,
            com.arin.llm.ApiFormat.OpenAI.name, com.arin.llm.LocalModel.MODEL_ID, "local:${file.name}")
        settings.setApiKey(api, com.arin.llm.LocalModel.DUMMY_KEY)
        if (savedApis.none { it.slotId == api.slotId }) { savedApis.add(0, api); settings.savedApis = savedApis.toList() }
        useSavedApi(api)
    }

    fun deleteOfflineModel(file: java.io.File) {
        val app = getApplication<Application>()
        ArinApp.scope.launch {
            if (com.arin.llm.LocalModel.lastModel(app)?.name == file.name) com.arin.llm.LocalModel.stop(app)
            file.delete()
            savedApis.removeAll { it.id == "local:${file.name}" }
            settings.savedApis = savedApis.toList()
            activeApiTick++
            offlineStatus = "Deleted ${file.name}."
        }
    }

    fun stopOfflineModel() {
        val app = getApplication<Application>()
        ArinApp.scope.launch { com.arin.llm.LocalModel.stop(app); offlineStatus = "Offline model stopped (frees the phone's memory)." }
    }

    /** Bumped when the active saved API changes, so the UI re-reads it. */
    var activeApiTick by mutableStateOf(0)
        private set

    /**
     * Adds a new API, or changes [old], from one form: name, base URL, key, model and format.
     * Any number can be saved, including several for the same base URL. Returns a problem to show, or null.
     */
    fun saveApi(old: com.arin.data.SavedApi?, name: String, url: String, key: String, model: String, format: com.arin.llm.ApiFormat, free: Boolean = false): String? {
        val base = com.arin.llm.CustomApi.normalize(url, format)
        if (base.isBlank()) return "Enter the base URL, e.g. https://example.com/v1"
        if (!base.startsWith("https://") && !base.startsWith("http://")) return "The base URL must start with https://"
        val id = old?.id?.ifBlank { null } ?: java.util.UUID.randomUUID().toString()
        val api = com.arin.data.SavedApi(name.trim().ifBlank { com.arin.llm.CustomApi.label(base) }, base, format.name, model.trim(), id, free = free || old?.free == true)
        // A key left empty while editing keeps the one already saved.
        val keep = old?.let { settings.apiKey(it) }.orEmpty()
        if (old != null && old.slotId != api.slotId) settings.setApiKey(old, "")
        settings.setApiKey(api, key.ifBlank { keep })
        val list = savedApis.toMutableList()
        val i = if (old != null) list.indexOfFirst { it.slotId == old.slotId } else -1
        if (i >= 0) list[i] = api else list.add(0, api)   // newest at the top
        savedApis.clear()
        savedApis.addAll(list)
        settings.savedApis = list
        syncFreePool()
        useSavedApi(api)
        return null
    }

    private fun syncFreePoolSoon() { viewModelScope.launch { kotlinx.coroutines.yield(); syncFreePool() } }

    /** Pushes the free providers the person added into the pool the Free provider falls over across. */
    fun syncFreePool() {
        com.arin.llm.CustomApi.freePool = savedApis.filter { it.free && it.format == "OpenAI" }
            .mapNotNull { a -> settings.apiKey(a).takeIf { it.isNotBlank() }?.let { com.arin.llm.CustomApi.FreeEntry(a.baseUrl, a.model, it) } }
    }

    fun hasKeyFor(api: com.arin.data.SavedApi) = settings.apiKey(api).isNotBlank()

    /** The saved API in use right now, if any. */
    fun currentSavedApi(): com.arin.data.SavedApi? {
        activeApiTick
        if (provider != Provider.Custom) return null
        val id = settings.activeApiId
        return savedApis.firstOrNull { it.slotId == id && it.baseUrl == customBaseUrl }
            ?: savedApis.firstOrNull { it.baseUrl == customBaseUrl }
    }

    var customBaseUrl by mutableStateOf(settings.customBaseUrl)
        private set
    var customFormat by mutableStateOf(settings.customFormat)
        private set
    var customModel by mutableStateOf(settings.customModel)
        private set

    /** Switches the Custom API to another address. Each address keeps its own key and model. */
    fun setCustomBase(url: String, format: com.arin.llm.ApiFormat = customFormat) {
        settings.customFormat = format
        settings.customBaseUrl = url
        customFormat = format
        customBaseUrl = settings.customBaseUrl
        customModel = settings.customModel
        refreshCustom()
    }

    fun chooseCustomFormat(format: com.arin.llm.ApiFormat) {
        settings.customFormat = format
        customFormat = format
        refreshCustom()
    }

    fun saveCustomModel(id: String) {
        settings.customModel = id
        customModel = settings.customModel
        if (provider == Provider.Custom && id.isNotBlank()) settings.setModel(Provider.Custom, id.trim())
        refreshCustom()
    }

    private fun refreshCustom() {
        if (provider != Provider.Custom) return
        models = DefaultModels.of(Provider.Custom)
        model = settings.model(Provider.Custom) ?: models.first().id
        loadModels()
    }

    fun chooseModel(option: ModelOption) {
        model = option.id
        pinnedModel = provider to option.id
        settings.setModel(provider, option.id)
        rememberChatSelection()
    }

    fun saveKey(p: Provider, key: String) {
        settings.setKey(p, key)
        // Keep the saved API in use in step with a key typed in the API keys section,
        // and save a new address automatically so it shows in Your APIs (chat menu) from now on.
        if (p == Provider.Custom && key.isNotBlank()) {
            val cur = currentSavedApi()
            if (cur != null) settings.setApiKey(cur, key)
            else {
                val api = com.arin.data.SavedApi(
                    com.arin.llm.CustomApi.label(customBaseUrl), customBaseUrl, customFormat.name,
                    settings.model(Provider.Custom).orEmpty(), java.util.UUID.randomUUID().toString(),
                )
                settings.setApiKey(api, key)
                savedApis.add(0, api)
                settings.savedApis = savedApis.toList()
                settings.activeApiId = api.slotId
                activeApiTick++
                notice = "Saved ${api.name} to Your APIs: switch to it any time from the model menu in the chat."
            }
        }
        if (p == provider) loadModels()
    }

    fun hasKey(p: Provider) = settings.key(p).isNotBlank()

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        settings.theme = mode
    }

    fun toggleThinking(show: Boolean) {
        showThinking = show
        settings.showThinking = show
    }

    fun toggleWebSearch(on: Boolean) {
        webSearch = on
        settings.webSearch = on
    }

    fun toggleMemory(on: Boolean) {
        memoryOn = on
        settings.memoryOn = on
    }

    fun toggleNotify(on: Boolean) {
        notifyOnReply = on
        settings.notifyOnReply = on
    }

    fun modelLabel(): String = models.firstOrNull { it.id == model }?.label ?: model

    private fun loadModels() {
        val p = provider
        viewModelScope.launch {
            val list = Llm.models(p, settings.key(p))
            if (p != provider) return@launch
            models = list
            // Keep the saved choice if it still exists; otherwise take the first.
            val saved = settings.model(p)
            // A chat's chosen model stays even if the service doesn't list it.
            val pin = pinnedModel?.takeIf { it.first == p }?.second
            model = list.firstOrNull { it.id == saved }?.id ?: pin ?: list.first().id
        }
    }

    companion object {
        /** Appended to the system prompt in voice mode so replies sound like a real spoken conversation. */
        const val VOICE_SPOKEN_STYLE = "\n\nVOICE MODE: Your reply is being spoken aloud, so talk like a real person on a call, " +
            "not like written text. Keep it short and conversational — usually one to three sentences unless more is truly needed. " +
            "Use natural spoken interjections where they fit: start with things like \"Hmm,\", \"Oh,\", \"Right,\", \"Let me think —\" " +
            "when you're considering something, and a light \"haha\" when something's genuinely funny. Contractions always. " +
            "No markdown, no bullet lists, no headings, no emoji, no code blocks — if you must mention code, describe it in words. " +
            "Spell out symbols and numbers as you'd say them aloud. Don't narrate these instructions."

        /** The single chat where REA (reverse engineering) is active. */
        const val REVERSE_ENG_TITLE = "Reverse engineering"
        /** Key for a temporary (unsaved) chat's running reply. */
        const val TEMP_CHAT = "__temporary_chat__"

        /** Arin's source repository, set when this build of Arin was made; changeable in Settings → GitHub. */
        const val DEFAULT_SELF_REPO = "poisaingti-spec/aiapp-261110-1752"
        const val DEFAULT_SELF_BRANCH = "arin"

        /** After REA is installed in the built-in Linux: have the model prove it works. */
        val REA_CHECK_PROMPT = """
            REA (Reverse Engineer Anything) was just installed in Arin's built-in Linux and connected as MCP tools. Check that it really works:
            1. Run rea --version in the terminal (Arin sets COLUMNS/LINES; without a terminal size REA's CLI crashes under PRoot).
            2. Use find_tools to look up analyze_javascript_application, then create a tiny test app (mkdir -p /root/rea-test && echo 'export function add(a,b){return a+b}' > /root/rea-test/app.js) and call that tool on /root/rea-test.
            3. Tell me what worked, what REA can do on this phone (static JavaScript/web bundles, APKs, Flutter, archives…), and what needs a desktop (Ghidra, IDA, Hopper, live Chrome). Only claim what the outputs show.
            Arin is the MCP client and REA is already connected: don't run rea setup and don't create or edit .claude.json or any other agent's config.
        """.trimIndent()

        /** What "Set up REA" asks the model to do, step by step in the terminal. */
        val REA_SETUP_PROMPT = """
            Set up REA (Reverse Engineer Anything, npm package rea-agents from github.com/morluto/rea) on this phone in Termux and connect it to Arin, then verify it works. Steps:
            1. Check what's installed: node -v, npm -v. REA needs Node.js ^22.23.4, ^24.22.1 or >=26.12.1. If Node is missing or too old, run pkg update -y, then pkg install -y nodejs (or nodejs-lts if that version fits better) and check again.
            2. Install REA's Linux prerequisite and REA itself: pkg install -y procps (REA needs a procps-compatible ps on PATH), then npm install --global rea-agents --omit=optional, and confirm with rea --version.
            3. Install only what REA needs for app and website work; skip the heavy native engines (Ghidra, IDA, Hopper, Frida): use npm install --global rea-agents --omit=optional. Don't run "rea setup" or edit .claude.json or other agents' configs: Arin connects to REA itself.
            4. Run termux-setup-storage only if ~/storage doesn't exist yet (I'll approve the prompt), so REA can read files I attach (they are saved in Download/Arin).
            5. Start REA through Arin's bridge (already saved at ~/.arin/mcp-bridge.mjs) and check it answers:
               (nohup env REA_MCP_OUTPUT_SCHEMA_PROFILE=compact node ~/.arin/mcp-bridge.mjs --port 8765 -- rea mcp > ~/.arin/rea.log 2>&1 &); sleep 6; curl -s http://127.0.0.1:8765/health
               then list its tools: curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' | head -c 400; and curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | grep -o '"name":"[a-z_]*"' | wc -l
               If it fails, read ~/.arin/rea.log and fix the cause.
            6. Termux reports the platform as "android", which REA doesn't officially support; tools that launch other programs may refuse with an unsupported-host or capability_unavailable error. After the tools are listed, check one real analysis call works, for example: mkdir -p ~/rea-test && echo 'export function add(a,b){return a+b}' > ~/rea-test/app.js && rea analyze-javascript-application ~/rea-test --json | head -c 1500
            7. If REA refuses because of the host, set it up in Ubuntu inside Termux instead, where REA sees a normal Linux system: pkg install -y proot-distro && proot-distro install ubuntu, then inside it (proot-distro login ubuntu -- bash -lc '…') install Node.js 24 from NodeSource or nvm, procps and rea-agents, and start the bridge from there on the same port: proot-distro login ubuntu --bind ~/.arin:/root/.arin -- bash -lc 'nohup env REA_MCP_OUTPUT_SCHEMA_PROFILE=compact node /root/.arin/mcp-bridge.mjs --port 8765 -- rea mcp > /root/.arin/rea.log 2>&1 &'. Stop the earlier bridge first (pkill -f mcp-bridge.mjs). Then repeat the checks in steps 5 and 6.
            8. Tell me the result, including any tools that can't work on a phone. From my next message on, Arin connects to REA automatically and you can call its tools directly.
            Report each step's real output. Only say it works once a command shows it.
        """.trimIndent()
    }
}
