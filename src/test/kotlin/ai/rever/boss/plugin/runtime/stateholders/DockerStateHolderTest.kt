package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DockerStateHolder] — the sidebar surface an out-of-process child
 * publishes to a host renderer.
 *
 * Split deliberately: everything here is either pure (`{{json .}}` parsing, the
 * intent decoder) or constructor-level. The parts that shell out to `docker` are
 * exercised in `boss-jvm-host`'s `real_plugin_e2e` sweep, where the child's PATH
 * can be pointed at a **stub** `docker` — a JVM cannot change its own `PATH`, so
 * a unit test here could only ever reach the operator's real daemon, which it
 * must not.
 */
class DockerStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0, and the kernel bridge's strictly-newer
     * guard means a version-0 envelope never applies host-side (the `bookmarks`
     * gap). An empty daemon is a legitimate result, so [DockerState.ready] — not
     * a non-empty container list — is what proves the holder published.
     */
    @Test
    fun `publishes a versioned state before any docker call completes`() {
        val holder = DockerStateHolder(scope)

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        assertTrue(holder.currentState().ready)
    }

    /** Honest capability reporting, so the host renders no dead Stop/Remove/Build. */
    @Test
    fun `reports mutations terminal and persistence as unavailable`() {
        val state = DockerStateHolder(scope).currentState()

        assertFalse(state.mutationsAvailable, "no start/stop/rm intent exists")
        assertFalse(state.terminalAvailable, "openTab is an inherited no-op on the IPC proxy")
        assertFalse(state.persistenceAvailable, "the wire has no plugin-storage service")
    }

    /** The plugin opens Project + Containers by default; a renderer mirrors that. */
    @Test
    fun `starts with the plugin's default expanded sections`() {
        val state = DockerStateHolder(scope).currentState()

        assertEquals(listOf(DockerSection.PROJECT, DockerSection.CONTAINERS), state.expandedSections)
    }

    /** No project path on this context, so there is nothing to scan. */
    @Test
    fun `has no project artifacts without a project path`() {
        assertEquals(emptyList(), DockerStateHolder(scope).currentState().projectArtifacts)
    }

    // endregion

    // region local intents

    @Test
    fun `toggling a section adds it then removes it`() {
        val holder = DockerStateHolder(scope)

        holder.onIntent(DockerIntent.ToggleSection(DockerSection.IMAGES))
        assertTrue(DockerSection.IMAGES in holder.currentState().expandedSections)

        holder.onIntent(DockerIntent.ToggleSection(DockerSection.IMAGES))
        assertFalse(DockerSection.IMAGES in holder.currentState().expandedSections)
    }

    @Test
    fun `set query is mirrored verbatim`() {
        val holder = DockerStateHolder(scope)

        holder.onIntent(DockerIntent.SetQuery("nginx"))

        assertEquals("nginx", holder.currentState().query)
    }

    /**
     * A selection must name a container that exists, or a renderer ends up
     * pointing at a row that is not in the list it was given.
     */
    @Test
    fun `selecting an unknown container is ignored`() {
        val holder = DockerStateHolder(scope)

        holder.onIntent(DockerIntent.SelectContainer("does-not-exist"))

        assertNull(holder.currentState().selectedContainerId)
    }

    @Test
    fun `clearing the selection also clears the detail body`() {
        val holder = DockerStateHolder(scope)

        holder.onIntent(DockerIntent.ClearSelection)

        val state = holder.currentState()
        assertNull(state.selectedContainerId)
        assertEquals(DockerDetailKind.NONE, state.detail)
        assertEquals("", state.detailText)
    }

    // endregion

    // region port parsing

    /**
     * Docker lists the IPv4 and IPv6 bindings of one publish separately. A
     * renderer must see one row per actual mapping, and the IPv4 record is the
     * friendlier one to show.
     */
    @Test
    fun `dedupes the ipv4 and ipv6 records of one publish preferring ipv4`() {
        val ports = DockerStateHolder.parsePorts("0.0.0.0:8080->80/tcp, :::8080->80/tcp")

        assertEquals(1, ports.size, "one publish must be one row: $ports")
        assertEquals("0.0.0.0", ports.single().hostIp)
        assertEquals(8080, ports.single().hostPort)
        assertEquals(80, ports.single().containerPort)
        assertEquals("tcp", ports.single().protocol)
    }

    /**
     * The same publish with the IPv6 record listed **first**. Docker's ordering is
     * not guaranteed, and a dedupe that simply keeps whichever arrived first would
     * show `::` as the host address here while showing `0.0.0.0` in the previous
     * test — so this is the case that actually pins the preference.
     */
    @Test
    fun `prefers the ipv4 record even when the ipv6 one arrives first`() {
        val ports = DockerStateHolder.parsePorts(":::8080->80/tcp, 0.0.0.0:8080->80/tcp")

        assertEquals(1, ports.size, "one publish must be one row: $ports")
        assertEquals("0.0.0.0", ports.single().hostIp)
    }

    /** An IPv6-only publish keeps its own address rather than being dropped. */
    @Test
    fun `keeps an ipv6 only publish`() {
        val ports = DockerStateHolder.parsePorts(":::9000->9000/tcp")

        assertEquals(1, ports.size)
        assertEquals("::", ports.single().hostIp, "`:::9000` keeps `::` as the host address")
        assertEquals(9000, ports.single().hostPort)
    }

    /** An exposed-but-unpublished port has no host side and no row. */
    @Test
    fun `ignores an exposed port that is not published`() {
        assertEquals(emptyList(), DockerStateHolder.parsePorts("80/tcp"))
        assertEquals(emptyList(), DockerStateHolder.parsePorts(""))
    }

    @Test
    fun `sorts multiple publishes by host port`() {
        val ports = DockerStateHolder.parsePorts("0.0.0.0:9443->443/tcp, 0.0.0.0:8080->80/tcp")

        assertEquals(listOf(8080, 9443), ports.map { it.hostPort })
    }

    /** UDP publishes keep their protocol; a renderer must not label them tcp. */
    @Test
    fun `preserves a non tcp protocol`() {
        val ports = DockerStateHolder.parsePorts("0.0.0.0:5353->53/udp")

        assertEquals("udp", ports.single().protocol)
    }

    // endregion

    // region label parsing

    @Test
    fun `parses the labels column into pairs`() {
        val labels = DockerStateHolder.parseLabels("com.docker.compose.project=shop,maintainer=ops")

        assertEquals("shop", labels["com.docker.compose.project"])
        assertEquals("ops", labels["maintainer"])
    }

    @Test
    fun `skips label entries with no equals sign`() {
        val labels = DockerStateHolder.parseLabels("broken,ok=yes")

        assertEquals(mapOf("ok" to "yes"), labels)
    }

    @Test
    fun `parses an empty labels column as empty`() {
        assertEquals(emptyMap(), DockerStateHolder.parseLabels(""))
    }

    // endregion

    // region docker ps / images / volumes / networks / compose parsing

    private val psOutput = """
        {"ID":"abc123def4567890","Names":"web","Image":"nginx:1","State":"running","Status":"Up 2 minutes","Ports":"0.0.0.0:8080->80/tcp, :::8080->80/tcp","Labels":"com.docker.compose.project=shop","CreatedAt":"2026-07-01 10:00:00 +0000 UTC"}
        {"ID":"999","Names":"db,db-alias","Image":"postgres:16","State":"exited","Status":"Exited (0)","Ports":"","Labels":"","CreatedAt":""}
    """.trimIndent()

    @Test
    fun `maps a docker ps line onto a container row`() {
        val web = DockerStateHolder.parseContainers(psOutput).single { it.name == "web" }

        assertEquals("abc123def4567890", web.id)
        assertEquals("nginx:1", web.image)
        assertEquals("running", web.state)
        assertEquals("Up 2 minutes", web.status)
        assertTrue(web.isRunning)
        assertEquals("shop", web.composeProject, "the compose label is lifted onto the row")
        assertEquals(1, web.ports.size, "the ipv4/ipv6 pair is one mapping: ${web.ports}")
        assertEquals(8080, web.ports.single().hostPort)
    }

    /** `docker ps` joins multiple names with commas; the first is the real one. */
    @Test
    fun `takes the first of several container names`() {
        val db = DockerStateHolder.parseContainers(psOutput).single { it.id == "999" }

        assertEquals("db", db.name)
        assertFalse(db.isRunning)
        assertEquals("", db.composeProject, "no compose label means no project")
    }

    /** Running containers first, then by name — what the sidebar shows. */
    @Test
    fun `sorts running containers before stopped ones`() {
        assertEquals(listOf("web", "db"), DockerStateHolder.parseContainers(psOutput).map { it.name })
    }

    /**
     * `docker` writes non-JSON lines to the same stream (warnings, hints). They
     * are noise, and a decoder that tried them would return a list with holes.
     */
    @Test
    fun `skips lines that are not json objects`() {
        val stdout = "WARNING: something\n" + psOutput + "\nnot json at all\n"

        assertEquals(2, DockerStateHolder.parseContainers(stdout).size)
    }

    @Test
    fun `computes an image reference and dangling flag`() {
        val stdout = """
            {"ID":"sha256:deadbeefcafebabe","Repository":"<none>","Tag":"<none>","Size":"120MB","CreatedSince":"2 days ago"}
            {"ID":"sha256:1","Repository":"nginx","Tag":"1.27","Size":"50MB","CreatedSince":"1 day ago"}
        """.trimIndent()

        val images = DockerStateHolder.parseImages(stdout)

        val dangling = images.single { it.dangling }
        assertEquals("deadbeefcafe", dangling.reference, "a dangling image shows its short id")
        val tagged = images.single { !it.dangling }
        assertEquals("nginx:1.27", tagged.reference)
    }

    @Test
    fun `parses volumes and networks`() {
        val volumes = DockerStateHolder.parseVolumes(
            """{"Name":"pgdata","Driver":"local","Mountpoint":"/var/lib/docker/volumes/pgdata/_data"}"""
        )
        assertEquals("pgdata", volumes.single().name)
        assertEquals("local", volumes.single().driver)

        val networks = DockerStateHolder.parseNetworks(
            """{"ID":"net1","Name":"bridge","Driver":"bridge","Scope":"local"}"""
        )
        assertEquals("bridge", networks.single().name)
        assertEquals("local", networks.single().scope)
    }

    /** `docker compose ls` emits a JSON array, unlike the NDJSON of `ps`. */
    @Test
    fun `parses the compose ls array and derives running`() {
        val stdout = """
            [{"Name":"shop","Status":"running(3)","ConfigFiles":"/p/compose.yaml"},
             {"Name":"old","Status":"exited(1)","ConfigFiles":"/q/compose.yaml"}]
        """.trimIndent()

        val projects = DockerStateHolder.parseComposeProjects(stdout)

        assertEquals(2, projects?.size)
        assertTrue(projects!!.single { it.name == "shop" }.running)
        assertFalse(projects.single { it.name == "old" }.running)
    }

    /**
     * A payload that is not an array at all means the command answered with
     * something else; that must be left alone rather than published as "no
     * compose projects", which would wipe a good list.
     */
    @Test
    fun `returns null for a compose payload that is not an array`() {
        assertNull(DockerStateHolder.parseComposeProjects("no compose plugin installed"))
        assertNull(DockerStateHolder.parseComposeProjects(""))
    }

    // endregion

    // region what actually reaches the wire

    /**
     * kotlinx serializes **constructor properties only**. Three of these rows
     * once carried `isRunning` / `failing` / `healthy` as computed getters, and
     * the field was silently absent from every synced payload — a host renderer
     * would have had to re-derive it, which is the duplication these mirrored
     * rows exist to remove. Only an end-to-end run caught it, so this is the
     * unit-level guard: assert against the encoded JSON, not the Kotlin object.
     */
    @Test
    fun `the derived container fields are serialized not computed`() {
        val state = DockerState(containers = DockerStateHolder.parseContainers(psOutput))

        val json = Json.encodeToString(DockerState.serializer(), state)

        assertTrue(json.contains("\"isRunning\":true"), "isRunning must reach the wire: $json")
        assertTrue(json.contains("\"composeProject\":\"shop\""), json)
        assertTrue(json.contains("\"hostPort\":8080"), json)
    }

    /** …and the same for the image row's two derived fields. */
    @Test
    fun `the derived image fields are serialized not computed`() {
        val images = DockerStateHolder.parseImages(
            """{"ID":"sha256:deadbeefcafebabe","Repository":"<none>","Tag":"<none>","Size":"1MB","CreatedSince":"now"}"""
        )

        val json = Json.encodeToString(DockerState.serializer(), DockerState(images = images))

        assertTrue(json.contains("\"reference\":\"deadbeefcafe\""), json)
        assertTrue(json.contains("\"dangling\":true"), json)
    }

    // endregion

    // region intent decoding

    /**
     * A holder with no arm in `PluginProcessMain.resolveIntentDeserializer`
     * publishes state and then silently drops every intent — which looks exactly
     * like a UI whose buttons do nothing. These pin the arm's vocabulary.
     */
    @Test
    fun `decodes the nullary intents`() {
        assertEquals(DockerIntent.Refresh, decodeDockerIntent("Refresh", ""))
        assertEquals(DockerIntent.RescanProject, decodeDockerIntent("RescanProject", ""))
        assertEquals(DockerIntent.ClearSelection, decodeDockerIntent("ClearSelection", ""))
        assertEquals(DockerIntent.ClearDetail, decodeDockerIntent("ClearDetail", ""))
    }

    @Test
    fun `decodes a bare string payload for the single field intents`() {
        assertEquals(DockerIntent.SetQuery("web"), decodeDockerIntent("SetQuery", "web"))
        assertEquals(DockerIntent.SelectContainer("abc"), decodeDockerIntent("SelectContainer", "abc"))
        assertEquals(DockerIntent.ShowInspect("abc"), decodeDockerIntent("ShowInspect", "abc"))
    }

    @Test
    fun `decodes a section name case insensitively`() {
        assertEquals(
            DockerIntent.ToggleSection(DockerSection.VOLUMES),
            decodeDockerIntent("ToggleSection", "volumes"),
        )
    }

    /** An enum name this build does not know must be dropped, not crash the stream. */
    @Test
    fun `drops an unknown section name`() {
        assertNull(decodeDockerIntent("ToggleSection", "nonsense"))
    }

    @Test
    fun `decodes show logs from a json object with a tail`() {
        val intent = decodeDockerIntent("ShowLogs", """{"containerId":"abc","tail":42}""")

        assertEquals(DockerIntent.ShowLogs("abc", 42), intent)
    }

    /** The bare-string form is the convention the other holders' arms use. */
    @Test
    fun `decodes show logs from a bare container id with the default tail`() {
        val intent = decodeDockerIntent("ShowLogs", "abc")

        assertEquals(DockerIntent.ShowLogs("abc", DockerIntent.DEFAULT_TAIL), intent)
    }

    @Test
    fun `drops show logs with no container id`() {
        assertNull(decodeDockerIntent("ShowLogs", ""))
        assertNull(decodeDockerIntent("ShowLogs", "{}"))
    }

    @Test
    fun `drops an unknown intent type`() {
        assertNull(decodeDockerIntent("StopContainer", "abc"))
        assertNull(decodeDockerIntent("RemoveImage", "abc"))
    }

    /**
     * Not a typo test: the whole point of [DockerState.mutationsAvailable] is
     * that no mutating intent exists. If someone adds one, this fails and they
     * have to update the capability flag and the docs with it.
     */
    @Test
    fun `has no mutating intent in its vocabulary`() {
        val mutating = listOf("Start", "Stop", "Restart", "Remove", "Rm", "Rmi", "ComposeUp", "ComposeDown", "Build")

        mutating.forEach { type ->
            assertNull(decodeDockerIntent(type, "abc"), "$type must not decode to an intent")
        }
    }

    // endregion
}
