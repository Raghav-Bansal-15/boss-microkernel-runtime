package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

// region State

/**
 * The Docker sidebar surface, mirrored for a host-side renderer.
 *
 * This is the **browse** surface — what the daemon currently holds and what the
 * open project contains — not the whole plugin. See [DockerStateHolder] for what
 * a child JVM cannot do. [mutationsAvailable] and [terminalAvailable] are always
 * `false` and exist so the host renders honest affordances rather than Stop /
 * Remove / Build buttons that would either do nothing or act without the
 * confirmation the in-process panel enforces.
 */
@Serializable
data class DockerState(
    val daemon: DockerDaemonStatus = DockerDaemonStatus.UNKNOWN,
    /** `docker version --format {{.Server.Version}}` when the daemon answers. */
    val serverVersion: String = "",
    /** Failure text when [daemon] is [DockerDaemonStatus.ERROR]. */
    val daemonError: String = "",
    val containers: List<DockerContainer> = emptyList(),
    val images: List<DockerImage> = emptyList(),
    val volumes: List<DockerVolume> = emptyList(),
    val networks: List<DockerNetwork> = emptyList(),
    val composeProjects: List<DockerComposeProject> = emptyList(),
    val projectArtifacts: List<DockerArtifact> = emptyList(),
    /** Which sidebar groups are open. Mirrors the plugin's default of Project + Containers. */
    val expandedSections: List<DockerSection> = listOf(DockerSection.PROJECT, DockerSection.CONTAINERS),
    val query: String = "",
    val selectedContainerId: String? = null,
    /** What [detailText] holds, so a renderer knows which body to draw. */
    val detail: DockerDetailKind = DockerDetailKind.NONE,
    val detailText: String = "",
    /** True while a refresh or a detail fetch is in flight. */
    val busy: Boolean = false,
    /**
     * True once the holder has published its first state. An empty daemon is a
     * legitimate result, so this is what distinguishes "initialised, nothing
     * running" from "no state has ever arrived".
     */
    val ready: Boolean = false,
    /**
     * Always false out-of-process — this holder issues no start/stop/rm/rmi.
     * See [DockerStateHolder].
     */
    val mutationsAvailable: Boolean = false,
    /** Always false out-of-process — `openTab` is an inherited no-op on the IPC proxy. */
    val terminalAvailable: Boolean = false,
    /** Always false out-of-process — the host's plugin storage is not on the wire. */
    val persistenceAvailable: Boolean = false,
)

/**
 * Where the daemon stands. Mirrors the plugin's `DaemonState` sum type, flattened
 * to an enum plus [DockerState.serverVersion] / [DockerState.daemonError] because
 * a polymorphic sealed hierarchy needs a serializers module the generic
 * `PluginStateSyncService` encoder does not install.
 */
@Serializable
enum class DockerDaemonStatus {
    /** Not probed yet. */
    UNKNOWN,

    /** No `docker` binary on PATH or in the usual install dirs. */
    CLI_MISSING,

    /** CLI present, daemon not answering. */
    STOPPED,

    /** Daemon answering. */
    RUNNING,

    /** CLI present but the probe failed for another reason. */
    ERROR,
}

/** The sidebar groups, in display order. Mirrors the plugin's `PanelSection`. */
@Serializable
enum class DockerSection { PROJECT, CONTAINERS, IMAGES, VOLUMES, NETWORKS }

/** Which detail body [DockerState.detailText] is carrying. */
@Serializable
enum class DockerDetailKind { NONE, LOGS, INSPECT }

/** One published port mapping. Mirrors the plugin's `PortBinding`. */
@Serializable
data class DockerPort(
    val hostIp: String,
    val hostPort: Int,
    val containerPort: Int,
    val protocol: String,
)

/** A container as reported by `docker ps -a`. */
@Serializable
data class DockerContainer(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: List<DockerPort> = emptyList(),
    /** Compose project label, when the container belongs to one. */
    val composeProject: String = "",
    val createdAt: String = "",
    /**
     * Whether [state] is `running`. A **constructor property**, not a computed
     * getter: kotlinx serializes only constructor properties, so a getter would
     * be absent from the synced payload and every host renderer would have to
     * re-derive it — which is the duplication these mirrored rows exist to avoid.
     */
    val isRunning: Boolean = false,
)

