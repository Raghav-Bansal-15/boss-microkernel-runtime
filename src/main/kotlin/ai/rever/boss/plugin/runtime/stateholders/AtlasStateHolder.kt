package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

// region State

/**
 * The Atlas chat surface, mirrored for a host-side renderer.
 *
 * The plugin's own UI is stock Material — a transcript list, three pickers and a
 * composer — with no custom drawing, no gestures and no animation, so the surface
 * really is a list plus selections plus per-item detail. See [AtlasStateHolder]
 * for the one capability that does not survive: [pageContextAvailable] is always
 * false, and the host must say so rather than implying the assistant can see the
 * page.
 */
@Serializable
data class AtlasState(
    val messages: List<AtlasMessage> = emptyList(),
    /** The project directory the CLI session runs in — its `cwd`. */
    val workingDir: String? = null,
    /** Recent projects from the host, for the project picker. */
    val recentProjects: List<AtlasProject> = emptyList(),
    val agents: List<AtlasAgent> = emptyList(),
    /** Empty name = the implicit default (a session with no `--agent`). */
    val selectedAgent: String = "",
    val permissionMode: AtlasPermissionMode = AtlasPermissionMode.READ_ONLY,
    /** Every selectable mode with its display text, so the host can render the picker. */
    val permissionModes: List<AtlasPermissionModeOption> =
        AtlasPermissionMode.entries.map { AtlasPermissionModeOption(it, it.label, it.description) },
    /** True while a turn is in flight. */
    val sending: Boolean = false,
    /** True when a `claude` binary was found. False renders the install hint. */
    val backendAvailable: Boolean = false,
    /**
     * True once the holder has published its first state. An empty transcript is
     * a legitimate starting point, so this is what distinguishes "initialised,
     * nothing said yet" from "no state has ever arrived".
     */
    val ready: Boolean = false,
    /**
     * Always false out-of-process. The plugin's entire reason for existing is
     * reading the adjacent browser tab, and that is not reachable here — see
     * [AtlasStateHolder]. [pageContextReason] carries the explanation to show.
     */
    val pageContextAvailable: Boolean = false,
    val pageContextReason: String = "",
    /** Always false out-of-process — the host's plugin storage is not on the wire. */
    val persistenceAvailable: Boolean = false,
)

@Serializable
enum class AtlasRole { USER, ASSISTANT }

@Serializable
data class AtlasMessage(
    val id: Long,
    val role: AtlasRole,
    /** Raw markdown. A renderer parses it locally; styled text is never serialized. */
    val text: String,
    val thinking: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
)

/** A discovered Claude Code subagent. [name] is passed to `claude --agent`. */
@Serializable
data class AtlasAgent(
    val name: String,
    val description: String = "",
    val source: AtlasAgentSource = AtlasAgentSource.USER,
)

@Serializable
enum class AtlasAgentSource { USER, PROJECT, BUILT_IN }

@Serializable
data class AtlasProject(val name: String, val path: String, val lastOpened: Long = 0L)

/**
 * One selectable permission mode, as data rather than as an enum.
 *
 * This is what makes the label and description reach the host at all - see
 * [AtlasPermissionMode] for why the enum's own properties do not.
 */
@Serializable
data class AtlasPermissionModeOption(val mode: AtlasPermissionMode, val label: String, val description: String)

/**
 * Mirrors the plugin's `PermissionMode`.
 *
 * **These constructor properties do not travel.** kotlinx.serialization encodes an enum as its
 * serial name and nothing else, so a host receives `"READ_ONLY"` - not the label, description or
 * CLI value. They are kept here because the child needs [cliValue] to build the command line;
 * anything the renderer has to display is published separately as [AtlasState.permissionModes].
 */
@Serializable
enum class AtlasPermissionMode(val cliValue: String, val label: String, val description: String) {
    READ_ONLY("", "Read-only", "Answer & run allowlisted read-only queries. Cannot write files."),
    ALLOW_WRITES("acceptEdits", "Allow writes", "Also let the agent save memory, charts and notes."),
}

// endregion

// region Intent

sealed class AtlasIntent {
    /** Send one user turn. Ignored while [AtlasState.sending]. */
    data class Send(val text: String) : AtlasIntent()

    /** Abandon the in-flight turn and kill the CLI child. */
    object Cancel : AtlasIntent()

    data class SelectAgent(val name: String) : AtlasIntent()

