package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

// region State

/**
 * The Kubernetes sidebar surface, mirrored for a host-side renderer.
 *
 * This is the **browse** surface: which contexts the kubeconfig offers, which one
 * this holder is pointed at, and the namespaced resources of the selected
 * namespace. See [KubernetesStateHolder] for what a child JVM cannot do.
 * [mutationsAvailable], [terminalAvailable] and [forwardsAvailable] are always
 * `false` so the host renders no Delete / Scale / Apply / Exec / Port-forward
 * affordance that it cannot honour safely.
 */
@Serializable
data class KubernetesState(
    val cluster: KubeClusterStatus = KubeClusterStatus.UNKNOWN,
    /** `serverVersion.gitVersion` when the API server answers. */
    val serverVersion: String = "",
    /** Failure text for the non-ready [cluster] states. */
    val clusterError: String = "",
    val contexts: List<KubeContext> = emptyList(),
    /** The context this holder is pointed at. Never written back to the kubeconfig. */
    val selectedContext: String? = null,
    val namespaces: List<KubeNamespace> = emptyList(),
    /** `*` means all namespaces, matching the plugin's `KubeTarget.ALL_NAMESPACES`. */
    val selectedNamespace: String = "default",
    val workloads: List<KubeWorkload> = emptyList(),
    val pods: List<KubePod> = emptyList(),
    val services: List<KubeService> = emptyList(),
    val configMaps: List<KubeNamedResource> = emptyList(),
    val secrets: List<KubeSecret> = emptyList(),
    val manifests: List<KubeManifest> = emptyList(),
    /** Which sidebar groups are open. Only open groups are queried. */
    val expandedSections: List<KubeSection> = listOf(KubeSection.WORKLOADS, KubeSection.PODS),
    val query: String = "",
    val selectedKind: String = "",
    val selectedName: String = "",
    /** What [detailText] holds, so a renderer knows which body to draw. */
    val detail: KubeDetailKind = KubeDetailKind.NONE,
    val detailText: String = "",
    val busy: Boolean = false,
    /**
     * True once the holder has published its first state. An empty namespace is a
     * legitimate result, so this is what distinguishes "initialised, nothing
     * there" from "no state has ever arrived".
     */
    val ready: Boolean = false,
    /** Always false out-of-process — no delete / scale / rollout / apply. */
    val mutationsAvailable: Boolean = false,
    /** Always false out-of-process — `openTab` is an inherited no-op on the IPC proxy. */
    val terminalAvailable: Boolean = false,
    /** Always false out-of-process — a forward this holder owns is a process the host cannot see. */
    val forwardsAvailable: Boolean = false,
    /** Always false out-of-process — the host's plugin storage is not on the wire. */
    val persistenceAvailable: Boolean = false,
)

/**
 * Whether the cluster is usable. Mirrors the plugin's `ClusterState` sum type,
 * flattened to an enum plus [KubernetesState.serverVersion] /
 * [KubernetesState.clusterError] because a polymorphic sealed hierarchy needs a
 * serializers module the generic `PluginStateSyncService` encoder does not install.
 */
@Serializable
enum class KubeClusterStatus {
    /** Not probed yet. */
    UNKNOWN,

    /** No `kubectl` binary on PATH or in the usual install dirs. */
    KUBECTL_MISSING,

    /** kubectl present, but the kubeconfig has no usable current context. */
    NO_CONTEXT,

    /** The API server could not be reached at all. */
    UNREACHABLE,

    /** Reached the server, but this identity may not list what we asked for. */
    FORBIDDEN,

    READY,

    ERROR,
}

/** The resource groups the sidebar can show. Mirrors the plugin's `KubeSection`. */
@Serializable
enum class KubeSection { PROJECT, WORKLOADS, PODS, SERVICES, CONFIGMAPS, SECRETS }

/** Which detail body [KubernetesState.detailText] is carrying. */
@Serializable
enum class KubeDetailKind { NONE, LOGS, DESCRIBE, YAML }

/** A kubeconfig context. Selecting one never writes to the kubeconfig. */
@Serializable
data class KubeContext(
    val name: String,
    val cluster: String,
    val namespace: String,
    /** True for the kubeconfig's own `current-context`. */
    val current: Boolean,
)

@Serializable
data class KubeNamespace(val name: String, val phase: String)

/** Deployments, StatefulSets and DaemonSets share one row shape. */
@Serializable
data class KubeWorkload(
    val kind: String,
    val name: String,
    val namespace: String,
    val ready: Int,
    val desired: Int,
    val images: List<String> = emptyList(),
    val createdAt: String = "",
    /**
     * Whether every desired replica is ready. A **constructor property**, not a
     * computed getter: kotlinx serializes only constructor properties, so a
     * getter would be absent from the synced payload and every host renderer
     * would have to re-derive it.
     */
    val healthy: Boolean = false,
)

@Serializable
data class KubePod(
    val name: String,
    val namespace: String,
    /** The container's waiting reason when it has one, else `status.phase`. */
    val phase: String,
    val readyContainers: Int,
    val totalContainers: Int,
    val restarts: Int,
    val node: String = "",
    val createdAt: String = "",
    val containers: List<String> = emptyList(),
    val initContainers: List<String> = emptyList(),
    /**
     * Whether this pod needs attention. A **constructor property**, not a
     * computed getter: kotlinx serializes only constructor properties, so a
     * getter would be absent from the synced payload and every host renderer
     * would have to re-derive it.
     */
    val failing: Boolean = false,
)