@Serializable
data class DockerImage(
    val id: String,
    val repository: String,
    val tag: String,
    val size: String,
    val createdSince: String,
    /** `repo:tag`, or the short id for a dangling image — computed here so a renderer need not. */
    val reference: String,
    val dangling: Boolean,
)

@Serializable
data class DockerVolume(val name: String, val driver: String, val mountpoint: String)

@Serializable
data class DockerNetwork(val id: String, val name: String, val driver: String, val scope: String)

/** A compose project as reported by `docker compose ls --all`. */
@Serializable
data class DockerComposeProject(
    val name: String,
    val status: String,
    val configFiles: String,
    val running: Boolean,
)

/** A Dockerfile or compose file found in the open project. */
@Serializable
data class DockerArtifact(
    val path: String,
    val relativePath: String,
    val kind: DockerArtifactKind,
)

@Serializable
enum class DockerArtifactKind { DOCKERFILE, COMPOSE }

// endregion

// region Intent

sealed class DockerIntent {
    /** Re-probe the daemon and re-read every list. */
    object Refresh : DockerIntent()

    /** Re-scan [DockerState.projectArtifacts] from the child's `BOSS_PROJECT_PATH`. */
    object RescanProject : DockerIntent()

    data class SetQuery(val query: String) : DockerIntent()
    data class ToggleSection(val section: DockerSection) : DockerIntent()
    data class SelectContainer(val containerId: String) : DockerIntent()
    object ClearSelection : DockerIntent()

    /** Fetch the last [tail] lines of a container's log into [DockerState.detailText]. */
    data class ShowLogs(val containerId: String, val tail: Int = DEFAULT_TAIL) : DockerIntent()

    /** Fetch `docker inspect` for a container into [DockerState.detailText]. */
    data class ShowInspect(val containerId: String) : DockerIntent()

    object ClearDetail : DockerIntent()

    companion object {
        const val DEFAULT_TAIL = 500
    }
}

// endregion

/**
 * StateHolder for the Docker plugin — the **sidebar browse** surface.
 *
 * The in-process plugin is a browser *and* a control panel *and* a build
 * launcher: it stops and removes containers, images, volumes and networks; it
 * runs `docker build` / `docker run` / `docker compose up` in a BossTerm tab; it
 * opens a per-container tab with a streaming log view and an embedded browser
 * preview of whatever the service serves; and it remembers per-artifact port
 * choices in plugin storage. What survives out-of-process, and what does not:
 *
 * - **Reading the daemon works, fully.** Shelling out is the one thing a child
 *   JVM does exactly as well as the host, and this holder issues the same
 *   read-only argv the plugin's `DockerEngine` does — `docker version`,
 *   `ps -a --no-trunc --format {{json .}}`, `images`, `volume ls`, `network ls`
 *   and `compose ls --all --format json` — parsing the same `{{json .}}` fields.
 * - **Mutations are deliberately not offered.** `start` / `stop` / `restart` /
 *   `rm` / `rmi` / `volume rm` / `network rm` are all reachable from a child JVM,
 *   but every one of them is gated in-process behind a `ConfirmRequest` the
 *   panel renders itself (because `genericDialogProvider` is nullable) and, for
 *   the MCP surface, behind the `docker.manage` permission. Neither gate has a
 *   shape on the state-sync wire: an intent arrives as a type string and a byte
 *   payload with no user-confirmation and no permission context. Shipping
 *   `Remove` as a bare intent would be strictly more dangerous than the
 *   in-process button it mimics, so [DockerState.mutationsAvailable] is always
 *   false and there is no mutating intent to send.
 * - **Builds and `compose up` are unavailable.** They run as shell strings in a
 *   terminal tab opened through `PluginContext.splitViewOperations.openTab`,
 *   which `SplitViewOperationsProxy` does not override — the call reaches
 *   `SplitViewOperations`' empty default body and silently does nothing. A child
 *   JVM could spawn `docker build` itself, but then its output goes nowhere a
 *   user can see; the plugin's whole design is that a build is *watchable*.
 *   [DockerState.terminalAvailable] is always false.
 * - **The service tab's preview is unavailable.** It embeds a real browser via
 *   `PluginContext.browserService`, declared `Nothing? = null` in
 *   `RemotePluginContext`. Logs are still reachable, as a bounded on-demand tail
 *   ([DockerIntent.ShowLogs]) rather than the plugin's `docker logs -f` stream:
 *   the state envelope is re-sent in full on every change, so a follow stream
 *   would put the whole scrollback on the wire per line.
 * - **Persistence is unavailable.** `autoOpenServiceTab` and the remembered
 *   per-artifact ports live in `pluginStorageFactory`, also `Nothing? = null`,
 *   and the IPC contract has no plugin-storage service at all.
 *   [DockerState.persistenceAvailable] is always false.
 *
 * The daemon is polled on [DockerIntent.Refresh] rather than followed with
 * `docker events`: the plugin's event stream exists to keep a live panel fresh,
 * and a permanently attached `docker events` child in every spawned holder is a
 * process the host cannot see. A host renderer that wants live rows sends
 * `Refresh`.
 */