    /** Re-scan `~/.claude/agents` and `<workingDir>/.claude/agents`. */
    object RefreshAgents : AtlasIntent()

    /** Point the session at a project directory; resets the session and re-scans agents. */
    data class SetWorkingDir(val path: String?) : AtlasIntent()

    /** Ask the host for a native folder picker, then adopt what it returns. */
    object PickDirectory : AtlasIntent()

    data class SetPermissionMode(val mode: AtlasPermissionMode) : AtlasIntent()

    /** Drop the transcript and the resumable session id. */
    object ClearConversation : AtlasIntent()
}

// endregion

/**
 * StateHolder for the Atlas plugin — the **chat** surface.
 *
 * Atlas is unusual among the tab/panel plugins in that its "AI" is not an API
 * client: every turn spawns the operator's own `claude` binary in headless
 * `--output-format stream-json` mode and folds the deltas into one growing
 * assistant message. A child JVM spawns a process exactly as well as the host
 * does, so the *chat itself works out-of-process*, session resume and all. What
 * does not survive is the thing the plugin was built for:
 *
 * - **Page context is unavailable, and that is the whole point of the plugin.**
 *   In-process, each turn reads the adjacent BOSS browser tab through
 *   `ActiveTabsProvider.getBrowserIntegration(tabId).executeJavaScript(...)` and
 *   appends the page's URL, title, text and the user's selection to the system
 *   prompt. Out-of-process the child gets `ActiveTabsProviderProxy`, whose
 *   `getBrowserIntegration` returns null unconditionally — and there is no
 *   `ExecuteJavaScript` RPC on the wire for it to call if it did not
 *   (`ActiveTabsService` offers `WatchActiveTabs`, `RefreshTabs`, `SelectTab`,
 *   `GetTabUrl`, `GetFaviconCacheKey`, `CreateBrowserTab`, `CloseTab`). So this
 *   holder never sends a page-context block, and
 *   [AtlasState.pageContextAvailable] is permanently false with
 *   [AtlasState.pageContextReason] explaining why. A host that renders this as
 *   an ordinary Claude chat is telling the truth; one that renders the amber
 *   "reading this page" chip is not.
 * - **Persistence is unavailable** — but Atlas never had any. The transcript,
 *   the selected agent, the working directory and the permission mode are all
 *   in-memory in the plugin too, and the conversation's real durable copy is the
 *   CLI's own session store under `~/.claude`. So the only thing lost is
 *   nothing: [AtlasState.persistenceAvailable] is false and matches in-process
 *   behaviour.
 * - **The directory picker survives.** `DirectoryPickerProviderProxy` is a real
 *   proxy, so [AtlasIntent.PickDirectory] works — the plugin's own reason for
 *   bypassing it (an ownerless AWT dialog appearing behind its Compose
 *   `DialogWindow`) does not apply here, because out-of-process there is no
 *   `DialogWindow` to fall behind.
 * - **Recent projects survive** through `ProjectDataProviderProxy`.
 *
 * Streaming is **coalesced**. The plugin republishes its message list on every
 * `text_delta`, which is free in-process; here every state change re-serializes
 * and re-sends the *entire* envelope, so a token-per-envelope stream would put
 * the whole transcript on the wire hundreds of times per turn. Deltas are
 * accumulated and published at most every [STREAM_FLUSH_MS], plus once when the
 * turn ends, so the final text is always exact.
 *
 * The `claude` binary is resolved the way the plugin resolves it, including the
 * `CLAUDE_CLI_PATH` override — which is also the seam a test uses to point this
 * holder at a stub and never make a real model call.
 */
