package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.AfterTest
import ai.rever.boss.plugin.runtime.StateWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AtlasStateHolder] — the chat surface an out-of-process child
 * publishes to a host renderer.
 *
 * The `claude` turn itself is exercised in `boss-jvm-host`'s `real_plugin_e2e`
 * sweep against a **stub** binary via `CLAUDE_CLI_PATH`; a JVM cannot change its
 * own environment, so a unit test here could only reach the operator's real
 * Claude Code install and make a real model call, which it must not.
 */
class AtlasStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0 and never renders (the `bookmarks` gap).
     * An empty transcript is a legitimate starting point, so [AtlasState.ready]
     * is what proves the holder published.
     */
    @Test
    fun `publishes a versioned state with an empty transcript`() {
        val holder = AtlasStateHolder(scope)

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        val state = holder.currentState()
        assertTrue(state.ready)
        assertEquals(emptyList(), state.messages)
    }

    /**
     * The honest headline: out-of-process this is a plain Claude chat, not a
     * page-aware one. A renderer that shows the "reading this page" chip anyway
     * would be lying to the user.
     */
    @Test
    fun `reports page context as unavailable with a reason`() {
        val state = AtlasStateHolder(scope).currentState()

        assertFalse(state.pageContextAvailable)
        assertEquals(AtlasStateHolder.PAGE_CONTEXT_REASON, state.pageContextReason)
        assertTrue(state.pageContextReason.isNotBlank(), "an unavailable capability must say why")
    }

    /** Atlas has no persistence in-process either, so this matches, not degrades. */
    @Test
    fun `reports persistence as unavailable`() {
        assertFalse(AtlasStateHolder(scope).currentState().persistenceAvailable)
    }

    /** The default agent is always offered first, and is the initial selection. */
    @Test
    fun `offers the default agent first`() {
        val state = AtlasStateHolder(scope).currentState()

        assertEquals(AtlasStateHolder.DEFAULT_AGENT, state.agents.first())
        assertEquals("", state.selectedAgent, "the empty name means no --agent flag")
    }

    @Test
    fun `starts read only`() {
        val state = AtlasStateHolder(scope).currentState()

        assertEquals(AtlasPermissionMode.READ_ONLY, state.permissionMode)
        assertEquals("", state.permissionMode.cliValue, "read-only passes no --permission-mode")
    }

    // endregion

    // region local intents

    @Test
    fun `setting the permission mode is mirrored`() {
        val holder = AtlasStateHolder(scope)

        holder.onIntent(AtlasIntent.SetPermissionMode(AtlasPermissionMode.ALLOW_WRITES))

        assertEquals(AtlasPermissionMode.ALLOW_WRITES, holder.currentState().permissionMode)
        assertEquals("acceptEdits", holder.currentState().permissionMode.cliValue)
    }

    /** Selecting an agent the scan never found would produce a `--agent` that fails. */
    @Test
    fun `selecting an unknown agent is ignored`() {
        val holder = AtlasStateHolder(scope)

        holder.onIntent(AtlasIntent.SelectAgent("no-such-agent"))

        assertEquals("", holder.currentState().selectedAgent)
    }

    @Test
    fun `clearing the conversation empties the transcript`() {
        val holder = AtlasStateHolder(scope)
        holder.onIntent(AtlasIntent.Send("hello"))

        holder.onIntent(AtlasIntent.ClearConversation)

        assertEquals(emptyList(), holder.currentState().messages)
        assertFalse(holder.currentState().sending)
    }

    /**
     * A blank send must not append an empty pair of bubbles, which would render
     * as a permanently streaming assistant message with nothing in it.
     */
    @Test
    fun `ignores a blank send`() {
        val holder = AtlasStateHolder(scope)

        holder.onIntent(AtlasIntent.Send("   "))

        assertEquals(emptyList(), holder.currentState().messages)
    }

    /**
     * With no `claude` on this machine the turn must fail visibly on the
     * assistant bubble, not hang with `sending` stuck true. (When a real `claude`
     * *is* installed the turn is spawned instead, which is the e2e test's job —
     * so this asserts only the branch that applies.)
     */
    @Test
    fun `a send with no backend fails the assistant message rather than hanging`() {
        val holder = AtlasStateHolder(scope)
        if (holder.currentState().backendAvailable) return

        holder.onIntent(AtlasIntent.Send("hello"))

        val state = holder.currentState()
        assertEquals(2, state.messages.size, "the user turn and an assistant bubble: ${state.messages}")
        assertEquals(AtlasRole.USER, state.messages[0].role)
        assertEquals("hello", state.messages[0].text)
        val assistant = state.messages[1]
        assertFalse(assistant.streaming, "a failed turn must stop streaming")
        assertNotNull(assistant.error)
        assertFalse(state.sending)
    }

    @Test
    fun `switching the working directory resets the agent selection`() {
        val holder = AtlasStateHolder(scope)
        val dir = createTempDir("atlas-wd")
        try {
            holder.onIntent(AtlasIntent.SetWorkingDir(dir.absolutePath))

            assertEquals(dir.absolutePath, holder.currentState().workingDir)
            assertEquals("", holder.currentState().selectedAgent)
        } finally {
            dir.deleteRecursively()
        }
    }

    // endregion

    // region agent discovery

    /** Project agents win over user agents on a name clash, default always first. */
    @Test
    fun `discovers project agents with their frontmatter`() {
        val project = createTempDir("atlas-project")
        try {
            val agents = File(project, ".claude/agents").apply { mkdirs() }
            File(agents, "reviewer.md").writeText(
                """
                ---
                name: reviewer
                description: Reviews a diff
                ---
                You review diffs.
                """.trimIndent(),
            )
            val holder = AtlasStateHolder(scope)
            holder.onIntent(AtlasIntent.SetWorkingDir(project.absolutePath))

            val found = holder.currentState().agents
            assertEquals(AtlasStateHolder.DEFAULT_AGENT, found.first())
            val reviewer = found.single { it.name == "reviewer" }
            assertEquals("Reviews a diff", reviewer.description)
            assertEquals(AtlasAgentSource.PROJECT, reviewer.source)
        } finally {
            project.deleteRecursively()
        }
    }

    /** A file with no frontmatter is still a valid agent; its name is the file name. */
    @Test
    fun `falls back to the file name when frontmatter names nothing`() {
        val project = createTempDir("atlas-noname")
        try {
            val agents = File(project, ".claude/agents").apply { mkdirs() }
            File(agents, "plain-helper.md").writeText("Just a body, no frontmatter.\n")
            val holder = AtlasStateHolder(scope)
            holder.onIntent(AtlasIntent.SetWorkingDir(project.absolutePath))

            val helper = holder.currentState().agents.single { it.name == "plain-helper" }
            assertEquals("", helper.description)
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `reads quoted frontmatter values without their quotes`() {
        val file = createTempFile("agent", ".md")
        try {
            file.writeText("---\nname: \"quoted\"\ndescription: 'single'\n---\nbody\n")

            val front = AtlasStateHolder.readFrontmatter(file)

            assertEquals("quoted", front?.get("name"))
            assertEquals("single", front?.get("description"))
        } finally {
            file.delete()
        }
    }

    /** An unterminated frontmatter block must not swallow the whole file as keys. */
    @Test
    fun `treats unterminated frontmatter as none`() {
        val file = createTempFile("agent-open", ".md")
        try {
            file.writeText("---\nname: never-closed\nstill going\n")

            assertEquals(emptyMap(), AtlasStateHolder.readFrontmatter(file))
        } finally {
            file.delete()
        }
    }

    // endregion

    // region stream-json parsing

    @Test
    fun `reads the session id off the init line`() {
        val event = AtlasStateHolder.parseStreamLine(
            """{"type":"system","subtype":"init","session_id":"sess-42"}""",
        )

        assertEquals(AtlasStateHolder.StreamEvent.Init("sess-42"), event)
    }

    @Test
    fun `reads a text delta`() {
        val event = AtlasStateHolder.parseStreamLine(
            """{"type":"stream_event","event":{"type":"content_block_delta",
               "delta":{"type":"text_delta","text":"Hel"}}}""",
        )

        assertEquals(AtlasStateHolder.StreamEvent.Text("Hel"), event)
    }

    /** Thinking must land in its own field, never in the answer body. */
    @Test
    fun `reads a thinking delta from its own key`() {
        val event = AtlasStateHolder.parseStreamLine(
            """{"type":"stream_event","event":{"type":"content_block_delta",
               "delta":{"type":"thinking_delta","thinking":"hmm"}}}""",
        )

        assertEquals(AtlasStateHolder.StreamEvent.Thinking("hmm"), event)
    }

    @Test
    fun `reads a successful result as done with its session id`() {
        val event = AtlasStateHolder.parseStreamLine(
            """{"type":"result","subtype":"success","session_id":"sess-9","result":"ok"}""",
        )

        assertEquals(AtlasStateHolder.StreamEvent.Done("sess-9"), event)
    }

    @Test
    fun `reads an error result as a failure carrying its message`() {
        val event = AtlasStateHolder.parseStreamLine(
            """{"type":"result","subtype":"error_during_execution","result":"boom"}""",
        )

        assertEquals(AtlasStateHolder.StreamEvent.Failed("boom"), event)
    }

    /** Lines the CLI emits that are not events at all must be skipped, not crash. */
    @Test
    fun `ignores lines that are not events`() {
        assertNull(AtlasStateHolder.parseStreamLine(""))
        assertNull(AtlasStateHolder.parseStreamLine("not json"))
        assertNull(AtlasStateHolder.parseStreamLine("""{"type":"assistant","message":{}}"""))
        assertNull(AtlasStateHolder.parseStreamLine("""{"type":"stream_event","event":{"type":"message_start"}}"""))
    }

    /** An empty delta is not a delta; appending it would publish a no-op envelope. */
    @Test
    fun `ignores an empty text delta`() {
        assertNull(
            AtlasStateHolder.parseStreamLine(
                """{"type":"stream_event","event":{"type":"content_block_delta",
                   "delta":{"type":"text_delta","text":""}}}""",
            ),
        )
    }

    // endregion

    // region intent decoding

    @Test
    fun `decodes the nullary intents`() {
        assertEquals(AtlasIntent.Cancel, decodeAtlasIntent("Cancel", ""))
        assertEquals(AtlasIntent.RefreshAgents, decodeAtlasIntent("RefreshAgents", ""))
        assertEquals(AtlasIntent.PickDirectory, decodeAtlasIntent("PickDirectory", ""))
        assertEquals(AtlasIntent.ClearConversation, decodeAtlasIntent("ClearConversation", ""))
    }

    @Test
    fun `decodes send and select agent from a bare payload`() {
        assertEquals(AtlasIntent.Send("hi"), decodeAtlasIntent("Send", "hi"))
        assertEquals(AtlasIntent.SelectAgent("reviewer"), decodeAtlasIntent("SelectAgent", "reviewer"))
        assertEquals(AtlasIntent.SelectAgent(""), decodeAtlasIntent("SelectAgent", ""))
    }

    @Test
    fun `drops a blank send`() {
        assertNull(decodeAtlasIntent("Send", ""))
        assertNull(decodeAtlasIntent("Send", "   "))
    }

    /**
     * A blank working directory is a meaningful value — run in the host's default
     * directory — so unlike the other single-field intents it must not be dropped.
     */
    @Test
    fun `decodes a blank working directory as no project`() {
        assertEquals(AtlasIntent.SetWorkingDir(null), decodeAtlasIntent("SetWorkingDir", ""))
        assertEquals(AtlasIntent.SetWorkingDir("/tmp/x"), decodeAtlasIntent("SetWorkingDir", "/tmp/x"))
    }

    @Test
    fun `decodes a permission mode case insensitively and drops an unknown one`() {
        assertEquals(
            AtlasIntent.SetPermissionMode(AtlasPermissionMode.ALLOW_WRITES),
            decodeAtlasIntent("SetPermissionMode", "allow_writes"),
        )
        assertNull(decodeAtlasIntent("SetPermissionMode", "yolo"))
    }

    @Test
    fun `drops an unknown intent type`() {
        assertNull(decodeAtlasIntent("CapturePage", ""))
        assertNull(decodeAtlasIntent("", ""))
    }

    // endregion

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun createTempFile(prefix: String, suffix: String): File =
        java.nio.file.Files.createTempFile(prefix, suffix).toFile()

    /**
     * The enum's own `label`/`description` never reach the wire - kotlinx encodes an enum as its
     * serial name alone - so the catalog is the only thing that carries them. The sibling test
     * for data-class derived fields existed; enums slipped through the same net.
     */
    @Test
    fun `permission mode labels reach the wire, not just the enum name`() {
        val json = StateWireJson.encodeToString(AtlasState.serializer(), AtlasState())

        assertTrue(json.contains("\"permissionMode\":\"READ_ONLY\""), "the selection is the name: $json")
        assertTrue(json.contains("\"Read-only\""), "the label must travel in the catalog: $json")
        assertTrue(json.contains("\"Allow writes\""), json)
        assertTrue(json.contains("Cannot write files."), "the description must travel too: $json")
    }

}
