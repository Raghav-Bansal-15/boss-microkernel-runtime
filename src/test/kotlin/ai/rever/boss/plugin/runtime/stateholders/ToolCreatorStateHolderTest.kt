package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.stateholders.ToolCreatorStateHolder.Companion.derive
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
 * Tests for [ToolCreatorStateHolder] — the scaffold surface an out-of-process
 * child publishes to a host renderer.
 *
 * The scaffold itself needs the tool-creator jar's bundled templates on the
 * classloader, which only happens in a real child JVM
 * (`java -cp runtime-all.jar:plugin.jar`). So the writing half is exercised in
 * `boss-jvm-host`'s `real_plugin_e2e` sweep; what is pinned here is the name
 * derivation, the validation rules, the template tokens, and the refusal that
 * fires when the templates are absent — which is the situation in this suite.
 */
class ToolCreatorStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0 and never renders (the `bookmarks` gap).
     */
    @Test
    fun `publishes a versioned state on construction`() {
        val holder = ToolCreatorStateHolder(scope)

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        assertTrue(holder.currentState().ready)
    }

    @Test
    fun `reports terminal handoff github and persistence as unavailable`() {
        val state = ToolCreatorStateHolder(scope).currentState()

        assertFalse(state.terminalHandoffAvailable, "openTab is an inherited no-op on the IPC proxy")
        assertFalse(state.githubAvailable, "pluginStoreApiKeyProvider is null out-of-process")
        assertFalse(state.persistenceAvailable, "the wire has no plugin-storage service")
    }

    /**
     * The runtime's own test classpath has no tool-creator jar on it, so the
     * templates are absent — which is exactly the condition
     * [ToolCreatorState.scaffoldAvailable] exists to report.
     */
    @Test
    fun `reports scaffolding as unavailable without the plugin jar`() {
        assertFalse(ToolCreatorStateHolder(scope).currentState().scaffoldAvailable)
    }

    /** …and refuses rather than writing a half-scaffold. */
    @Test
    fun `refuses to build when the templates are absent`() {
        val holder = ToolCreatorStateHolder(scope)
        val parent = tempDir("tc-refuse")
        try {
            holder.onIntent(ToolCreatorIntent.SetToolName("Invoice Extractor"))
            holder.onIntent(ToolCreatorIntent.SetDescription("Pulls totals out of invoices"))
            holder.onIntent(ToolCreatorIntent.SetParentDir(parent.absolutePath))
            assertNull(holder.currentState().form.error, "the form itself is valid")

            holder.onIntent(ToolCreatorIntent.StartBuild)

            assertEquals(emptyList(), holder.currentState().jobs, "no job may start")
            assertNotNull(holder.currentState().form.error)
            assertEquals(
                emptyList(),
                parent.listFiles()?.toList() ?: emptyList(),
                "nothing may be written to disk",
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    /** The environment probe must actually run, or the banner never resolves. */
    @Test
    fun `probes the environment on construction`() {
        val holder = ToolCreatorStateHolder(scope)

        // The probe is launched from the constructor; give it a moment to land.
        val deadline = System.currentTimeMillis() + PROBE_WAIT_MS
        while (!holder.currentState().environment.checked && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }

        assertTrue(holder.currentState().environment.checked, "the environment probe never completed")
    }

    // endregion

    // region name derivation

    /**
     * Every derived name flows from the tool name, and must match the plugin's
     * `ScaffoldSpec` exactly — a scaffold whose package and class prefix disagree
     * with the in-process rules produces a repo that does not build.
     */
    @Test
    fun `derives every name from the tool name`() {
        val form = ToolCreatorForm(toolName = "Invoice Extractor", parentDir = "/work").derive()

        assertEquals("invoice-extractor", form.slug)
        assertEquals("invoiceextractor", form.packageName)
        assertEquals("InvoiceExtractor", form.classPrefix)
        assertEquals("ai.rever.boss.plugin.dynamic.invoiceextractor", form.pluginId)
        assertEquals("boss-plugin-invoice-extractor", form.repoName)
        assertEquals(File("/work", "invoice-extractor").absolutePath, form.targetDir)
    }

    /** Punctuation collapses to single hyphens and is trimmed off the ends. */
    @Test
    fun `collapses punctuation into the slug`() {
        // A whole run of non-alphanumerics collapses to ONE hyphen, not one each.
        assertEquals("k-8-s-helper", ToolCreatorForm(toolName = "K 8 S -- Helper").derive().slug)
        assertEquals("helper", ToolCreatorForm(toolName = "  ...Helper!!  ").derive().slug)
    }

    // endregion

    // region validation

    @Test
    fun `requires a name a description and an existing location`() {
        val dir = tempDir("tc-validate")
        try {
            assertEquals(
                "Tool name is required",
                ToolCreatorForm(toolName = "", description = "d", parentDir = dir.path).derive().error,
            )
            assertEquals(
                "Tool description is required",
                ToolCreatorForm(toolName = "Thing", description = "", parentDir = dir.path).derive().error,
            )
            assertEquals(
                "Location is required",
                ToolCreatorForm(toolName = "Thing", description = "d", parentDir = "").derive().error,
            )
            assertEquals(
                "Location does not exist: /no/such/dir",
                ToolCreatorForm(toolName = "Thing", description = "d", parentDir = "/no/such/dir").derive().error,
            )
            assertNull(
                ToolCreatorForm(toolName = "Thing", description = "d", parentDir = dir.path).derive().error,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A name starting with a digit yields a slug that is not a legal package. */
    @Test
    fun `rejects a name that does not start with a letter`() {
        val dir = tempDir("tc-digit")
        try {
            assertEquals(
                "Tool name must start with a letter",
                ToolCreatorForm(toolName = "3D Viewer", description = "d", parentDir = dir.path).derive().error,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Scaffolding over an existing directory would clobber someone's work. */
    @Test
    fun `rejects a target directory that already exists`() {
        val dir = tempDir("tc-exists")
        try {
            File(dir, "thing").mkdirs()

            val error = ToolCreatorForm(toolName = "Thing", description = "d", parentDir = dir.path).derive().error

            assertNotNull(error)
            assertTrue(error.startsWith("Directory already exists:"), "got: $error")
        } finally {
            dir.deleteRecursively()
        }
    }

    // endregion

    // region form intents

    @Test
    fun `editing the name re-derives the plan`() {
        val holder = ToolCreatorStateHolder(scope)

        holder.onIntent(ToolCreatorIntent.SetToolName("Cost Report"))

        assertEquals("cost-report", holder.currentState().form.slug)
        assertEquals("CostReport", holder.currentState().form.classPrefix)
    }

    @Test
    fun `toggling a permission adds it then removes it`() {
        val holder = ToolCreatorStateHolder(scope)

        holder.onIntent(ToolCreatorIntent.TogglePermission(ToolCreatorPermission.NETWORK))
        assertTrue(ToolCreatorPermission.NETWORK in holder.currentState().form.permissions)

        holder.onIntent(ToolCreatorIntent.TogglePermission(ToolCreatorPermission.NETWORK))
        assertFalse(ToolCreatorPermission.NETWORK in holder.currentState().form.permissions)
    }

    @Test
    fun `choosing an agent is mirrored`() {
        val holder = ToolCreatorStateHolder(scope)

        holder.onIntent(ToolCreatorIntent.SetAgent(ToolCreatorAgent.CODEX))

        assertEquals(ToolCreatorAgent.CODEX, holder.currentState().form.agent)
    }

    // endregion

    // region handoff command

    /**
     * The terminal handoff is the plugin's finishing move and it cannot happen
     * here, so the exact command must be reproduced for the user to run. Each
     * agent's command is copied from the plugin's `CliAgent.launchCommand`.
     */
    @Test
    fun `every agent has an apostrophe free launch command`() {
        ToolCreatorAgent.entries.forEach { agent ->
            val command = agent.launchCommand()
            assertTrue(command.startsWith(agent.binary), "${agent.name}: $command")
            assertFalse(command.contains('\''), "${agent.name} must survive shell quoting: $command")
        }
    }

    @Test
    fun `claude's launch command engages the scaffolded skill`() {
        assertEquals("claude \"/tool-creator\"", ToolCreatorAgent.CLAUDE_CODE.launchCommand())
    }

    // endregion

    // region escaping and version sort

    /** Tokens land inside JSON and inside Kotlin string literals, escaped differently. */
    @Test
    fun `escapes json and kotlin differently`() {
        val raw = "a\"b\\c\nd\$e"

        assertEquals("a\\\"b\\\\c\\nd\$e", ToolCreatorStateHolder.jsonEscape(raw))
        assertEquals("a\\\"b\\\\c\\nd\\\$e", ToolCreatorStateHolder.ktEscape(raw))
    }

    /** `$` is only special in Kotlin — a JSON-escaped `$` would corrupt the manifest. */
    @Test
    fun `only kotlin escaping touches the dollar sign`() {
        assertEquals("costs \$5", ToolCreatorStateHolder.jsonEscape("costs \$5"))
        assertEquals("costs \\\$5", ToolCreatorStateHolder.ktEscape("costs \$5"))
    }

    /**
     * Zero-padding is what makes `1.0.66` sort above `1.0.9`; plain string
     * comparison would bundle a two-year-old api jar into every scaffold.
     */
    @Test
    fun `sorts api jar versions numerically`() {
        val newer = ToolCreatorStateHolder.versionKey("boss-plugin-api-1.0.66.jar")
        val older = ToolCreatorStateHolder.versionKey("boss-plugin-api-1.0.9.jar")

        assertTrue(newer > older, "$newer must sort above $older")
    }

    // endregion

    // region intent decoding

    @Test
    fun `decodes the nullary intents`() {
        assertEquals(ToolCreatorIntent.CheckEnvironment, decodeToolCreatorIntent("CheckEnvironment", ""))
        assertEquals(ToolCreatorIntent.StartBuild, decodeToolCreatorIntent("StartBuild", ""))
        assertEquals(ToolCreatorIntent.ClearJobs, decodeToolCreatorIntent("ClearJobs", ""))
    }

    @Test
    fun `decodes the text field intents from a bare payload`() {
        assertEquals(ToolCreatorIntent.SetToolName("Thing"), decodeToolCreatorIntent("SetToolName", "Thing"))
        assertEquals(ToolCreatorIntent.SetDescription("d"), decodeToolCreatorIntent("SetDescription", "d"))
        assertEquals(ToolCreatorIntent.SetParentDir("/w"), decodeToolCreatorIntent("SetParentDir", "/w"))
    }

    @Test
    fun `decodes enum payloads case insensitively`() {
        assertEquals(
            ToolCreatorIntent.SetAgent(ToolCreatorAgent.GEMINI),
            decodeToolCreatorIntent("SetAgent", "gemini"),
        )
        assertEquals(
            ToolCreatorIntent.TogglePermission(ToolCreatorPermission.SECRETS),
            decodeToolCreatorIntent("TogglePermission", "secrets"),
        )
    }

    @Test
    fun `drops enum payloads this build does not know`() {
        assertNull(decodeToolCreatorIntent("SetAgent", "cursor"))
        assertNull(decodeToolCreatorIntent("TogglePermission", "root"))
    }

    @Test
    fun `drops an unknown intent type`() {
        assertNull(decodeToolCreatorIntent("PublishToStore", ""))
        assertNull(decodeToolCreatorIntent("CreateGitHubRepo", ""))
    }

    // endregion

    private fun tempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private companion object {
        const val PROBE_WAIT_MS = 15_000L
        const val POLL_MS = 25L
    }

    /** Same net as the Atlas catalog: an enum reaches the host as its name and nothing else. */
    @Test
    fun `agent and permission labels reach the wire, not just the enum names`() {
        val json = StateWireJson.encodeToString(ToolCreatorState.serializer(), ToolCreatorState())

        assertTrue(json.contains("\"Claude Code\""), "agent display name must travel: $json")
        assertTrue(json.contains("\"OpenCode\""), json)
        assertTrue(json.contains("\"files.read\""), "permission id must travel: $json")
        assertTrue(json.contains("\"Read workspace files\""), "permission label must travel: $json")
    }

}