class AtlasStateHolder : PluginStateHolder<AtlasState, AtlasIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(AtlasStateHolder::class.java)

    private val projectDataProvider: ProjectDataProvider?
    private val pickDirectory: ((callback: (String?) -> Unit) -> Unit)?

    /** Resumed across turns; cleared when the agent or the project changes. */
    private var sessionId: String? = null
    private var idCounter = 0L
    private var turnJob: Job? = null

    constructor(scope: CoroutineScope) : this(scope, null, null, null)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : this(
        scope,
        projectPath = context.projectPath,
        projectDataProvider = context.projectDataProvider,
        pickDirectory = { callback -> context.directoryPickerProvider.pickDirectory(callback) },
    ) {
        logger.info(
            "AtlasStateHolder started (projectPath={}, page context unavailable out-of-process)",
            context.projectPath,
        )
    }

    private constructor(
        scope: CoroutineScope,
        projectPath: String?,
        projectDataProvider: ProjectDataProvider?,
        pickDirectory: ((callback: (String?) -> Unit) -> Unit)?,
    ) : super(AtlasState(), scope) {
        this.projectDataProvider = projectDataProvider
        this.pickDirectory = pickDirectory
        // Publish an initial versioned state before any disk scan. A holder that
        // never calls updateState stays at version 0 and never renders at all
        // (the bookmarks failure mode).
        updateState {
            copy(
                workingDir = projectPath,
                agents = discoverAgents(projectPath),
                recentProjects = readRecentProjects(),
                backendAvailable = Cli.resolve() != null,
                pageContextReason = PAGE_CONTEXT_REASON,
                ready = true,
            )
        }
    }

    override fun onIntent(intent: AtlasIntent) {
        when (intent) {
            is AtlasIntent.Send -> send(intent.text)

            is AtlasIntent.Cancel -> {
                turnJob?.cancel()
                turnJob = null
                updateState {
                    copy(
                        sending = false,
                        messages = messages.map { if (it.streaming) it.copy(streaming = false) else it },
                    )
                }
            }

            is AtlasIntent.SelectAgent -> {
                if (currentState().selectedAgent == intent.name) return
                if (currentState().agents.none { it.name == intent.name }) {
                    logger.debug("Ignoring unknown agent {}", intent.name)
                    return
                }
                sessionId = null // a newly loaded agent starts a new session
                updateState { copy(selectedAgent = intent.name) }
            }

            is AtlasIntent.RefreshAgents -> {
                val agents = discoverAgents(currentState().workingDir)
                updateState { copy(agents = agents) }
            }

            is AtlasIntent.SetWorkingDir -> setWorkingDir(intent.path)

            is AtlasIntent.PickDirectory -> {
                val picker = pickDirectory
                if (picker == null) {
                    logger.debug("No directory picker on this context")
                    return
                }
                picker { path -> if (path != null) setWorkingDir(path) }
            }

            is AtlasIntent.SetPermissionMode -> updateState { copy(permissionMode = intent.mode) }

            is AtlasIntent.ClearConversation -> {
                turnJob?.cancel()
                turnJob = null
                sessionId = null
                updateState { copy(messages = emptyList(), sending = false) }
            }
        }
    }

    private fun setWorkingDir(path: String?) {
        if (path == currentState().workingDir) return
        sessionId = null
        val agents = discoverAgents(path)
        updateState {
            copy(
                workingDir = path,
                // The default agent is the only one guaranteed to exist in a new project.
                selectedAgent = "",
                agents = agents,
                recentProjects = readRecentProjects(),
            )
        }
    }

    // region one turn

    private fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || currentState().sending) return
        val exe = Cli.resolve()

        val user = AtlasMessage(id = nextId(), role = AtlasRole.USER, text = text)
        val assistant = AtlasMessage(id = nextId(), role = AtlasRole.ASSISTANT, text = "", streaming = true)
        val assistantId = assistant.id
        updateState { copy(messages = messages + user + assistant, sending = true) }

        if (exe == null) {
            failTurn(
                assistantId,
                "Claude Code CLI not found. Install it and ensure `claude` is on your PATH.",
            )
            return
        }

        val state = currentState()
        val argv = buildList {
            add("-p")
            addAll(listOf("--output-format", "stream-json"))
            addAll(listOf("--input-format", "text"))
            add("--verbose")
            add("--include-partial-messages")
            sessionId?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--resume", it)) }
            state.selectedAgent.takeIf { it.isNotBlank() }?.let { addAll(listOf("--agent", it)) }
            state.permissionMode.cliValue.takeIf { it.isNotBlank() }
                ?.let { addAll(listOf("--permission-mode", it)) }
            // No --append-system-prompt: there is no page context out-of-process.
        }
        val cwd = state.workingDir?.takeIf { it.isNotBlank() && File(it).isDirectory }?.let(::File)

        turnJob?.cancel()
        turnJob = scope.launch {
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            var lastFlush = 0L
            var failure: String? = null
            var sawTerminal = false

            fun flush(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - lastFlush < STREAM_FLUSH_MS) return
                lastFlush = now
                val body = answer.toString()
                val thinking = reasoning.toString()
                updateState {
                    copy(
                        messages = messages.map {
                            if (it.id == assistantId) it.copy(text = body, thinking = thinking) else it
                        },
                    )
                }
            }

            val exit = ProcessRunner.stream(
                exe = exe,
                args = argv,
                workingDir = cwd,
                // stdin cannot be written by `stream`, so the prompt goes through
                // the env-free path below instead. See sendPrompt.
                onLine = { line ->
                    when (val event = parseStreamLine(line)) {
                        null -> Unit
                        is StreamEvent.Init -> sessionId = event.sessionId.ifBlank { sessionId }
                        is StreamEvent.Text -> {
                            answer.append(event.text)
                            flush(force = false)
                        }

                        is StreamEvent.Thinking -> {
                            reasoning.append(event.text)
                            flush(force = false)
                        }

                        is StreamEvent.Done -> {
                            sessionId = event.sessionId.ifBlank { sessionId }
                            sawTerminal = true
                        }

                        is StreamEvent.Failed -> {
                            failure = event.message
                            sawTerminal = true
                        }
                    }
                },
                stdin = text,
            )

            flush(force = true)
            val message = failure ?: when {
                sawTerminal -> null
                exit == 0 -> null
                else -> "Claude exited with code $exit"
            }
            updateState {
                copy(
                    sending = false,
                    messages = messages.map {
                        if (it.id == assistantId) it.copy(streaming = false, error = message) else it
                    },
                )
            }
            turnJob = null
        }
    }

    private fun failTurn(assistantId: Long, message: String) {
        updateState {
            copy(
                sending = false,
                messages = messages.map {
                    if (it.id == assistantId) it.copy(streaming = false, error = message) else it
                },
            )
        }
    }

    private fun nextId(): Long = idCounter++

    // endregion

    // region agent discovery

    /**
     * Claude Code subagents: markdown files under `~/.claude/agents` (user scope)
     * and `<project>/.claude/agents` (project scope), each with YAML frontmatter.
     * Project scope wins on a name clash, and the implicit default comes first —
     * the same order the plugin's `AgentDiscovery` produces.
     */
    private fun discoverAgents(projectDir: String?): List<AtlasAgent> {
        val byName = LinkedHashMap<String, AtlasAgent>()
        val dirs = buildList {
            projectDir?.takeIf { it.isNotBlank() }
                ?.let { add(File(it, ".claude/agents") to AtlasAgentSource.PROJECT) }
            add(File(System.getProperty("user.home").orEmpty(), ".claude/agents") to AtlasAgentSource.USER)
        }
        for ((folder, source) in dirs) {
            if (!folder.isDirectory) continue
            val files = folder.listFiles { file -> file.isFile && file.extension == "md" } ?: continue
            for (file in files) {
                val front = readFrontmatter(file) ?: continue
                val name = front["name"]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                byName.putIfAbsent(
                    name,
                    AtlasAgent(name = name, description = front["description"].orEmpty(), source = source),
                )
            }
        }
        return listOf(DEFAULT_AGENT) + byName.values.sortedBy { it.name.lowercase() }
    }

    private fun readRecentProjects(): List<AtlasProject> =
        projectDataProvider?.recentProjects?.value.orEmpty().map { project: ProjectData ->
            AtlasProject(name = project.name, path = project.path, lastOpened = project.lastOpened)
        }

    // endregion

    internal companion object {
        /** How often streamed deltas may republish the whole state envelope. */
        internal const val STREAM_FLUSH_MS = 120L

        internal const val PAGE_CONTEXT_REASON =
            "Page context is unavailable out-of-process: the child's ActiveTabsProvider proxy " +
                "returns no BrowserIntegration, and the IPC contract has no ExecuteJavaScript call."

        internal val DEFAULT_AGENT = AtlasAgent(
            name = "",
            description = "Default Claude (no specific agent)",
            source = AtlasAgentSource.BUILT_IN,
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parse the `key: value` pairs from a leading `---` frontmatter block. */
        internal fun readFrontmatter(file: File): Map<String, String>? {
            val lines = runCatching { file.readLines() }.getOrNull() ?: return null
            // A file with no frontmatter is still a valid agent file.
            if (lines.firstOrNull()?.trim() != "---") return emptyMap()
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end < 0) return emptyMap()
            val map = HashMap<String, String>()
            for (line in lines.subList(1, end + 1)) {
                val index = line.indexOf(':')
                if (index <= 0) continue
                map[line.substring(0, index).trim()] =
                    line.substring(index + 1).trim().trim('"', '\'')
            }
            return map
        }

        /**
         * One line of `claude --output-format stream-json`, or null for lines we
         * ignore. Shapes mirror the plugin's `StreamJsonParser`, verified there
         * against claude 2.1.195.
         */
        internal fun parseStreamLine(line: String): StreamEvent? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null

            return when (obj.str("type")) {
                "system" -> if (obj.str("subtype") == "init") StreamEvent.Init(obj.str("session_id")) else null
                "stream_event" -> parseDelta(obj)
                "result" -> parseResult(obj)
                else -> null
            }
        }

        private fun parseDelta(obj: JsonObject): StreamEvent? {
            val event = obj["event"]?.jsonObject ?: return null
            if (event.str("type") != "content_block_delta") return null
            val delta = event["delta"]?.jsonObject ?: return null
            return when (delta.str("type")) {
                "text_delta" -> delta.str("text").takeIf { it.isNotEmpty() }?.let { StreamEvent.Text(it) }
                "thinking_delta" ->
                    delta.str("thinking").takeIf { it.isNotEmpty() }?.let { StreamEvent.Thinking(it) }

                else -> null
            }
        }

        private fun parseResult(obj: JsonObject): StreamEvent {
            val isError = obj["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj.str("subtype").startsWith("error")
            return if (isError) {
                StreamEvent.Failed(obj.str("result").ifBlank { "Claude returned an error" })
            } else {
                StreamEvent.Done(obj.str("session_id"))
            }
        }

        private fun JsonObject.str(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** One decoded line of the CLI's stream-json output. */
    internal sealed interface StreamEvent {
        data class Init(val sessionId: String) : StreamEvent
        data class Text(val text: String) : StreamEvent
        data class Thinking(val text: String) : StreamEvent
        data class Done(val sessionId: String) : StreamEvent
        data class Failed(val message: String) : StreamEvent
    }

    /**
     * The `claude` binary, resolved the way the plugin's `ClaudeCodeCliBackend`
     * resolves it: the `CLAUDE_CLI_PATH` override first, then the usual install
     * locations, because a GUI-launched process does not inherit the shell PATH.
     *
     * The plugin's final fallback — a `/bin/sh -lc "command -v claude"` login
     * shell — is deliberately not reproduced. A login shell in a spawned plugin
     * child sources the operator's whole profile for a path lookup, and
     * `CLAUDE_CLI_PATH` already covers the case it was there for. It is also the
     * seam a test uses to point this holder at a stub and never reach a model.
     */
    private object Cli {
        fun resolve(): File? {
            val home = System.getProperty("user.home").orEmpty()
            val candidates = listOfNotNull(
                System.getenv("CLAUDE_CLI_PATH"),
                "$home/.local/bin/claude",
                "$home/.claude/local/claude",
                "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude",
                "/usr/bin/claude",
            )
            return candidates
                .map(::File)
                .firstOrNull { runCatching { it.canExecute() }.getOrDefault(false) }
        }
    }
}

/**
 * Decode one wire intent for [AtlasStateHolder].
 *
 * Every intent here is single-field or nullary, so all of them take the payload
 * as a bare string — the convention the other holders' arms use. An unknown
 * [intentType] decodes to null so `PluginStateSyncService` drops it.
 *
 * [AtlasIntent.SetWorkingDir] treats a blank payload as "no project", which is a
 * meaningful value (run in the host's default directory), so unlike the other
 * single-field intents it does not reject an empty payload.
 */
internal fun decodeAtlasIntent(intentType: String, payload: String): AtlasIntent? = when (intentType) {
    "Send" -> payload.ifBlank { null }?.let { AtlasIntent.Send(it) }
    "Cancel" -> AtlasIntent.Cancel
    "SelectAgent" -> AtlasIntent.SelectAgent(payload)
    "RefreshAgents" -> AtlasIntent.RefreshAgents
    "SetWorkingDir" -> AtlasIntent.SetWorkingDir(payload.ifBlank { null })
    "PickDirectory" -> AtlasIntent.PickDirectory
    "SetPermissionMode" -> runCatching { AtlasPermissionMode.valueOf(payload.trim().uppercase()) }
        .getOrNull()?.let { AtlasIntent.SetPermissionMode(it) }

    "ClearConversation" -> AtlasIntent.ClearConversation
    else -> null
}