@Serializable
data class KubeServicePort(
    val name: String,
    val port: Int,
    val targetPort: String,
    val protocol: String,
)

@Serializable
data class KubeService(
    val name: String,
    val namespace: String,
    val type: String,
    val clusterIp: String,
    val ports: List<KubeServicePort> = emptyList(),
    val createdAt: String = "",
)

/** A resource listed by name only — ConfigMaps, whose `data` is never requested. */
@Serializable
data class KubeNamedResource(val name: String, val namespace: String, val createdAt: String = "")

/**
 * A Secret, deliberately without any value-bearing field. Listed with
 * `custom-columns` naming only metadata and type, exactly as the plugin does:
 * the `data` map is never requested and never parsed.
 */
@Serializable
data class KubeSecret(
    val name: String,
    val namespace: String,
    val type: String,
    val createdAt: String = "",
)

/** A manifest or kustomization found in the open project. */
@Serializable
data class KubeManifest(
    val path: String,
    val relativePath: String,
    val kind: KubeManifestKind,
)

@Serializable
enum class KubeManifestKind { MANIFEST, KUSTOMIZATION }

// endregion

// region Intent

sealed class KubernetesIntent {
    /** Re-read the contexts, re-probe the cluster and re-read every open section. */
    object Refresh : KubernetesIntent()

    /** Re-scan [KubernetesState.manifests] from the child's `BOSS_PROJECT_PATH`. */
    object RescanProject : KubernetesIntent()

    /** Point at a different kubeconfig context. Local only — the kubeconfig is untouched. */
    data class SelectContext(val context: String) : KubernetesIntent()

    /** Point at a namespace, or `*` for all namespaces. */
    data class SelectNamespace(val namespace: String) : KubernetesIntent()

    data class SetQuery(val query: String) : KubernetesIntent()
    data class ToggleSection(val section: KubeSection) : KubernetesIntent()

    /** Select a row. [kind] is the kubectl type (`pod`, `deployment`, `service`, …). */
    data class SelectResource(val kind: String, val name: String) : KubernetesIntent()

    object ClearSelection : KubernetesIntent()

    /** Fetch the last [tail] lines of a pod's log into [KubernetesState.detailText]. */
    data class ShowLogs(
        val podName: String,
        val container: String = "",
        val tail: Int = DEFAULT_TAIL,
    ) : KubernetesIntent()

    data class ShowDescribe(val kind: String, val name: String) : KubernetesIntent()

    /** `kubectl get <kind> <name> -o yaml`. Refused for Secrets — see the holder docs. */
    data class ShowYaml(val kind: String, val name: String) : KubernetesIntent()

    object ClearDetail : KubernetesIntent()

    companion object {
        const val DEFAULT_TAIL = 500
    }
}

// endregion

/**
 * StateHolder for the Kubernetes plugin — the **sidebar browse** surface.
 *
 * The in-process plugin is a browser, a control panel, a port-forward supervisor
 * and a Helm client. What survives out-of-process, and what does not:
 *
 * - **Reading the cluster works, fully.** This holder issues the same read-only
 *   argv the plugin's `KubeEngine` does — `kubectl version -o json`,
 *   `config view` / `config current-context`, `get <resource> -o json` for pods,
 *   workloads, services and namespaces, and the *custom-columns* form for
 *   ConfigMaps and Secrets — with the selected context and namespace passed per
 *   invocation (`--context` / `-n`) and a `--request-timeout` on every call.
 *   **The kubeconfig is read and never written**, which is the plugin's headline
 *   invariant and is preserved here: there is no `config use-context` and no
 *   `set-context`, and [KubernetesIntent.SelectContext] changes only this
 *   holder's own selection.
 * - **The Secret guarantee is preserved structurally.** Secrets are listed with
 *   `custom-columns=NAME,NS,TYPE,AGE`, never `-o json`, so `data` never enters
 *   this process; [KubeSecret] has no value-bearing field; and
 *   [KubernetesIntent.ShowYaml] refuses a Secret kind outright, the same refusal
 *   the plugin's `KubeActions.yaml()` makes. That matters more here than
 *   in-process: a synced state envelope is a *copy* of the payload leaving the
 *   process.
 * - **Mutations are deliberately not offered.** `delete`, `scale`,
 *   `rollout restart`, `apply` and `exec` are all reachable from a child JVM, but
 *   in-process every one is gated behind a `ConfirmRequest` naming the context
 *   and namespace, and on the MCP surface behind the `kubernetes.manage`
 *   permission. Neither gate has a shape on the state-sync wire — an intent is a
 *   type string and a byte payload, with no confirmation and no permission
 *   context — and `kubectl delete` against whatever context the kubeconfig
 *   happens to name is the highest-consequence call in this plugin.
 *   [KubernetesState.mutationsAvailable] is always false and no mutating intent
 *   exists to send.
 * - **Port-forwards are unavailable.** The plugin supervises long-lived
 *   `kubectl port-forward` children and restarts them up to twenty times; a
 *   child JVM can spawn them, but they would bind ports on the operator's
 *   machine with no owner the host can see or stop — the plugin already has a
 *   documented leak of exactly this shape when the host disables it without
 *   calling `dispose()`. [KubernetesState.forwardsAvailable] is always false, and
 *   with it the service tab's embedded preview, which is served *through* a
 *   forward and needs `browserService` (`Nothing? = null` here) besides.
 * - **Helm is out of scope for this holder.** It is a second CLI, a second state
 *   machine and twenty-three MCP tools, and its write half (`install`, `upgrade`,
 *   `rollback`, `uninstall`, `push`) mutates both the cluster and the operator's
 *   shared `~/.config/helm/repositories.yaml`. Mirroring only its read half would
 *   put a Releases list next to a dead Install button; it is better named as
 *   absent than half-present.
 * - **Persistence is unavailable.** The selected context, namespace and pinned
 *   custom resources live in `pluginStorageFactory`, `Nothing? = null` here, and
 *   the IPC contract has no plugin-storage service at all. The selection lives
 *   for the child process's lifetime and starts from the kubeconfig's own
 *   current context, which is what the plugin does on first run anyway.
 *
 * Sections are read on demand rather than followed with `kubectl get --watch`:
 * the plugin's watches keep a live panel fresh, and a permanently attached watch
 * child per section in every spawned holder is a process the host cannot see. A
 * host renderer that wants live rows sends [KubernetesIntent.Refresh].
 */
