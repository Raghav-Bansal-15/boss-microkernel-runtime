package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [FlowStateHolder] — the flow-canvas surface an out-of-process child
 * publishes to a host renderer.
 *
 * The graph mutation rules deliberately mirror the plugin's own `FlowGraphState`,
 * and the ids it generates are in the plugin's `n<N>` / `e<N>` form, so a graph
 * authored out-of-process loads in-process unchanged. Those two properties are
 * what most of these tests pin.
 */
class FlowStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun holder() = FlowStateHolder(scope)

    private fun FlowStateHolder.addNode(kindId: String, x: Float = 0f, y: Float = 0f) =
        onIntent(FlowIntent.AddNode(kindId, x, y))

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0, and the kernel bridge's strictly-newer
     * guard means a version-0 envelope never applies host-side (the `bookmarks`
     * gap). An empty canvas is a legitimate graph, so [FlowState.ready] — not a
     * non-empty node list — is what proves the holder published.
     */
    @Test
    fun `publishes a versioned empty canvas with no prompting`() {
        val holder = holder()

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        val state = holder.currentState()
        assertTrue(state.ready)
        assertEquals(emptyList(), state.nodes)
        assertEquals(emptyList(), state.edges)
        assertEquals(1f, state.scale)
    }

    /** Honest capability reporting, so the host renders no dead Run/Save. */
    @Test
    fun `reports running and persistence as unavailable`() {
        val state = holder().currentState()

        assertFalse(state.runAvailable, "the node executors live in the plugin jar")
        assertFalse(
            state.persistenceAvailable,
            "pluginStorageFactory is null OOP and the wire has no storage service",
        )
    }

    // endregion

    // region nodes

    @Test
    fun `adds a node with the plugin's id form label and port counts`() {
        val holder = holder()

        holder.addNode("TRIGGER", x = 10f, y = 20f)

        val node = holder.currentState().nodes.single()
        assertEquals("n1", node.id, "ids must match the plugin's n<N> form")
        assertEquals("TRIGGER", node.kindId)
        assertEquals("Trigger", node.title)
        assertEquals(10f, node.x)
        assertEquals(20f, node.y)
        assertEquals(0, node.inputs, "TRIGGER starts a workflow, so it has no inputs")
        assertEquals(1, node.outputs)
        assertEquals("n1", holder.currentState().selectedNodeId)
    }

    @Test
    fun `mirrors the multi-port built-ins`() {
        val holder = holder()

        holder.addNode("IF")
        holder.addNode("MERGE")

        val nodes = holder.currentState().nodes
        assertEquals(1 to 2, nodes[0].inputs to nodes[0].outputs)
        assertEquals(2 to 1, nodes[1].inputs to nodes[1].outputs)
    }

    /**
     * Dynamic kinds (registry tools, agents) are not in the built-in table. They
     * keep their kind-id verbatim and get the plugin's own placeholder geometry
     * rather than being dropped.
     */
    @Test
    fun `an unknown kind keeps its id and gets placeholder geometry`() {
        val holder = holder()

        holder.addNode("tool:boss:some_tool")

        val node = holder.currentState().nodes.single()
        assertEquals("tool:boss:some_tool", node.kindId)
        assertEquals("tool:boss:some_tool", node.title)
        assertEquals(1 to 1, node.inputs to node.outputs)
    }

    @Test
    fun `node ids keep advancing so a deleted id is never reused`() {
        val holder = holder()
        holder.addNode("HTTP")
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.DeleteNode("n1"))
        holder.addNode("HTTP")

        assertEquals(listOf("n2", "n3"), holder.currentState().nodes.map { it.id })
    }

    @Test
    fun `moving a node updates only its position`() {
        val holder = holder()
        holder.addNode("HTTP", x = 0f, y = 0f)

        holder.onIntent(FlowIntent.MoveNode("n1", 120f, 240f))

        val node = holder.currentState().nodes.single()
        assertEquals(120f to 240f, node.x to node.y)
        assertEquals("HTTP", node.kindId)
    }

    @Test
    fun `retitles a node`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.SetNodeTitle("n1", "Fetch users"))

        assertEquals("Fetch users", holder.currentState().nodes.single().title)
    }

    @Test
    fun `sets a node config from raw json`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.SetNodeConfig("n1", """{"method":"POST","url":"https://x"}"""))

        val config = holder.currentState().nodes.single().config
        assertEquals("POST", config["method"]?.toString()?.trim('"'))
        assertNull(holder.currentState().lastError)
    }

    @Test
    fun `a non-object node config is refused with an error`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.SetNodeConfig("n1", "[1,2,3]"))

        assertEquals("Node config must be a JSON object", holder.currentState().lastError)
        assertTrue(holder.currentState().nodes.single().config.isEmpty())
    }

    @Test
    fun `deleting a node deletes its edges and clears its selection`() {
        val holder = holder()
        holder.addNode("TRIGGER")
        holder.addNode("HTTP")
        holder.addNode("SET")
        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))
        holder.onIntent(FlowIntent.Connect("n2", 0, "n3", 0))
        holder.onIntent(FlowIntent.SelectNode("n2"))

        holder.onIntent(FlowIntent.DeleteNode("n2"))

        assertEquals(listOf("n1", "n3"), holder.currentState().nodes.map { it.id })
        assertEquals(emptyList(), holder.currentState().edges, "both incident edges must go")
        assertNull(holder.currentState().selectedNodeId)
    }

    // endregion

    // region edges

    @Test
    fun `connects two nodes with an id from the shared counter`() {
        val holder = holder()
        holder.addNode("TRIGGER")
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))

        val edge = holder.currentState().edges.single()
        // n1, n2 then e3 — nodes and edges share one counter, as in the plugin.
        assertEquals("e3", edge.id)
        assertEquals("n1", edge.fromNode)
        assertEquals("n2", edge.toNode)
    }

    @Test
    fun `refuses a self connection`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.Connect("n1", 0, "n1", 0))

        assertEquals(emptyList(), holder.currentState().edges)
    }

    @Test
    fun `refuses an exact duplicate edge but allows a different port pair`() {
        val holder = holder()
        holder.addNode("IF")
        holder.addNode("MERGE")
        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))

        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))
        assertEquals(1, holder.currentState().edges.size, "duplicates are rejected")

        holder.onIntent(FlowIntent.Connect("n1", 1, "n2", 1))
        assertEquals(2, holder.currentState().edges.size, "a different port pair is a new edge")
    }

    @Test
    fun `refuses an edge to a node that does not exist`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.Connect("n1", 0, "ghost", 0))
        holder.onIntent(FlowIntent.Connect("ghost", 0, "n1", 0))

        assertEquals(emptyList(), holder.currentState().edges)
    }

    @Test
    fun `deletes an edge and clears its selection`() {
        val holder = holder()
        holder.addNode("TRIGGER")
        holder.addNode("HTTP")
        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))
        holder.onIntent(FlowIntent.SelectEdge("e3"))

        holder.onIntent(FlowIntent.DeleteEdge("e3"))

        assertEquals(emptyList(), holder.currentState().edges)
        assertNull(holder.currentState().selectedEdgeId)
    }

    // endregion

    // region selection and view

    @Test
    fun `selecting a node clears an edge selection and vice versa`() {
        val holder = holder()
        holder.addNode("TRIGGER")
        holder.addNode("HTTP")
        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))

        holder.onIntent(FlowIntent.SelectEdge("e3"))
        assertEquals("e3", holder.currentState().selectedEdgeId)
        assertNull(holder.currentState().selectedNodeId)

        holder.onIntent(FlowIntent.SelectNode("n1"))
        assertEquals("n1", holder.currentState().selectedNodeId)
        assertNull(holder.currentState().selectedEdgeId)

        holder.onIntent(FlowIntent.ClearSelection)
        assertNull(holder.currentState().selectedNodeId)
        assertNull(holder.currentState().selectedEdgeId)
    }

    @Test
    fun `selecting something that does not exist changes nothing`() {
        val holder = holder()
        holder.addNode("HTTP")
        holder.onIntent(FlowIntent.SelectNode("n1"))

        holder.onIntent(FlowIntent.SelectNode("ghost"))
        holder.onIntent(FlowIntent.SelectEdge("ghost"))

        assertEquals("n1", holder.currentState().selectedNodeId)
        assertNull(holder.currentState().selectedEdgeId)
    }

    /** Mirrors the plugin's MIN_SCALE / MAX_SCALE so the host cannot desync zoom. */
    @Test
    fun `clamps the view scale to the plugin's range`() {
        val holder = holder()

        holder.onIntent(FlowIntent.SetView(99f, 5f, 6f))
        assertEquals(2.5f, holder.currentState().scale)
        assertEquals(5f to 6f, holder.currentState().panX to holder.currentState().panY)

        holder.onIntent(FlowIntent.SetView(0.001f, 0f, 0f))
        assertEquals(0.2f, holder.currentState().scale)
    }

    @Test
    fun `resetting the view restores the identity transform`() {
        val holder = holder()
        holder.onIntent(FlowIntent.SetView(2f, 40f, 50f))

        holder.onIntent(FlowIntent.ResetView)

        val state = holder.currentState()
        assertEquals(1f, state.scale)
        assertEquals(0f to 0f, state.panX to state.panY)
    }

    // endregion

    // region snapshots

    @Test
    fun `loads a pushed graph snapshot`() {
        val holder = holder()

        holder.onIntent(FlowIntent.LoadSnapshot(SNAPSHOT))

        val state = holder.currentState()
        assertNull(state.lastError)
        assertEquals(listOf("n7", "n8", "n9"), state.nodes.map { it.id })
        assertEquals("Imported", state.graphName)
        assertEquals("Start", state.nodes[0].title)
        assertEquals(2, state.nodes[1].outputs, "IF's geometry comes from the built-in table")
        assertEquals("tool:boss:unknown", state.nodes[2].kindId)
        assertNull(state.selectedNodeId, "a load clears the selection")
    }

    /** A wire to a node that is not in the snapshot cannot be rendered. */
    @Test
    fun `drops edges whose endpoints are missing`() {
        val holder = holder()

        holder.onIntent(FlowIntent.LoadSnapshot(SNAPSHOT))

        assertEquals(listOf("e10"), holder.currentState().edges.map { it.id })
    }

    /**
     * The id counter must clear every id the snapshot brought in, or the next
     * authored node collides with a loaded one.
     */
    @Test
    fun `keeps the id counter ahead of every loaded id`() {
        val holder = holder()
        holder.onIntent(FlowIntent.LoadSnapshot(SNAPSHOT))

        holder.addNode("HTTP")

        assertEquals("n12", holder.currentState().nodes.last().id)
    }

    @Test
    fun `keeps the id counter ahead even when nextId lags behind the ids`() {
        val holder = holder()
        val stale = """{"nodes":[{"id":"n40","type":"HTTP"}],"edges":[],"nextId":2}"""

        holder.onIntent(FlowIntent.LoadSnapshot(stale))
        holder.addNode("HTTP")

        assertEquals("n41", holder.currentState().nodes.last().id)
    }

    @Test
    fun `refuses a snapshot from a newer schema and keeps the current graph`() {
        val holder = holder()
        holder.onIntent(FlowIntent.LoadSnapshot(SNAPSHOT))

        holder.onIntent(FlowIntent.LoadSnapshot("""{"nodes":[],"edges":[],"schemaVersion":99}"""))

        val state = holder.currentState()
        val error = assertNotNull(state.lastError)
        assertTrue(error.contains("newer than this runtime"), error)
        assertEquals(3, state.nodes.size, "a refused load must not clear the graph")
    }

    @Test
    fun `accepts a legacy snapshot with no schema version`() {
        val holder = holder()

        holder.onIntent(FlowIntent.LoadSnapshot("""{"nodes":[{"id":"n1","type":"HTTP"}],"edges":[]}"""))

        assertNull(holder.currentState().lastError)
        assertEquals(1, holder.currentState().nodes.size)
    }

    @Test
    fun `reports an unparseable snapshot and keeps the current graph`() {
        val holder = holder()
        holder.addNode("HTTP")

        holder.onIntent(FlowIntent.LoadSnapshot("{not json"))

        assertNotNull(holder.currentState().lastError)
        assertEquals(1, holder.currentState().nodes.size)
    }

    @Test
    fun `clearing the graph empties it and restarts the id counter`() {
        val holder = holder()
        holder.addNode("TRIGGER")
        holder.addNode("HTTP")
        holder.onIntent(FlowIntent.Connect("n1", 0, "n2", 0))

        holder.onIntent(FlowIntent.ClearGraph)

        val state = holder.currentState()
        assertEquals(emptyList(), state.nodes)
        assertEquals(emptyList(), state.edges)
        assertEquals("", state.graphName)
        holder.addNode("HTTP")
        assertEquals("n1", holder.currentState().nodes.single().id)
    }

    @Test
    fun `clearing the error leaves the graph alone`() {
        val holder = holder()
        holder.addNode("HTTP")
        holder.onIntent(FlowIntent.SetNodeConfig("n1", "not an object"))
        assertNotNull(holder.currentState().lastError)

        holder.onIntent(FlowIntent.ClearError)

        assertNull(holder.currentState().lastError)
        assertEquals(1, holder.currentState().nodes.size)
    }

    // endregion

    // region intent decoding

    @Test
    fun `decodes single-field intents from a bare string payload`() {
        assertEquals(FlowIntent.DeleteNode("n1"), decodeFlowIntent("DeleteNode", "n1"))
        assertEquals(FlowIntent.DeleteEdge("e3"), decodeFlowIntent("DeleteEdge", "e3"))
        assertEquals(FlowIntent.SelectNode("n1"), decodeFlowIntent("SelectNode", "n1"))
        assertEquals(FlowIntent.SelectEdge("e3"), decodeFlowIntent("SelectEdge", "e3"))
        assertEquals(FlowIntent.LoadSnapshot("{}"), decodeFlowIntent("LoadSnapshot", "{}"))
        assertEquals(FlowIntent.ClearSelection, decodeFlowIntent("ClearSelection", ""))
        assertEquals(FlowIntent.ResetView, decodeFlowIntent("ResetView", ""))
        assertEquals(FlowIntent.ClearGraph, decodeFlowIntent("ClearGraph", ""))
        assertEquals(FlowIntent.ClearError, decodeFlowIntent("ClearError", ""))
    }

    @Test
    fun `decodes multi-field intents from a json payload`() {
        assertEquals(
            FlowIntent.AddNode("HTTP", 10f, 20f),
            decodeFlowIntent("AddNode", """{"kindId":"HTTP","x":10,"y":20}"""),
        )
        assertEquals(
            FlowIntent.AddNode("HTTP", 0f, 0f),
            decodeFlowIntent("AddNode", """{"kindId":"HTTP"}"""),
            "a position-less AddNode drops the node at the origin",
        )
        assertEquals(
            FlowIntent.MoveNode("n1", 1.5f, 2.5f),
            decodeFlowIntent("MoveNode", """{"nodeId":"n1","x":1.5,"y":2.5}"""),
        )
        assertEquals(
            FlowIntent.SetNodeTitle("n1", "Fetch"),
            decodeFlowIntent("SetNodeTitle", """{"nodeId":"n1","title":"Fetch"}"""),
        )
        assertEquals(
            FlowIntent.Connect("n1", 0, "n2", 1),
            decodeFlowIntent(
                "Connect",
                """{"fromNode":"n1","fromPort":0,"toNode":"n2","toPort":1}""",
            ),
        )
        assertEquals(
            FlowIntent.SetView(2f, 3f, 4f),
            decodeFlowIntent("SetView", """{"scale":2,"panX":3,"panY":4}"""),
        )
    }

    @Test
    fun `a decoded node config is re-serialized as a json object`() {
        val intent = decodeFlowIntent(
            "SetNodeConfig",
            """{"nodeId":"n1","config":{"method":"POST"}}""",
        )

        assertEquals(FlowIntent.SetNodeConfig("n1", """{"method":"POST"}"""), intent)
    }

    /**
     * A dropped intent is recoverable; an intent applied from a half-read message
     * silently mutates the wrong node. So anything malformed must decode to null.
     */
    @Test
    fun `refuses unknown types blank payloads and incomplete json`() {
        assertNull(decodeFlowIntent("NoSuchIntent", "whatever"))
        assertNull(decodeFlowIntent("DeleteNode", ""))
        assertNull(decodeFlowIntent("SelectNode", ""))
        assertNull(decodeFlowIntent("LoadSnapshot", ""))
        assertNull(decodeFlowIntent("AddNode", """{"x":1,"y":2}"""))
        assertNull(decodeFlowIntent("AddNode", "not json"))
        assertNull(decodeFlowIntent("MoveNode", """{"nodeId":"n1","x":1}"""))
        assertNull(decodeFlowIntent("Connect", """{"fromNode":"n1","fromPort":0}"""))
        assertNull(decodeFlowIntent("SetNodeConfig", """{"nodeId":"n1"}"""))
        assertNull(decodeFlowIntent("SetView", """{"panX":1,"panY":2}"""))
    }

    // endregion

    private companion object {
        val SNAPSHOT = """
            {
             "nodes": [
              {"id": "n7", "type": "TRIGGER", "title": "Start", "x": 0.0, "y": 0.0},
              {"id": "n8", "type": "IF", "title": "Branch", "x": 320.0, "y": 0.0},
              {"id": "n9", "type": "tool:boss:unknown", "title": "Tool", "x": 640.0, "y": 0.0}
             ],
             "edges": [
              {"id": "e10", "fromNode": "n7", "fromPort": 0, "toNode": "n8", "toPort": 0},
              {"id": "e11", "fromNode": "n8", "fromPort": 1, "toNode": "gone", "toPort": 0}
             ],
             "nextId": 12,
             "schemaVersion": 2,
             "metadata": {"name": "Imported"}
            }
        """.trimIndent()
    }
}
