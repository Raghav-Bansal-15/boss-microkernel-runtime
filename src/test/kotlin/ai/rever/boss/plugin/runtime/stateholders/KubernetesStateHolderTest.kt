package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [KubernetesStateHolder] — the sidebar surface an out-of-process child
 * publishes to a host renderer.
 *
 * Everything here is pure (`-o json` / `custom-columns` parsing, the intent
 * decoder, the Secret refusal) or constructor-level. The parts that shell out to
 * `kubectl` are exercised in `boss-jvm-host`'s `real_plugin_e2e` sweep against a
 * **stub** `kubectl` and a throwaway `KUBECONFIG`: a JVM cannot change its own
 * `PATH`, so a unit test here could only reach the operator's real cluster, which
 * it must not.
 */
class KubernetesStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0 and a version-0 envelope never applies
     * host-side (the `bookmarks` gap). An empty namespace is a legitimate result,
     * so [KubernetesState.ready] is what proves the holder published.
     */
    @Test
    fun `publishes a versioned state before any kubectl call completes`() {
        val holder = KubernetesStateHolder(scope)

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        assertTrue(holder.currentState().ready)
    }

    @Test
    fun `reports mutations forwards terminal and persistence as unavailable`() {
        val state = KubernetesStateHolder(scope).currentState()

        assertFalse(state.mutationsAvailable, "no delete/scale/rollout/apply intent exists")
        assertFalse(state.forwardsAvailable, "a forward here is a process the host cannot see")
        assertFalse(state.terminalAvailable, "openTab is an inherited no-op on the IPC proxy")
        assertFalse(state.persistenceAvailable, "the wire has no plugin-storage service")
    }

    @Test
    fun `starts on the default namespace with the plugin's default sections`() {
        val state = KubernetesStateHolder(scope).currentState()

        assertEquals("default", state.selectedNamespace)
        assertEquals(listOf(KubeSection.WORKLOADS, KubeSection.PODS), state.expandedSections)
    }

    // endregion

    // region local intents

    /** Pointing at a context the kubeconfig never offered would query nothing. */
    @Test
    fun `selecting an unknown context is ignored`() {
        val holder = KubernetesStateHolder(scope)

        holder.onIntent(KubernetesIntent.SelectContext("no-such-context"))

        assertNull(holder.currentState().selectedContext)
    }

    @Test
    fun `selecting a namespace replaces the selection`() {
        val holder = KubernetesStateHolder(scope)

        holder.onIntent(KubernetesIntent.SelectNamespace("kube-system"))

        assertEquals("kube-system", holder.currentState().selectedNamespace)
    }

    @Test
    fun `toggling a section adds it then removes it`() {
        val holder = KubernetesStateHolder(scope)

        holder.onIntent(KubernetesIntent.ToggleSection(KubeSection.SERVICES))
        assertTrue(KubeSection.SERVICES in holder.currentState().expandedSections)

        holder.onIntent(KubernetesIntent.ToggleSection(KubeSection.SERVICES))
        assertFalse(KubeSection.SERVICES in holder.currentState().expandedSections)
    }

    @Test
    fun `selecting a resource records its kind and name`() {
        val holder = KubernetesStateHolder(scope)

        holder.onIntent(KubernetesIntent.SelectResource("pod", "api-7d9"))

        assertEquals("pod", holder.currentState().selectedKind)
        assertEquals("api-7d9", holder.currentState().selectedName)
    }

    // endregion

    // region the Secret guarantee

    /**
     * The plugin's structural promise: a Secret's YAML *is* its base64 payload,
     * and this surface copies state out of the process. `KubeActions.yaml()`
     * refuses in-process; so must this.
     */
    @Test
    fun `refuses to render a Secret's yaml`() {
        val holder = KubernetesStateHolder(scope)

        holder.onIntent(KubernetesIntent.ShowYaml("secret", "db-password"))

        val state = holder.currentState()
        assertEquals(KubeDetailKind.YAML, state.detail)
        assertEquals(KubernetesStateHolder.SECRET_YAML_REFUSAL, state.detailText)
        assertEquals("db-password", state.selectedName, "the row is still selected, just not rendered")
    }

    /**
     * The refusal is a constant: nothing about the resource — and so nothing from
     * the cluster — can reach [KubernetesState.detailText] on this path. Two
     * different Secrets must produce byte-identical text.
     */
    @Test
    fun `the Secret refusal carries nothing from the cluster`() {
        val first = KubernetesStateHolder(scope)
        first.onIntent(KubernetesIntent.ShowYaml("secret", "db-password"))
        val second = KubernetesStateHolder(scope)
        second.onIntent(KubernetesIntent.ShowYaml("secrets", "tls-cert"))

        assertEquals(first.currentState().detailText, second.currentState().detailText)
        assertFalse(
            first.currentState().detailText.contains("db-password"),
            "not even the resource name is echoed",
        )
    }

    /** Every spelling kubectl accepts for the kind must hit the refusal. */
    @Test
    fun `recognises every spelling of a Secret kind`() {
        listOf("secret", "Secret", "secrets", "SECRETS", "secret/db").forEach { kind ->
            assertTrue(KubernetesStateHolder.isSecretKind(kind), "$kind must be treated as a Secret")
        }
    }

    /** …and nothing else, or `ShowYaml` would refuse legitimate kinds. */
    @Test
    fun `does not mistake other kinds for Secrets`() {
        listOf("pod", "configmap", "deployment", "service", "secretproviderclass").forEach { kind ->
            assertFalse(KubernetesStateHolder.isSecretKind(kind), "$kind must not be treated as a Secret")
        }
    }

    // endregion

    // region kubectl output parsing

    @Test
    fun `pulls the server version out of kubectl version json`() {
        val stdout = """
            {"clientVersion":{"gitVersion":"v1.35.0"},
             "serverVersion":{"major":"1","minor":"34","gitVersion":"v1.34.2"}}
        """.trimIndent()

        assertEquals("v1.34.2", KubernetesStateHolder.serverVersionOf(stdout))
    }

    /** A payload with no server half must not be reported as a version. */
    @Test
    fun `reports an unknown server version when the json has none`() {
        assertEquals(
            "unknown",
            KubernetesStateHolder.serverVersionOf("""{"clientVersion":{"gitVersion":"v1.35.0"}}"""),
        )
    }

    /**
     * `{{.context.namespace}}` renders the literal `<no value>` when a context
     * sets none, which would then be passed to `kubectl -n` verbatim and match
     * nothing.
     */
    @Test
    fun `treats go template placeholders as empty`() {
        with(KubernetesStateHolder) {
            assertEquals("", "<no value>".cleanTemplateValue())
            assertEquals("", "<nil>".cleanTemplateValue())
            assertEquals("", null.cleanTemplateValue())
            assertEquals("prod", "  prod  ".cleanTemplateValue())
        }
    }

    /**
     * A pod stuck in CrashLoopBackOff still reports `phase: Running`; the waiting
     * reason is what a human needs in the row, and what marks it failing.
     */
    @Test
    fun `surfaces a waiting reason instead of the phase`() {
        val stdout = """
            {"items":[{"metadata":{"name":"api-1","namespace":"prod","creationTimestamp":"2026-07-01T00:00:00Z"},
              "spec":{"nodeName":"node-a","containers":[{"name":"api"},{"name":"sidecar"}]},
              "status":{"phase":"Running","containerStatuses":[
                {"ready":false,"restartCount":7,"state":{"waiting":{"reason":"CrashLoopBackOff"}}},
                {"ready":true,"restartCount":0,"state":{}}]}}]}
        """.trimIndent()

        val pod = KubernetesStateHolder.parsePods(stdout).single()

        assertEquals("CrashLoopBackOff", pod.phase)
        assertEquals(1, pod.readyContainers)
        assertEquals(2, pod.totalContainers)
        assertEquals(7, pod.restarts, "restarts sum across containers")
        assertEquals("node-a", pod.node)
        assertEquals(listOf("api", "sidecar"), pod.containers)
        assertTrue(pod.failing)
    }

    /** A pod with no container statuses yet falls back to the spec's count. */
    @Test
    fun `falls back to the spec container count before statuses exist`() {
        val stdout = """
            {"items":[{"metadata":{"name":"pending-1","namespace":"prod"},
              "spec":{"containers":[{"name":"a"},{"name":"b"}]},
              "status":{"phase":"Pending","containerStatuses":[]}}]}
        """.trimIndent()

        val pod = KubernetesStateHolder.parsePods(stdout).single()

        assertEquals(2, pod.totalContainers)
        assertEquals(0, pod.readyContainers)
        assertFalse(pod.failing, "Pending is not failing")
    }

    /** Deployments read replicas; DaemonSets use an entirely different vocabulary. */
    @Test
    fun `reads deployment replicas from spec and status`() {
        val stdout = """
            {"items":[{"metadata":{"name":"api","namespace":"prod"},
              "spec":{"replicas":3,"template":{"spec":{"containers":[{"image":"api:1"},{"image":""}]}}},
              "status":{"readyReplicas":3,"replicas":3}}]}
        """.trimIndent()

        val workload = KubernetesStateHolder.parseWorkloads(stdout, "Deployment").single()

        assertEquals("Deployment", workload.kind)
        assertEquals(3, workload.ready)
        assertEquals(3, workload.desired)
        assertTrue(workload.healthy)
        assertEquals(listOf("api:1"), workload.images, "blank images are dropped")
    }

    @Test
    fun `reads daemonset replicas from its own status fields`() {
        val stdout = """
            {"items":[{"metadata":{"name":"logs","namespace":"kube-system"},
              "spec":{"template":{"spec":{"containers":[{"image":"fluentd:2"}]}}},
              "status":{"numberReady":2,"desiredNumberScheduled":4}}]}
        """.trimIndent()

        val workload = KubernetesStateHolder.parseWorkloads(stdout, "DaemonSet").single()

        assertEquals(2, workload.ready)
        assertEquals(4, workload.desired)
        assertFalse(workload.healthy)
    }

    /** `targetPort` may be an int or a named port string; both must render. */
    @Test
    fun `renders both numeric and named target ports`() {
        val stdout = """
            {"items":[{"metadata":{"name":"api","namespace":"prod"},
              "spec":{"type":"","clusterIP":"10.0.0.1","ports":[
                {"name":"http","port":80,"targetPort":8080,"protocol":"TCP"},
                {"name":"grpc","port":9000,"targetPort":"grpc-port","protocol":""}]}}]}
        """.trimIndent()

        val service = KubernetesStateHolder.parseServices(stdout).single()

        assertEquals("ClusterIP", service.type, "a blank type defaults to ClusterIP")
        assertEquals("8080", service.ports[0].targetPort)
        assertEquals("grpc-port", service.ports[1].targetPort)
        assertEquals("TCP", service.ports[1].protocol, "a blank protocol defaults to TCP")
    }

    /** Malformed output must yield no rows rather than throwing into the sync loop. */
    @Test
    fun `parses unusable output as no rows`() {
        assertEquals(emptyList(), KubernetesStateHolder.parsePods("not json"))
        assertEquals(emptyList(), KubernetesStateHolder.parsePods(""))
    }

    // endregion

    // region intent decoding

    @Test
    fun `decodes the nullary intents`() {
        assertEquals(KubernetesIntent.Refresh, decodeKubernetesIntent("Refresh", ""))
        assertEquals(KubernetesIntent.RescanProject, decodeKubernetesIntent("RescanProject", ""))
        assertEquals(KubernetesIntent.ClearSelection, decodeKubernetesIntent("ClearSelection", ""))
        assertEquals(KubernetesIntent.ClearDetail, decodeKubernetesIntent("ClearDetail", ""))
    }

    @Test
    fun `decodes bare string payloads`() {
        assertEquals(KubernetesIntent.SetQuery("api"), decodeKubernetesIntent("SetQuery", "api"))
        assertEquals(
            KubernetesIntent.SelectContext("prod"),
            decodeKubernetesIntent("SelectContext", "prod"),
        )
        assertEquals(
            KubernetesIntent.SelectNamespace("kube-system"),
            decodeKubernetesIntent("SelectNamespace", "kube-system"),
        )
    }

    @Test
    fun `decodes a section name case insensitively and drops an unknown one`() {
        assertEquals(
            KubernetesIntent.ToggleSection(KubeSection.SECRETS),
            decodeKubernetesIntent("ToggleSection", "secrets"),
        )
        assertNull(decodeKubernetesIntent("ToggleSection", "nonsense"))
    }

    @Test
    fun `decodes the two field intents from a json object`() {
        assertEquals(
            KubernetesIntent.SelectResource("pod", "api-1"),
            decodeKubernetesIntent("SelectResource", """{"kind":"pod","name":"api-1"}"""),
        )
        assertEquals(
            KubernetesIntent.ShowDescribe("service", "api"),
            decodeKubernetesIntent("ShowDescribe", """{"kind":"service","name":"api"}"""),
        )
        assertEquals(
            KubernetesIntent.ShowYaml("deployment", "api"),
            decodeKubernetesIntent("ShowYaml", """{"kind":"deployment","name":"api"}"""),
        )
    }

    /** A half-read message must be dropped, not applied with a missing field. */
    @Test
    fun `drops two field intents missing a field`() {
        assertNull(decodeKubernetesIntent("SelectResource", """{"kind":"pod"}"""))
        assertNull(decodeKubernetesIntent("ShowDescribe", """{"name":"api"}"""))
        assertNull(decodeKubernetesIntent("ShowYaml", ""))
    }

    @Test
    fun `decodes show logs with a container and a tail`() {
        assertEquals(
            KubernetesIntent.ShowLogs("api-1", "sidecar", 20),
            decodeKubernetesIntent("ShowLogs", """{"podName":"api-1","container":"sidecar","tail":20}"""),
        )
    }

    @Test
    fun `decodes show logs from a bare pod name with defaults`() {
        assertEquals(
            KubernetesIntent.ShowLogs("api-1", "", KubernetesIntent.DEFAULT_TAIL),
            decodeKubernetesIntent("ShowLogs", "api-1"),
        )
    }

    /** A JSON object with no pod name must never become a pod named `{}`. */
    @Test
    fun `drops show logs with no pod name`() {
        assertNull(decodeKubernetesIntent("ShowLogs", ""))
        assertNull(decodeKubernetesIntent("ShowLogs", "{}"))
    }

    /**
     * The whole point of [KubernetesState.mutationsAvailable]: no mutating intent
     * exists. If someone adds one this fails, and they must update the flag and
     * the holder docs with it.
     */
    @Test
    fun `has no mutating or port forward intent in its vocabulary`() {
        val forbidden = listOf(
            "Delete", "Scale", "RolloutRestart", "Apply", "Exec",
            "PortForward", "PortForwardStop", "UseContext", "HelmInstall",
        )

        forbidden.forEach { type ->
            assertNull(decodeKubernetesIntent(type, "api"), "$type must not decode to an intent")
        }
    }

    // endregion
}