class KubernetesStateHolder : PluginStateHolder<KubernetesState, KubernetesIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(KubernetesStateHolder::class.java)

    /** Project root for the manifest scan — `BOSS_PROJECT_PATH`, when the host set it. */
    private val projectPath: String?

    private var refreshJob: Job? = null
    private var detailJob: Job? = null

    constructor(scope: CoroutineScope) : this(scope, projectPath = null)

    constructor(scope: CoroutineScope, context: RemotePluginContext) :
        this(scope, projectPath = context.projectPath) {
        logger.info(
            "KubernetesStateHolder started (projectPath={}, mutations/forwards/helm unavailable OOP)",
            context.projectPath,
        )
    }

    private constructor(scope: CoroutineScope, projectPath: String?) : super(KubernetesState(), scope) {
        this.projectPath = projectPath
        // Publish an initial versioned state before any kubectl call completes. A
        // holder that never calls updateState stays at version 0 and never
        // renders at all (the bookmarks failure mode) — and probing an
        // unreachable cluster takes seconds even with a request timeout.
        updateState { copy(ready = true) }
        onIntent(KubernetesIntent.RescanProject)
        onIntent(KubernetesIntent.Refresh)
    }

    override fun onIntent(intent: KubernetesIntent) {
        when (intent) {
            is KubernetesIntent.Refresh -> refresh()

            is KubernetesIntent.RescanProject -> {
                val root = projectPath?.let(::File)
                val manifests = if (root == null || !root.isDirectory) emptyList() else scanManifests(root)
                updateState { copy(manifests = manifests) }
            }

            is KubernetesIntent.SelectContext -> {
                if (currentState().contexts.none { it.name == intent.context }) {
                    logger.debug("Ignoring unknown context {}", intent.context)
                    return
                }
                if (currentState().selectedContext == intent.context) return
                val adopted = currentState().contexts.first { it.name == intent.context }
                updateState {
                    clearResources().copy(
                        selectedContext = intent.context,
                        selectedNamespace = adopted.namespace.ifBlank { DEFAULT_NAMESPACE },
                    )
                }
                refresh()
            }

            is KubernetesIntent.SelectNamespace -> {
                if (currentState().selectedNamespace == intent.namespace) return
                updateState { clearResources().copy(selectedNamespace = intent.namespace) }
                refresh()
            }

            is KubernetesIntent.SetQuery -> updateState { copy(query = intent.query) }

            is KubernetesIntent.ToggleSection -> {
                val opening = intent.section !in currentState().expandedSections
                updateState {
                    copy(
                        expandedSections = if (opening) {
                            expandedSections + intent.section
                        } else {
                            expandedSections - intent.section
                        },
                    )
                }
                if (opening) refresh()
            }

            is KubernetesIntent.SelectResource -> updateState {
                copy(selectedKind = intent.kind, selectedName = intent.name)
            }

            is KubernetesIntent.ClearSelection -> updateState {
                copy(selectedKind = "", selectedName = "", detail = KubeDetailKind.NONE, detailText = "")
            }

            is KubernetesIntent.ShowLogs -> {
                val argv = buildList {
                    add("logs")
                    add(intent.podName)
                    add("--tail=${intent.tail.coerceIn(1, MAX_TAIL)}")
                    if (intent.container.isNotBlank()) addAll(listOf("-c", intent.container))
                }
                fetchDetail("pod", intent.podName, KubeDetailKind.LOGS, argv)
            }

            is KubernetesIntent.ShowDescribe -> fetchDetail(
                kind = intent.kind,
                name = intent.name,
                detail = KubeDetailKind.DESCRIBE,
                command = listOf("describe", intent.kind, intent.name),
            )

            is KubernetesIntent.ShowYaml -> {
                // The same refusal the plugin's KubeActions.yaml() makes: a
                // Secret's YAML is its base64 payload, and this holder's whole
                // point is to copy state out of the process.
                if (isSecretKind(intent.kind)) {
                    updateState {
                        copy(
                            selectedKind = intent.kind,
                            selectedName = intent.name,
                            detail = KubeDetailKind.YAML,
                            detailText = SECRET_YAML_REFUSAL,
                        )
                    }
                    return
                }
                fetchDetail(
                    kind = intent.kind,
                    name = intent.name,
                    detail = KubeDetailKind.YAML,
                    command = listOf("get", intent.kind, intent.name, "-o", "yaml"),
                )
            }

            is KubernetesIntent.ClearDetail -> updateState {
                copy(detail = KubeDetailKind.NONE, detailText = "")
            }
        }
    }

    // region refresh

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            updateState { copy(busy = true) }
            try {
                readContexts()
                if (!probe()) return@launch
                readNamespaces()
                currentState().expandedSections.forEach { readSection(it) }
            } finally {
                updateState { copy(busy = false) }
            }
        }
    }

    /**
     * Read the kubeconfig's contexts and its `current-context`, and on first run
     * adopt that context and its namespace as the starting selection — read,
     * never written, exactly as the plugin does.
     */
    private suspend fun readContexts() {
        val listed = Cli.exec(
            listOf(
                "config", "view", "-o",
                "go-template={{range .contexts}}{{.name}}\t{{.context.cluster}}\t" +
                    "{{if .context.namespace}}{{.context.namespace}}{{end}}\n{{end}}",
            ),
            PROBE_TIMEOUT_MS,
        )
        if (!listed.ok) return
        val current = Cli.exec(listOf("config", "current-context"), PROBE_TIMEOUT_MS)
            .takeIf { it.ok }?.stdout?.trim().orEmpty()

        val parsed = listed.stdout.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split('\t')
            val name = cols.getOrNull(0).cleanTemplateValue()
            if (name.isBlank()) return@mapNotNull null
            KubeContext(
                name = name,
                cluster = cols.getOrNull(1).cleanTemplateValue(),
                namespace = cols.getOrNull(2).cleanTemplateValue(),
                current = name == current,
            )
        }.toList()

        updateState { copy(contexts = parsed) }
        if (currentState().selectedContext == null) {
            val start = parsed.firstOrNull { it.current } ?: parsed.firstOrNull()
            if (start != null) {
                updateState {
                    copy(
                        selectedContext = start.name,
                        selectedNamespace = start.namespace.ifBlank { DEFAULT_NAMESPACE },
                    )
                }
            }
        }
    }

    /** Cheap liveness probe; also the source of the version we publish. */
    private suspend fun probe(): Boolean {
        if (Cli.resolve() == null) {
            updateState { clearResources().copy(cluster = KubeClusterStatus.KUBECTL_MISSING, clusterError = "") }
            return false
        }
        val result = Cli.exec(args(listOf("version", "-o", "json"), namespaced = false), PROBE_TIMEOUT_MS)
        if (result.ok) {
            updateState {
                copy(
                    cluster = KubeClusterStatus.READY,
                    serverVersion = serverVersionOf(result.stdout),
                    clusterError = "",
                )
            }
            return true
        }
        val status = when {
            result.exitCode == ProcessRunner.EXIT_BINARY_MISSING -> KubeClusterStatus.KUBECTL_MISSING
            result.badContext -> KubeClusterStatus.NO_CONTEXT
            result.unreachable || result.exitCode == ProcessRunner.EXIT_TIMEOUT -> KubeClusterStatus.UNREACHABLE
            result.forbidden -> KubeClusterStatus.FORBIDDEN
            else -> KubeClusterStatus.ERROR
        }
        updateState {
            clearResources().copy(
                cluster = status,
                serverVersion = "",
                clusterError = result.cleanError.take(MAX_ERROR_CHARS),
            )
        }
        return false
    }

    private suspend fun readNamespaces() {
        val result = Cli.exec(args(listOf("get", "namespaces", "-o", "json"), namespaced = false))
        if (!result.ok) return
        val namespaces = parseNamespaces(result.stdout)
        updateState { copy(namespaces = namespaces) }
    }

    private suspend fun readSection(section: KubeSection) {
        when (section) {
            KubeSection.PROJECT -> onIntent(KubernetesIntent.RescanProject)

            KubeSection.WORKLOADS -> {
                val out = buildList {
                    addAll(parseWorkloads(get("deployments"), "Deployment"))
                    addAll(parseWorkloads(get("statefulsets"), "StatefulSet"))
                    addAll(parseWorkloads(get("daemonsets"), "DaemonSet"))
                }.sortedWith(compareByDescending<KubeWorkload> { !it.healthy }.thenBy { it.name })
                updateState { copy(workloads = out) }
            }

            KubeSection.PODS -> {
                val pods = parsePods(get("pods"))
                updateState { copy(pods = pods) }
            }

            KubeSection.SERVICES -> {
                val services = parseServices(get("services"))
                updateState { copy(services = services) }
            }

            KubeSection.CONFIGMAPS -> {
                val rows = parseConfigMapRows(configMapColumns())
                updateState { copy(configMaps = rows) }
            }

            KubeSection.SECRETS -> {
                val rows = parseSecretRows(secretColumns())
                updateState { copy(secrets = rows) }
            }
        }
    }

    /**
     * ConfigMaps are listed with custom columns rather than `-o json`: their
     * `data` can be megabytes of embedded files, and a list only needs names.
     */
    private suspend fun configMapColumns(): String {
        val result = Cli.exec(
            args(
                listOf(
                    "get", "configmaps",
                    "-o",
                    "custom-columns=NAME:.metadata.name,NS:.metadata.namespace,AGE:.metadata.creationTimestamp",
                    "--no-headers",
                ),
            ),
        )
        return if (result.ok) result.stdout else ""
    }

    /**
     * Secrets are listed with custom columns naming only metadata and type — the
     * `data` map is never requested and never parsed. This is the structural half
     * of the promise that no secret value can reach the wire.
     */
    private suspend fun secretColumns(): String {
        val result = Cli.exec(
            args(
                listOf(
                    "get", "secrets",
                    "-o",
                    "custom-columns=NAME:.metadata.name,NS:.metadata.namespace,TYPE:.type," +
                        "AGE:.metadata.creationTimestamp",
                    "--no-headers",
                ),
            ),
        )
        return if (result.ok) result.stdout else ""
    }

    /**
     * `kubectl get <resource> -o json`, or empty text when the call failed. An
     * unreachable answer downgrades the cluster verdict on the way out, which is
     * how a cluster that dies mid-refresh stops looking ready.
     */
    private suspend fun get(resource: String): String {
        val result = Cli.exec(args(listOf("get", resource, "-o", "json")))
        if (!result.ok) {
            if (result.unreachable) {
                updateState {
                    copy(
                        cluster = KubeClusterStatus.UNREACHABLE,
                        clusterError = result.cleanError.take(MAX_ERROR_CHARS),
                    )
                }
            }
            return ""
        }
        return result.stdout
    }

    private fun fetchDetail(kind: String, name: String, detail: KubeDetailKind, command: List<String>) {
        detailJob?.cancel()
        detailJob = scope.launch {
            updateState { copy(selectedKind = kind, selectedName = name, busy = true) }
            val result = Cli.exec(args(command), DETAIL_TIMEOUT_MS)
            val text = if (result.ok) result.stdout else result.cleanError
            updateState {
                copy(
                    detail = detail,
                    detailText = text.takeLast(MAX_DETAIL_CHARS),
                    busy = false,
                )
            }
        }
    }

    /**
     * Prefix every invocation with the selected context and namespace. This is
     * what keeps the kubeconfig untouched.
     */
    private fun args(command: List<String>, namespaced: Boolean = true): List<String> = buildList {
        addAll(command)
        val state = currentState()
        state.selectedContext?.let { addAll(listOf("--context", it)) }
        if (namespaced) {
            if (state.selectedNamespace == ALL_NAMESPACES) {
                add("--all-namespaces")
            } else {
                addAll(listOf("-n", state.selectedNamespace))
            }
        }
        if (command.firstOrNull() != "config") add(REQUEST_TIMEOUT)
    }

    private fun KubernetesState.clearResources(): KubernetesState = copy(
        workloads = emptyList(),
        pods = emptyList(),
        services = emptyList(),
        configMaps = emptyList(),
        secrets = emptyList(),
        selectedKind = "",
        selectedName = "",
        detail = KubeDetailKind.NONE,
        detailText = "",
    )

    // endregion

    // region project scan

    private fun scanManifests(root: File): List<KubeManifest> = buildList {
        root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir == root || (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile }
            .forEach { file ->
                val kind = classify(file) ?: return@forEach
                add(
                    KubeManifest(
                        path = file.absolutePath,
                        relativePath = file.relativeToOrNull(root)?.path ?: file.name,
                        kind = kind,
                    ),
                )
                if (size >= MAX_MANIFESTS) return@buildList
            }
    }.sortedWith(compareBy({ it.kind != KubeManifestKind.KUSTOMIZATION }, { it.relativePath }))

    /**
     * A YAML file counts as a manifest only if it looks like one — a repo is full
     * of CI configs and lockfiles that would otherwise flood the section.
     */
    private fun classify(file: File): KubeManifestKind? {
        val lower = file.name.lowercase()
        if (lower == "kustomization.yaml" || lower == "kustomization.yml") {
            return KubeManifestKind.KUSTOMIZATION
        }
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) return null
        val inK8sDir = file.parentFile?.name?.lowercase() in K8S_DIR_NAMES
        val head = runCatching {
            file.bufferedReader().useLines { lines -> lines.take(HEAD_LINES).joinToString("\n") }
        }.getOrDefault("")
        val looksLikeManifest = head.contains(API_VERSION_LINE) && head.contains(KIND_LINE)
        return if (looksLikeManifest || (inK8sDir && head.contains("kind:"))) KubeManifestKind.MANIFEST else null
    }

    // endregion

    // region kubectl wire shape

    @Serializable
    private data class RawMeta(
        val name: String = "",
        val namespace: String = "",
        val creationTimestamp: String = "",
    )

    @Serializable
    private data class RawList<T>(val items: List<T> = emptyList())

    @Serializable
    private data class RawNamespace(val metadata: RawMeta = RawMeta(), val status: Status = Status()) {
        @Serializable
        data class Status(val phase: String = "")
    }

    @Serializable
    private data class RawPod(
        val metadata: RawMeta = RawMeta(),
        val spec: Spec = Spec(),
        val status: Status = Status(),
    ) {
        @Serializable
        data class Spec(
            val nodeName: String = "",
            val containers: List<Named> = emptyList(),
            val initContainers: List<Named> = emptyList(),
        )

        @Serializable
        data class Named(val name: String = "")

        @Serializable
        data class Status(
            val phase: String = "",
            val containerStatuses: List<ContainerStatus> = emptyList(),
        )

        @Serializable
        data class ContainerStatus(
            val ready: Boolean = false,
            val restartCount: Int = 0,
            val state: State = State(),
        )

        @Serializable
        data class State(val waiting: Waiting? = null)

        @Serializable
        data class Waiting(val reason: String = "")

        fun toState(): KubePod {
            // A pod stuck in CrashLoopBackOff still reports phase=Running; the
            // waiting reason is what a human actually wants to see in the row.
            val waitingReason = status.containerStatuses
                .firstNotNullOfOrNull { it.state.waiting?.reason?.ifBlank { null } }
            val phase = waitingReason ?: status.phase
            val ready = status.containerStatuses.count { it.ready }
            val total = status.containerStatuses.size.takeIf { it > 0 } ?: spec.containers.size
            return KubePod(
                name = metadata.name,
                namespace = metadata.namespace,
                phase = phase,
                readyContainers = ready,
                totalContainers = total,
                restarts = status.containerStatuses.sumOf { it.restartCount },
                node = spec.nodeName,
                createdAt = metadata.creationTimestamp,
                containers = spec.containers.map { it.name },
                initContainers = spec.initContainers.map { it.name },
                failing = isFailing(phase, ready, total),
            )
        }
    }

    @Serializable
    private data class RawWorkload(
        val metadata: RawMeta = RawMeta(),
        val spec: Spec = Spec(),
        val status: Status = Status(),
    ) {
        @Serializable
        data class Spec(val replicas: Int? = null, val template: Template = Template())

        @Serializable
        data class Template(val spec: PodSpec = PodSpec())

        @Serializable
        data class PodSpec(val containers: List<Container> = emptyList())

        @Serializable
        data class Container(val image: String = "")

        @Serializable
        data class Status(
            val readyReplicas: Int = 0,
            val replicas: Int = 0,
            // DaemonSets use a different vocabulary for the same two numbers.
            val numberReady: Int = 0,
            val desiredNumberScheduled: Int = 0,
        )

        fun toState(kindOverride: String): KubeWorkload {
            val isDaemonSet = kindOverride.equals("DaemonSet", ignoreCase = true)
            val ready = if (isDaemonSet) status.numberReady else status.readyReplicas
            val desired =
                if (isDaemonSet) status.desiredNumberScheduled else (spec.replicas ?: status.replicas)
            return KubeWorkload(
                kind = kindOverride,
                name = metadata.name,
                namespace = metadata.namespace,
                ready = ready,
                desired = desired,
                images = spec.template.spec.containers.map { it.image }.filter { it.isNotBlank() },
                createdAt = metadata.creationTimestamp,
                healthy = desired > 0 && ready == desired,
            )
        }
    }

    @Serializable
    private data class RawService(val metadata: RawMeta = RawMeta(), val spec: Spec = Spec()) {
        @Serializable
        data class Spec(
            val type: String = "",
            val clusterIP: String = "",
            val ports: List<Port> = emptyList(),
        )

        @Serializable
        data class Port(
            val name: String = "",
            val port: Int = 0,
            // targetPort is an int OR a named port string, so it can only be
            // modelled as a raw element.
            val targetPort: JsonElement? = null,
            val protocol: String = "TCP",
        )

        fun toState() = KubeService(
            name = metadata.name,
            namespace = metadata.namespace,
            type = spec.type.ifBlank { "ClusterIP" },
            clusterIp = spec.clusterIP,
            ports = spec.ports.map { port ->
                KubeServicePort(
                    name = port.name,
                    port = port.port,
                    targetPort = port.targetPort.renderScalar().ifBlank { port.port.toString() },
                    protocol = port.protocol.ifBlank { "TCP" },
                )
            },
            createdAt = metadata.creationTimestamp,
        )
    }

    // endregion

    internal companion object {
        const val ALL_NAMESPACES = "*"
        const val DEFAULT_NAMESPACE = "default"

        /**
         * Refusal text for [KubernetesIntent.ShowYaml] on a Secret — the same
         * refusal the plugin's `KubeActions.yaml()` makes.
         */
        const val SECRET_YAML_REFUSAL =
            "Refused: a Secret's YAML is its base64-encoded data. " +
                "This surface never renders secret values."

        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val DETAIL_TIMEOUT_MS = 20_000L
        private const val SCAN_DEPTH = 5
        private const val MAX_MANIFESTS = 80
        private const val MAX_TAIL = 5_000
        private const val MAX_DETAIL_CHARS = 200_000
        private const val MAX_ERROR_CHARS = 300
        private const val HEAD_LINES = 30

        /**
         * Without a request timeout an unreachable cluster hangs for kubectl's own
         * default, which is far longer than a panel can wait.
         */
        private const val REQUEST_TIMEOUT = "--request-timeout=5s"

        private val WHITESPACE = Regex("\\s+")
        private val API_VERSION_LINE = Regex("^apiVersion:\\s*\\S", RegexOption.MULTILINE)
        private val KIND_LINE = Regex("^kind:\\s*\\S", RegexOption.MULTILINE)

        /** `serverVersion.gitVersion` inside `kubectl version -o json`. */
        private val SERVER_VERSION_REGEX = Regex(
            "\"serverVersion\"\\s*:\\s*\\{[^}]*?\"gitVersion\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL,
        )

        private val SKIP_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor",
            "venv", "__pycache__", "Pods", "DerivedData", "tmp",
        )
        private val K8S_DIR_NAMES =
            setOf("k8s", "kubernetes", "manifests", "deploy", "deployment", "chart", "charts")

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * Whether a pod needs attention. A pod stuck in CrashLoopBackOff still
         * reports `phase: Running`, so the waiting reason has already replaced
         * the phase by the time this is called.
         */
        internal fun isFailing(phase: String, readyContainers: Int, totalContainers: Int): Boolean =
            phase.equals("Failed", ignoreCase = true) ||
                phase.equals("CrashLoopBackOff", ignoreCase = true) ||
                (phase.equals("Running", ignoreCase = true) && readyContainers < totalContainers)

        /** Every spelling of a Secret kind kubectl accepts. */
        internal fun isSecretKind(kind: String): Boolean =
            kind.trim().lowercase().removeSuffix("s") == "secret" ||
                kind.trim().lowercase().startsWith("secret/")

        internal fun serverVersionOf(stdout: String): String =
            SERVER_VERSION_REGEX.find(stdout)?.groupValues?.getOrNull(1) ?: "unknown"

        /**
         * `{{.context.namespace}}` renders the literal `<no value>` when a context
         * sets no namespace, which would then be passed to `kubectl -n` verbatim
         * and match nothing.
         */
        internal fun String?.cleanTemplateValue(): String {
            val trimmed = this?.trim().orEmpty()
            return if (trimmed == "<no value>" || trimmed == "<nil>") "" else trimmed
        }

        /**
         * Decode a `kubectl get -o json` list. Unusable output yields no rows
         * rather than throwing into the state-sync loop.
         */
        private fun <T> parseItems(stdout: String, serializer: KSerializer<T>): List<T> = runCatching {
            json.decodeFromString(RawList.serializer(serializer), stdout).items
        }.getOrDefault(emptyList())

        /** Failing pods first, then by name — what the sidebar shows. */
        internal fun parsePods(stdout: String): List<KubePod> =
            parseItems(stdout, RawPod.serializer())
                .map { it.toState() }
                .sortedWith(compareByDescending<KubePod> { it.failing }.thenBy { it.name })

        internal fun parseWorkloads(stdout: String, kind: String): List<KubeWorkload> =
            parseItems(stdout, RawWorkload.serializer()).map { it.toState(kind) }

        internal fun parseServices(stdout: String): List<KubeService> =
            parseItems(stdout, RawService.serializer()).map { it.toState() }.sortedBy { it.name }

        internal fun parseNamespaces(stdout: String): List<KubeNamespace> =
            parseItems(stdout, RawNamespace.serializer())
                .map { KubeNamespace(it.metadata.name, it.status.phase) }
                .sortedBy { it.name }

        /** `custom-columns=NAME,NS,AGE --no-headers`, one ConfigMap per line. */
        internal fun parseConfigMapRows(stdout: String): List<KubeNamedResource> =
            stdout.lineSequence().mapNotNull { line ->
                val cols = line.trim().split(WHITESPACE)
                if (cols.size < 3) return@mapNotNull null
                KubeNamedResource(name = cols[0], namespace = cols[1], createdAt = cols[2])
            }.sortedBy { it.name }.toList()

        /** `custom-columns=NAME,NS,TYPE,AGE --no-headers`. No `data` column, ever. */
        internal fun parseSecretRows(stdout: String): List<KubeSecret> =
            stdout.lineSequence().mapNotNull { line ->
                val cols = line.trim().split(WHITESPACE)
                if (cols.size < 4) return@mapNotNull null
                KubeSecret(name = cols[0], namespace = cols[1], type = cols[2], createdAt = cols[3])
            }.sortedBy { it.name }.toList()

        /** `targetPort` may be `8080` or `"http"`; render either as a plain string. */
        private fun JsonElement?.renderScalar(): String = when (this) {
            null -> ""
            is JsonPrimitive -> content
            else -> toString()
        }
    }

    /**
     * The `kubectl` CLI, resolved and invoked the way the plugin's `KubectlCli`
     * does: absolute binary, widened child PATH (cloud kubeconfigs shell out to
     * credential plugins that must be findable), argv lists rather than shell
     * strings, and a request timeout on every server-touching call.
     *
     * PATH is searched before the fallback install dirs, so a test that puts a
     * stub `kubectl` first on the child's PATH is guaranteed to get the stub and
     * never the operator's real cluster.
     */
    private object Cli {
        private val UNREACHABLE_MARKERS = listOf(
            "connection refused",
            "connection to the server",
            "was refused",
            "did you specify the right host or port",
            "Unable to connect to the server",
            "couldn't get current server API group list",
            "dial tcp",
            "no such host",
            "i/o timeout",
            "TLS handshake timeout",
            "context deadline exceeded",
        )
        private val FORBIDDEN_MARKERS = listOf(
            "is forbidden",
            "Unauthorized",
            "error: You must be logged in",
            "Forbidden",
        )
        private val BAD_CONTEXT_MARKERS = listOf(
            "context was not found",
            "no context exists",
            "current-context is not set",
            "no configuration has been provided",
        )

        private val extraDirs: List<String> by lazy {
            val home = System.getProperty("user.home").orEmpty()
            listOf(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "$home/.local/bin",
                "$home/bin",
                // Docker Desktop bundles its own kubectl; last resort.
                "/Applications/Docker.app/Contents/Resources/bin",
                // Credential plugins for cloud clusters.
                "$home/google-cloud-sdk/bin",
                "/opt/homebrew/share/google-cloud-sdk/bin",
                "/usr/bin",
                "/bin",
            ).filter { it.isNotBlank() }
        }

        fun resolve(): File? = ProcessRunner.resolve("kubectl", extraDirs)

        suspend fun exec(args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
            ProcessRunner.run(
                exe = resolve(),
                args = args,
                extraPathDirs = extraDirs,
                timeoutMs = timeoutMs,
                missingMessage = "The kubectl CLI was not found on this machine.",
                timeoutMessage = "kubectl ${args.firstOrNull().orEmpty()} timed out",
            ).let { Result(it.exitCode, it.stdout, it.stderr) }

        private const val DEFAULT_TIMEOUT_MS = 15_000L

        data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
            val ok: Boolean get() = exitCode == 0

            /**
             * stderr with kubectl's retry noise stripped. An unreachable cluster
             * emits a wall of `memcache.go` "Unhandled Error" lines that say the
             * same thing five times — never show those to a user.
             */
            val cleanError: String
                get() = stderr.lineSequence()
                    .filterNot { it.contains("memcache.go") }
                    .filterNot { it.startsWith("E0") && it.contains("Unhandled Error") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n")
                    .ifBlank { stdout.trim() }

            val unreachable: Boolean
                get() = !ok && UNREACHABLE_MARKERS.any { stderr.contains(it, ignoreCase = true) }

            val forbidden: Boolean
                get() = !ok && FORBIDDEN_MARKERS.any { stderr.contains(it, ignoreCase = true) }

            val badContext: Boolean
                get() = !ok && BAD_CONTEXT_MARKERS.any { stderr.contains(it, ignoreCase = true) }
        }
    }
}

