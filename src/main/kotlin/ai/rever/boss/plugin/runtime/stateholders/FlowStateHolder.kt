package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

// region State

/**
 * The flow-canvas surface, mirrored for a host-side renderer.
 *
 * Geometry is carried per node ([FlowNodeState.inputs] / [FlowNodeState.outputs])
 * rather than derived from a node-kind table on the host, so a renderer can lay
 * out ports and edges without knowing the plugin's kind catalog.
 *
 * [runAvailable] is always false — see [FlowStateHolder].
 */
@Serializable
data class FlowState(
    val nodes: List<FlowNodeState> = emptyList(),
    val edges: List<FlowEdgeState> = emptyList(),
    val selectedNodeId: String? = null,
    val selectedEdgeId: String? = null,
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val graphName: String = "",
    val lastError: String? = null,
    /**
     * True once the holder has published its first state. An empty canvas is a
     * legitimate graph, so this is what distinguishes "initialised, nothing on
     * it yet" from "no state has ever arrived".
     */
    val ready: Boolean = false,
    /** Always false out-of-process — no executor and no browser session here. */
    val runAvailable: Boolean = false,
    /** Always false out-of-process — the host's plugin storage is not on the wire. */
    val persistenceAvailable: Boolean = false,
)

/**
 * One node. [kindId] is the plugin's registry kind-id string (`"HTTP"`,
 * `"TRIGGER"`, `"agent"`, `"tool:boss:foo"`, …) — an opaque, stable identifier
 * kept verbatim so a graph round-trips through this holder unchanged.
 * [config] is the node's raw config JSON, also passed through untouched.
 */