class DockerStateHolder : PluginStateHolder<DockerState, DockerIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(DockerStateHolder::class.java)

    /** Project root for the artifact scan — `BOSS_PROJECT_PATH`, when the host set it. */
    private val projectPath: String?

    /** The in-flight refresh, so a burst of intents does not stack `docker ps` children. */
    private var refreshJob: Job? = null
    private var detailJob: Job? = null

    constructor(scope: CoroutineScope) : this(scope, projectPath = null)

    constructor(scope: CoroutineScope, context: RemotePluginContext) :
        this(scope, projectPath = context.projectPath) {
        logger.info(
            "DockerStateHolder started (projectPath={}, mutations/terminal/preview unavailable OOP)",
            context.projectPath,
        )
    }

    /**
     * @param autoStart whether construction may reach the docker CLI.
     *
     * True in production: the panel is expected to be populated by the time a user looks at it.
     * Tests pass false, because construction is itself a side effect - every `DockerStateHolder(scope)`
     * otherwise launches `docker version`, `ps -a`, `images`, `volume ls` and `network ls` against whatever daemon or cluster the machine happens to be
     * pointed at. That is a race today rather than a certainty (the launched coroutine usually
     * loses to the test's `tearDown`), which makes it worse, not better: it is the kind of thing
     * that stays quiet until CI is slow one morning. `build.yml` runs `./gradlew build` on every
     * pull request, so the machine in question can be a runner.
     *
     * The project scan is deliberately NOT gated - it only reads the filesystem, and several
     * tests depend on it having run.
     */
    internal constructor(
        scope: CoroutineScope,
        projectPath: String?,
        autoStart: Boolean = true,
    ) : super(DockerState(), scope) {
        this.projectPath = projectPath
        // Publish an initial versioned state before any docker call completes. A
        // holder that never calls updateState stays at version 0 and never
        // renders at all (the bookmarks failure mode) — and the first probe can
        // take seconds against a daemon that is booting.
        updateState { copy(ready = true) }
        onIntent(DockerIntent.RescanProject)
        if (autoStart) onIntent(DockerIntent.Refresh)
    }

    override fun onIntent(intent: DockerIntent) {
        when (intent) {
            is DockerIntent.Refresh -> refresh()

            is DockerIntent.RescanProject -> {
                val root = projectPath?.let(::File)
                val artifacts = if (root == null || !root.isDirectory) {
                    emptyList()
                } else {
                    scanArtifacts(root)
                }
                updateState { copy(projectArtifacts = artifacts) }
            }

            is DockerIntent.SetQuery -> updateState { copy(query = intent.query) }

            is DockerIntent.ToggleSection -> updateState {
                copy(
                    expandedSections = if (intent.section in expandedSections) {
                        expandedSections - intent.section
                    } else {
                        expandedSections + intent.section
                    },
                )
            }

            is DockerIntent.SelectContainer -> updateState {
                if (containers.none { it.id == intent.containerId }) {
                    this
                } else {
                    copy(selectedContainerId = intent.containerId)
                }
            }

            is DockerIntent.ClearSelection -> updateState {
                copy(selectedContainerId = null, detail = DockerDetailKind.NONE, detailText = "")
            }

            is DockerIntent.ShowLogs -> fetchDetail(
                containerId = intent.containerId,
                kind = DockerDetailKind.LOGS,
                argv = listOf("logs", "--tail", intent.tail.coerceIn(1, MAX_TAIL).toString(), intent.containerId),
            )

            is DockerIntent.ShowInspect -> fetchDetail(
                containerId = intent.containerId,
                kind = DockerDetailKind.INSPECT,
                argv = listOf("inspect", intent.containerId),
            )

            is DockerIntent.ClearDetail -> updateState {
                copy(detail = DockerDetailKind.NONE, detailText = "")
            }
        }
    }

    // region refresh

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            updateState { copy(busy = true) }
            try {
                if (!probe()) return@launch
                readLists()
            } finally {
                updateState { copy(busy = false) }
            }
        }
    }

    /**
     * Cheap liveness probe; also the source of the version we publish. Returns
     * true when the daemon answered, and clears every list when it did not —
     * stale rows next to live-looking detail are worse than no rows, which is
     * the same call the in-process engine makes.
     */
    private suspend fun probe(): Boolean {
        if (Cli.resolve() == null) {
            updateState { emptyExcept(DockerDaemonStatus.CLI_MISSING) }
            return false
        }
        val result = Cli.exec(listOf("version", "--format", "{{.Server.Version}}"), PROBE_TIMEOUT_MS)
        if (result.ok) {
            updateState {
                copy(
                    daemon = DockerDaemonStatus.RUNNING,
                    serverVersion = result.stdout.trim().ifBlank { "unknown" },
                    daemonError = "",
                )
            }
            return true
        }
        val status = when {
            result.daemonUnreachable -> DockerDaemonStatus.STOPPED
            result.exitCode == Cli.EXIT_CLI_MISSING -> DockerDaemonStatus.CLI_MISSING
            // A timeout while Docker Desktop boots looks like "stopped", not an error.
            result.exitCode == Cli.EXIT_TIMEOUT -> DockerDaemonStatus.STOPPED
            else -> DockerDaemonStatus.ERROR
        }
        val message = if (status == DockerDaemonStatus.ERROR) result.message.take(MAX_ERROR_CHARS) else ""
        updateState { emptyExcept(status).copy(daemonError = message) }
        return false
    }

    private suspend fun readLists() {
        Cli.exec(listOf("ps", "-a", "--no-trunc", "--format", "{{json .}}")).let { result ->
            if (result.ok) {
                val containers = parseContainers(result.stdout)
                updateState {
                    copy(
                        containers = containers,
                        // Drop a selection whose container is gone rather than
                        // leaving a renderer pointing at a row that is not there.
                        selectedContainerId = selectedContainerId?.takeIf { id -> containers.any { it.id == id } },
                    )
                }
            } else if (result.daemonUnreachable) {
                updateState { emptyExcept(DockerDaemonStatus.STOPPED) }
                return
            }
        }
        Cli.exec(listOf("images", "--format", "{{json .}}")).let { result ->
            if (result.ok) {
                updateState { copy(images = parseImages(result.stdout)) }
            }
        }
        Cli.exec(listOf("volume", "ls", "--format", "{{json .}}")).let { result ->
            if (result.ok) {
                updateState { copy(volumes = parseVolumes(result.stdout)) }
            }
        }
        Cli.exec(listOf("network", "ls", "--format", "{{json .}}")).let { result ->
            if (result.ok) {
                updateState { copy(networks = parseNetworks(result.stdout)) }
            }
        }
        readComposeProjects()
    }

    /** `docker compose ls` emits a JSON *array*, unlike the NDJSON of `ps` / `images`. */
    private suspend fun readComposeProjects() {
        val result = Cli.exec(listOf("compose", "ls", "--all", "--format", "json"))
        if (!result.ok) return
        val projects = parseComposeProjects(result.stdout) ?: return
        updateState { copy(composeProjects = projects) }
    }

    private fun fetchDetail(containerId: String, kind: DockerDetailKind, argv: List<String>) {
        if (currentState().containers.none { it.id == containerId }) {
            logger.debug("Ignoring {} for unknown container {}", kind, containerId)
            return
        }
        detailJob?.cancel()
        detailJob = scope.launch {
            updateState { copy(selectedContainerId = containerId, busy = true) }
            val result = Cli.exec(argv, DETAIL_TIMEOUT_MS)
            val text = if (result.ok) result.stdout else result.message
            updateState {
                copy(
                    detail = kind,
                    detailText = text.takeLast(MAX_DETAIL_CHARS),
                    busy = false,
                )
            }
        }
    }

    /** Everything gone but the daemon verdict — the in-process engine's `clearAll`. */
    private fun DockerState.emptyExcept(status: DockerDaemonStatus): DockerState = copy(
        daemon = status,
        serverVersion = "",
        daemonError = "",
        containers = emptyList(),
        images = emptyList(),
        volumes = emptyList(),
        networks = emptyList(),
        composeProjects = emptyList(),
        selectedContainerId = null,
        detail = DockerDetailKind.NONE,
        detailText = "",
    )

    // endregion

    // region project scan

    private fun scanArtifacts(root: File): List<DockerArtifact> = buildList {
        root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir == root || (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile }
            .forEach { file ->
                val kind = classify(file.name) ?: return@forEach
                add(
                    DockerArtifact(
                        path = file.absolutePath,
                        relativePath = file.relativeToOrNull(root)?.path ?: file.name,
                        kind = kind,
                    ),
                )
                if (size >= MAX_ARTIFACTS) return@buildList
            }
    }.sortedWith(compareBy({ it.kind != DockerArtifactKind.COMPOSE }, { it.relativePath }))

    private fun classify(fileName: String): DockerArtifactKind? {
        val lower = fileName.lowercase()
        return when {
            lower == "dockerfile" || lower.startsWith("dockerfile.") -> DockerArtifactKind.DOCKERFILE
            lower.endsWith(".dockerfile") -> DockerArtifactKind.DOCKERFILE
            lower in COMPOSE_NAMES -> DockerArtifactKind.COMPOSE
            else -> null
        }
    }

    // endregion

    // region docker wire shape

    /**
     * The `{{json .}}` column names `docker ps -a` emits, re-declared here
     * because the runtime does not depend on the plugin's jar. Field names and
     * defaults match the plugin's `RawContainer` exactly.
     */
    @Serializable
    private data class RawContainer(
        @SerialName("ID") val id: String = "",
        @SerialName("Names") val names: String = "",
        @SerialName("Image") val image: String = "",
        @SerialName("State") val state: String = "",
        @SerialName("Status") val status: String = "",
        @SerialName("Ports") val ports: String = "",
        @SerialName("Labels") val labels: String = "",
        @SerialName("CreatedAt") val createdAt: String = "",
    ) {
        fun toState() = DockerContainer(
            id = id,
            // `docker ps` joins multiple names with commas; the first is the real one.
            name = names.split(",").firstOrNull()?.trim().orEmpty().ifBlank { id.take(SHORT_ID) },
            image = image,
            state = state,
            status = status,
            ports = parsePorts(ports),
            composeProject = parseLabels(labels)["com.docker.compose.project"].orEmpty(),
            createdAt = createdAt,
            isRunning = state.equals("running", ignoreCase = true),
        )
    }

    @Serializable
    private data class RawImage(
        @SerialName("ID") val id: String = "",
        @SerialName("Repository") val repository: String = "",
        @SerialName("Tag") val tag: String = "",
        @SerialName("Size") val size: String = "",
        @SerialName("CreatedSince") val createdSince: String = "",
    ) {
        fun toState(): DockerImage {
            val dangling = repository == NONE || tag == NONE
            val reference = when {
                repository.isBlank() || repository == NONE -> id.removePrefix("sha256:").take(SHORT_ID)
                tag.isBlank() || tag == NONE -> repository
                else -> "$repository:$tag"
            }
            return DockerImage(id, repository, tag, size, createdSince, reference, dangling)
        }
    }

    @Serializable
    private data class RawVolume(
        @SerialName("Name") val name: String = "",
        @SerialName("Driver") val driver: String = "",
        @SerialName("Mountpoint") val mountpoint: String = "",
    )

    @Serializable
    private data class RawNetwork(
        @SerialName("ID") val id: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("Driver") val driver: String = "",
        @SerialName("Scope") val scope: String = "",
    )

    @Serializable
    private data class RawCompose(
        @SerialName("Name") val name: String = "",
        @SerialName("Status") val status: String = "",
        @SerialName("ConfigFiles") val configFiles: String = "",
    )

    // endregion

    internal companion object {
        private const val PROBE_TIMEOUT_MS = 8_000L
        private const val DETAIL_TIMEOUT_MS = 20_000L
        private const val SCAN_DEPTH = 4
        private const val MAX_ARTIFACTS = 60
        private const val MAX_TAIL = 5_000
        private const val MAX_DETAIL_CHARS = 200_000
        private const val MAX_ERROR_CHARS = 300
        private const val SHORT_ID = 12
        private const val NONE = "<none>"

        private val SKIP_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor",
            "venv", "__pycache__", "Pods", "DerivedData", "tmp",
        )
        private val COMPOSE_NAMES = setOf(
            "docker-compose.yml", "docker-compose.yaml",
            "compose.yml", "compose.yaml",
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * Decode NDJSON, skipping lines docker did not mean as data. `docker`
         * writes warnings and hints to the same stream, and a decoder that tried
         * them would return a list with holes in it.
         */
        private fun <T> parseNdJson(
            stdout: String,
            serializer: kotlinx.serialization.KSerializer<T>,
        ): List<T> = stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line -> runCatching { json.decodeFromString(serializer, line) }.getOrNull() }
            .toList()

        /** `docker ps -a --format {{json .}}`, running containers first then by name. */
        internal fun parseContainers(stdout: String): List<DockerContainer> =
            parseNdJson(stdout, RawContainer.serializer())
                .map { it.toState() }
                .sortedWith(compareByDescending<DockerContainer> { it.isRunning }.thenBy { it.name })

        internal fun parseImages(stdout: String): List<DockerImage> =
            parseNdJson(stdout, RawImage.serializer()).map { it.toState() }.sortedBy { it.reference }

        internal fun parseVolumes(stdout: String): List<DockerVolume> =
            parseNdJson(stdout, RawVolume.serializer())
                .map { DockerVolume(it.name, it.driver, it.mountpoint) }
                .sortedBy { it.name }

        internal fun parseNetworks(stdout: String): List<DockerNetwork> =
            parseNdJson(stdout, RawNetwork.serializer())
                .map { DockerNetwork(it.id, it.name, it.driver, it.scope) }
                .sortedBy { it.name }

        /**
         * `docker compose ls --all --format json` emits a JSON *array*, unlike the
         * NDJSON of `ps` / `images`. Null means the payload was not an array at
         * all, which is left alone rather than published as "no projects".
         */
        internal fun parseComposeProjects(stdout: String): List<DockerComposeProject>? {
            val text = stdout.trim()
            if (!text.startsWith("[")) return null
            val raw = runCatching {
                json.decodeFromString(ListSerializer(RawCompose.serializer()), text)
            }.getOrNull() ?: return null
            return raw.map {
                DockerComposeProject(
                    name = it.name,
                    status = it.status,
                    configFiles = it.configFiles,
                    running = it.status.startsWith("running", ignoreCase = true),
                )
            }
        }

        /**
         * Parse the `Ports` column, e.g. `0.0.0.0:8080->80/tcp, :::8080->80/tcp`
         * or `80/tcp` (published nothing). Docker lists the IPv4 and IPv6
         * bindings of one publish separately; they are deduped so a renderer
         * shows one row per actual mapping, preferring the IPv4 record.
         */
        internal fun parsePorts(raw: String): List<DockerPort> {
            if (raw.isBlank()) return emptyList()
            val seen = LinkedHashMap<Pair<Int, Int>, DockerPort>()
            for (piece in raw.split(",")) {
                val part = piece.trim()
                if (!part.contains("->")) continue // not published to the host
                val hostSide = part.substringBefore("->").trim()
                val containerSide = part.substringAfter("->").trim()

                val protocol = containerSide.substringAfter('/', "tcp").ifBlank { "tcp" }
                val containerPort = containerSide.substringBefore('/').toIntOrNull() ?: continue
                val hostPort = hostSide.substringAfterLast(':').toIntOrNull() ?: continue
                val hostIp = hostSide.substringBeforeLast(':').ifBlank { "::" }

                val key = hostPort to containerPort
                val existing = seen[key]
                if (existing == null || (existing.hostIp.contains(':') && !hostIp.contains(':'))) {
                    seen[key] = DockerPort(hostIp, hostPort, containerPort, protocol)
                }
            }
            return seen.values.sortedBy { it.hostPort }
        }

        /** Parse the `Labels` column: `k=v,k2=v2`. */
        internal fun parseLabels(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split(",").mapNotNull { entry ->
                val trimmed = entry.trim()
                if (!trimmed.contains('=')) return@mapNotNull null
                trimmed.substringBefore('=') to trimmed.substringAfter('=')
            }.toMap()
        }
    }

    /**
     * The `docker` CLI, resolved and invoked the way the plugin's `DockerCli`
     * does: absolute binary plus a widened child PATH (a bare command name
     * resolves against the *parent* PATH, which is nearly empty when a packaged
     * host launches from Finder), and argv lists rather than shell strings so
     * daemon-supplied names have no shell to inject into.
     *
     * PATH is searched before the fallback install dirs, so a test that puts a
     * stub `docker` first on the child's PATH is guaranteed to get the stub and
     * never the operator's real daemon.
     */
    private object Cli {
        const val EXIT_CLI_MISSING = ProcessRunner.EXIT_BINARY_MISSING
        const val EXIT_TIMEOUT = ProcessRunner.EXIT_TIMEOUT

        private val DAEMON_DOWN_MARKERS = listOf(
            "Cannot connect to the Docker daemon",
            "Is the docker daemon running",
            "error during connect",
            "The system cannot find the file specified", // Windows named-pipe form
        )

        private val extraDirs: List<String> by lazy {
            val home = System.getProperty("user.home").orEmpty()
            listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "$home/.docker/bin",
                "/Applications/Docker.app/Contents/Resources/bin",
                "/usr/bin",
                "/bin",
            ).filter { it.isNotBlank() }
        }

        fun resolve(): File? = ProcessRunner.resolve("docker", extraDirs)

        suspend fun exec(args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
            ProcessRunner.run(
                exe = resolve(),
                args = args,
                extraPathDirs = extraDirs,
                timeoutMs = timeoutMs,
                missingMessage = "The docker CLI was not found on this machine.",
                timeoutMessage = "docker ${args.firstOrNull().orEmpty()} timed out",
            ).let { Result(it.exitCode, it.stdout, it.stderr) }

        private const val DEFAULT_TIMEOUT_MS = 30_000L

        data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
            val ok: Boolean get() = exitCode == 0

            /** stderr first — docker writes the useful failure text there. */
            val message: String get() = stderr.ifBlank { stdout }.trim()

            val daemonUnreachable: Boolean
                get() = !ok && DAEMON_DOWN_MARKERS.any { stderr.contains(it, ignoreCase = true) }
        }
    }
}