/**
 * Decode one wire intent for [KubernetesStateHolder].
 *
 * Single-field intents take the payload as a bare string; multi-field intents
 * take a JSON object. An unknown [intentType], or a payload missing the fields
 * an intent needs, decodes to null so `PluginStateSyncService` drops it rather
 * than acting on a half-read message.
 *
 * There is deliberately no arm for delete / scale / rollout / apply / exec, and
 * none for port-forwards: those intents do not exist. See
 * [KubernetesStateHolder] for why.
 */
internal fun decodeKubernetesIntent(intentType: String, payload: String): KubernetesIntent? {
    val obj = runCatching { kubeIntentJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    fun str(key: String): String? = obj?.get(key)?.jsonPrimitive?.contentOrNull
    fun int(key: String): Int? = obj?.get(key)?.jsonPrimitive?.intOrNull

    return when (intentType) {
        "Refresh" -> KubernetesIntent.Refresh
        "RescanProject" -> KubernetesIntent.RescanProject
        "ClearSelection" -> KubernetesIntent.ClearSelection
        "ClearDetail" -> KubernetesIntent.ClearDetail

        "SetQuery" -> KubernetesIntent.SetQuery(payload)

        "SelectContext" -> payload.ifBlank { null }?.let { KubernetesIntent.SelectContext(it) }
        "SelectNamespace" -> payload.ifBlank { null }?.let { KubernetesIntent.SelectNamespace(it) }

        "ToggleSection" -> runCatching { KubeSection.valueOf(payload.trim().uppercase()) }
            .getOrNull()?.let { KubernetesIntent.ToggleSection(it) }

        "SelectResource" -> {
            val kind = str("kind") ?: return null
            val name = str("name") ?: return null
            KubernetesIntent.SelectResource(kind, name)
        }

        // Accepts either a bare pod name or {"podName":…,"container":…,"tail":…}.
        // A payload that *is* a JSON object never falls back to being read as a
        // name — `{}` would otherwise become a pod literally named "{}".
        "ShowLogs" -> {
            val podName = str("podName")
                ?: payload.takeIf { obj == null }?.ifBlank { null }
                ?: return null
            KubernetesIntent.ShowLogs(
                podName = podName,
                container = str("container").orEmpty(),
                tail = int("tail") ?: KubernetesIntent.DEFAULT_TAIL,
            )
        }

        "ShowDescribe" -> {
            val kind = str("kind") ?: return null
            val name = str("name") ?: return null
            KubernetesIntent.ShowDescribe(kind, name)
        }

        "ShowYaml" -> {
            val kind = str("kind") ?: return null
            val name = str("name") ?: return null
            KubernetesIntent.ShowYaml(kind, name)
        }

        else -> null
    }
}

private val kubeIntentJson = Json { ignoreUnknownKeys = true; isLenient = true }