@Serializable
data class FlowNodeState(
    val id: String,
    val kindId: String,
    val title: String,
    val x: Float,
    val y: Float,
    val inputs: Int = 1,
    val outputs: Int = 1,
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FlowEdgeState(
    val id: String,
    val fromNode: String,
    val fromPort: Int,
    val toNode: String,
    val toPort: Int,
)

// endregion

// region Intent

sealed class FlowIntent {
    /** Spawn a node of [kindId] with its top-left at ([x], [y]). */
    data class AddNode(val kindId: String, val x: Float, val y: Float) : FlowIntent()

    data class MoveNode(val nodeId: String, val x: Float, val y: Float) : FlowIntent()
    data class SetNodeTitle(val nodeId: String, val title: String) : FlowIntent()

    /** Replace a node's config with [configJson] (raw JSON object text). */
    data class SetNodeConfig(val nodeId: String, val configJson: String) : FlowIntent()

    data class DeleteNode(val nodeId: String) : FlowIntent()

    data class Connect(
        val fromNode: String,
        val fromPort: Int,
        val toNode: String,
        val toPort: Int,
    ) : FlowIntent()

    data class DeleteEdge(val edgeId: String) : FlowIntent()
    data class SelectNode(val nodeId: String) : FlowIntent()
    data class SelectEdge(val edgeId: String) : FlowIntent()
    object ClearSelection : FlowIntent()
    data class SetView(val scale: Float, val panX: Float, val panY: Float) : FlowIntent()
    object ResetView : FlowIntent()

    /** Replace the whole graph from a `GraphSnapshot` JSON document. */
    data class LoadSnapshot(val snapshotJson: String) : FlowIntent()

    object ClearGraph : FlowIntent()
    object ClearError : FlowIntent()
}

// endregion

/**
 * StateHolder for the Flow plugin — the **canvas** surface.
 *
 * The in-process plugin is a canvas *and* a workflow engine: `FlowExecutor`
 * drives HTTP nodes, a shared browser session (Open Browser / Navigate /
 * Click / Type / Extract / Inject), agent and MCP-tool nodes, and it persists
 * every graph through `PluginContext.pluginStorageFactory`. Two of those are
 * structurally out of reach from a child JVM:
 *
 * - **Running a flow is unavailable.** The node executors live in the
 *   plugin's own jar, and the browser nodes need
 *   `PluginContext.browserService`, which `RemotePluginContext` declares as
 *   `Nothing? = null`. [FlowState.runAvailable] is always false so the host
 *   renders no Run button rather than one that fails.
 * - **Persistence is unavailable.** The plugin reads and writes graphs via
 *   `pluginStorageFactory`, also `Nothing? = null` here, and the IPC contract
 *   has no plugin-storage service at all — so this holder can neither load
 *   the operator's saved flows nor save the graph it owns. The graph lives
 *   for the lifetime of the child process. [FlowState.persistenceAvailable]
 *   is always false, and [FlowIntent.LoadSnapshot] exists so a host that
 *   *does* hold a `GraphSnapshot` (from its own store, an import, or an MCP
 *   call) can push one in.
 *
 * What it does own is the graph itself, which is pure data: nodes with their
 * kind-id, title, position and raw config; edges between ports; the
 * selection; and the pan/zoom transform. Authoring intents mutate it with the
 * same rules the plugin's `FlowGraphState` applies — self-connections and
 * exact duplicate edges are rejected, deleting a node deletes its edges, and
 * ids are generated in the plugin's `n<N>` / `e<N>` form so a graph produced
 * here loads in the in-process plugin unchanged.
 *
 * Port counts for the built-in kinds are mirrored in [BUILTIN_PORTS]. That is
 * a deliberate, small duplication of metadata the plugin documents as stable
 * (built-in kind-ids are the legacy enum names, kept for persistence);
 * anything else — dynamic tool and agent kinds — defaults to one input and
 * one output, exactly like the plugin's own placeholder spec for an
 * unregistered kind.
 */
class FlowStateHolder : PluginStateHolder<FlowState, FlowIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(FlowStateHolder::class.java)

    /** Monotonic id counter, kept ahead of every id in the graph. */
    private var idCounter = 1L

    constructor(scope: CoroutineScope) : super(FlowState(), scope) {
        // Publish an initial versioned state so a host renderer has something to
        // apply on connect. A holder that never calls updateState stays at
        // version 0 and never renders at all (the bookmarks failure mode).
        updateState { copy(ready = true) }
    }

    constructor(scope: CoroutineScope, context: RemotePluginContext) : this(scope) {
        logger.info(
            "FlowStateHolder started (windowId={}, run/persistence unavailable out-of-process)",
            context.windowId,
        )
    }

    override fun onIntent(intent: FlowIntent) {
        when (intent) {
            is FlowIntent.AddNode -> {
                val node = FlowNodeState(
                    id = nextId("n"),
                    kindId = intent.kindId,
                    title = labelFor(intent.kindId),
                    x = intent.x,
                    y = intent.y,
                    inputs = portsFor(intent.kindId).first,
                    outputs = portsFor(intent.kindId).second,
                )
                updateState { copy(nodes = nodes + node, selectedNodeId = node.id, selectedEdgeId = null) }
            }

            is FlowIntent.MoveNode -> updateState {
                copy(
                    nodes = nodes.map {
                        if (it.id == intent.nodeId) it.copy(x = intent.x, y = intent.y) else it
                    },
                )
            }

            is FlowIntent.SetNodeTitle -> updateState {
                copy(
                    nodes = nodes.map {
                        if (it.id == intent.nodeId) it.copy(title = intent.title) else it
                    },
                )
            }

            is FlowIntent.SetNodeConfig -> {
                val parsed = runCatching {
                    json.parseToJsonElement(intent.configJson) as? JsonObject
                }.getOrNull()
                if (parsed == null) {
                    updateState { copy(lastError = "Node config must be a JSON object") }
                } else {
                    updateState {
                        copy(
                            nodes = nodes.map {
                                if (it.id == intent.nodeId) it.copy(config = parsed) else it
                            },
                            lastError = null,
                        )
                    }
                }
            }

            is FlowIntent.DeleteNode -> updateState {
                copy(
                    nodes = nodes.filter { it.id != intent.nodeId },
                    edges = edges.filter { it.fromNode != intent.nodeId && it.toNode != intent.nodeId },
                    selectedNodeId = selectedNodeId.takeIf { it != intent.nodeId },
                )
            }

            is FlowIntent.Connect -> connect(intent)

            is FlowIntent.DeleteEdge -> updateState {
                copy(
                    edges = edges.filter { it.id != intent.edgeId },
                    selectedEdgeId = selectedEdgeId.takeIf { it != intent.edgeId },
                )
            }

            is FlowIntent.SelectNode -> updateState {
                if (nodes.none { it.id == intent.nodeId }) {
                    this
                } else {
                    copy(selectedNodeId = intent.nodeId, selectedEdgeId = null)
                }
            }

            is FlowIntent.SelectEdge -> updateState {
                if (edges.none { it.id == intent.edgeId }) {
                    this
                } else {
                    copy(selectedEdgeId = intent.edgeId, selectedNodeId = null)
                }
            }

            is FlowIntent.ClearSelection -> updateState {
                copy(selectedNodeId = null, selectedEdgeId = null)
            }

            is FlowIntent.SetView -> updateState {
                copy(
                    scale = intent.scale.coerceIn(MIN_SCALE, MAX_SCALE),
                    panX = intent.panX,
                    panY = intent.panY,
                )
            }

            is FlowIntent.ResetView -> updateState { copy(scale = 1f, panX = 0f, panY = 0f) }

            is FlowIntent.LoadSnapshot -> loadSnapshot(intent.snapshotJson)

            is FlowIntent.ClearGraph -> {
                idCounter = 1L
                updateState {
                    copy(
                        nodes = emptyList(),
                        edges = emptyList(),
                        selectedNodeId = null,
                        selectedEdgeId = null,
                        graphName = "",
                    )
                }
            }

            is FlowIntent.ClearError -> updateState { copy(lastError = null) }
        }
    }

    /** Same validity rules as the plugin's `FlowGraphState.connect`. */
    private fun connect(intent: FlowIntent.Connect) {
        val state = currentState()
        val endpointsExist = state.nodes.any { it.id == intent.fromNode } &&
            state.nodes.any { it.id == intent.toNode }
        val duplicate = state.edges.any {
            it.fromNode == intent.fromNode && it.fromPort == intent.fromPort &&
                it.toNode == intent.toNode && it.toPort == intent.toPort
        }
        if (intent.fromNode == intent.toNode || !endpointsExist || duplicate) {
            logger.debug(
                "Rejected connection {}:{} -> {}:{}",
                intent.fromNode, intent.fromPort, intent.toNode, intent.toPort,
            )
            return
        }
        val edge = FlowEdgeState(
            id = nextId("e"),
            fromNode = intent.fromNode,
            fromPort = intent.fromPort,
            toNode = intent.toNode,
            toPort = intent.toPort,
        )
        updateState { copy(edges = edges + edge) }
    }

    private fun loadSnapshot(snapshotJson: String) {
        val snapshot = runCatching { json.decodeFromString(FlowGraphSnapshot.serializer(), snapshotJson) }
            .getOrElse { error ->
                logger.warn("Failed to parse graph snapshot: {}", error.message)
                updateState { copy(lastError = "Could not read graph: ${error.message}") }
                return
            }
        if (snapshot.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            updateState {
                copy(
                    lastError = "Graph schema v${snapshot.schemaVersion} is newer than this runtime " +
                        "understands (v$SUPPORTED_SCHEMA_VERSION)",
                )
            }
            return
        }

        val nodes = snapshot.nodes.map { node ->
            val ports = portsFor(node.type)
            FlowNodeState(
                id = node.id,
                kindId = node.type,
                title = node.title.ifBlank { labelFor(node.type) },
                x = node.x,
                y = node.y,
                inputs = ports.first,
                outputs = ports.second,
                config = node.config,
            )
        }
        val nodeIds = nodes.map { it.id }.toSet()
        // Drop edges whose endpoints are gone rather than rendering dangling wires.
        val edges = snapshot.edges.filter { it.fromNode in nodeIds && it.toNode in nodeIds }
            .map { FlowEdgeState(it.id, it.fromNode, it.fromPort, it.toNode, it.toPort) }

        val highest = (nodes.map { it.id } + edges.map { it.id })
            .mapNotNull { it.drop(1).toLongOrNull() }
            .maxOrNull() ?: 0L
        idCounter = maxOf(snapshot.nextId, highest + 1)

        updateState {
            copy(
                nodes = nodes,
                edges = edges,
                selectedNodeId = null,
                selectedEdgeId = null,
                graphName = snapshot.metadata?.name.orEmpty(),
                lastError = null,
            )
        }
        logger.info("Loaded graph: {} nodes, {} edges", nodes.size, edges.size)
    }

    private fun nextId(prefix: String): String = "$prefix${idCounter++}"

    private fun portsFor(kindId: String): Pair<Int, Int> = BUILTIN_PORTS[kindId] ?: (1 to 1)

    private fun labelFor(kindId: String): String = BUILTIN_LABELS[kindId] ?: kindId

    // region Snapshot wire shape

    /**
     * The plugin's `GraphSnapshot`, re-declared here because the runtime does not
     * depend on the plugin's jar. Field names and defaults match it exactly so a
     * document written by either side decodes on the other.
     */
    @Serializable
    private data class FlowGraphSnapshot(
        val nodes: List<SnapshotNode> = emptyList(),
        val edges: List<SnapshotEdge> = emptyList(),
        val nextId: Long = 1L,
        val schemaVersion: Int = 1,
        val metadata: SnapshotMeta? = null,
    )

    @Serializable
    private data class SnapshotNode(
        val id: String,
        val type: String,
        val title: String = "",
        val x: Float = 0f,
        val y: Float = 0f,
        val config: JsonObject = JsonObject(emptyMap()),
    )

    @Serializable
    private data class SnapshotEdge(
        val id: String,
        val fromNode: String,
        val fromPort: Int = 0,
        val toNode: String,
        val toPort: Int = 0,
    )

    @Serializable
    private data class SnapshotMeta(
        val name: String = "",
        val description: String = "",
        val version: Int = 1,
        val inputs: List<String> = emptyList(),
    )

    // endregion

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Mirrors the plugin's `FlowGraphState.MIN_SCALE` / `MAX_SCALE`. */
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 2.5f

        /** Mirrors the plugin's `SUPPORTED_SCHEMA_VERSION`. */
        const val SUPPORTED_SCHEMA_VERSION = 2

        /** (inputs, outputs) for the plugin's built-in kind-ids. */
        val BUILTIN_PORTS = mapOf(
            "TRIGGER" to (0 to 1),
            "OPEN_BROWSER" to (1 to 1),
            "NAVIGATE" to (1 to 1),
            "CLICK" to (1 to 1),
            "TYPE" to (1 to 1),
            "EXTRACT" to (1 to 1),
            "INJECT" to (1 to 1),
            "HTTP" to (1 to 1),
            "SET" to (1 to 1),
            "CODE" to (1 to 1),
            "IF" to (1 to 2),
            "MERGE" to (2 to 1),
        )

        val BUILTIN_LABELS = mapOf(
            "TRIGGER" to "Trigger",
            "OPEN_BROWSER" to "Open Browser",
            "NAVIGATE" to "Navigate",
            "CLICK" to "Click",
            "TYPE" to "Type",
            "EXTRACT" to "Extract",
            "INJECT" to "Inject",
            "HTTP" to "HTTP Request",
            "SET" to "Set",
            "CODE" to "Code",
            "IF" to "If",
            "MERGE" to "Merge",
        )
    }
}