/**
 * Decode one wire intent for [DockerStateHolder].
 *
 * Single-field intents take the payload as a bare string (the convention the
 * other holders' arms use); multi-field intents take a JSON object. An unknown
 * [intentType], or a payload that does not carry the fields the intent needs,
 * decodes to null so `PluginStateSyncService` drops it rather than acting on a
 * half-read message.
 *
 * There is deliberately no arm for start / stop / restart / rm / rmi: those
 * intents do not exist. See [DockerStateHolder] for why.
 */
internal fun decodeDockerIntent(intentType: String, payload: String): DockerIntent? {
    val obj = runCatching { dockerIntentJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    fun str(key: String): String? = obj?.get(key)?.jsonPrimitive?.contentOrNull
    fun int(key: String): Int? = obj?.get(key)?.jsonPrimitive?.intOrNull

    return when (intentType) {
        "Refresh" -> DockerIntent.Refresh
        "RescanProject" -> DockerIntent.RescanProject
        "ClearSelection" -> DockerIntent.ClearSelection
        "ClearDetail" -> DockerIntent.ClearDetail

        "SetQuery" -> DockerIntent.SetQuery(payload)

        "ToggleSection" -> runCatching { DockerSection.valueOf(payload.trim().uppercase()) }
            .getOrNull()?.let { DockerIntent.ToggleSection(it) }

        "SelectContainer" -> payload.ifBlank { null }?.let { DockerIntent.SelectContainer(it) }

        "ShowInspect" -> payload.ifBlank { null }?.let { DockerIntent.ShowInspect(it) }

        // Accepts either a bare container id or {"containerId":…,"tail":…}. A
        // payload that *is* a JSON object never falls back to being read as an
        // id — `{}` would otherwise become a container literally named "{}".
        "ShowLogs" -> {
            val containerId = str("containerId")
                ?: payload.takeIf { obj == null }?.ifBlank { null }
                ?: return null
            DockerIntent.ShowLogs(containerId, int("tail") ?: DockerIntent.DEFAULT_TAIL)
        }

        else -> null
    }
}

private val dockerIntentJson = Json { ignoreUnknownKeys = true; isLenient = true }
