package ai.rever.boss.plugin.runtime

import ai.rever.boss.plugin.runtime.stateholders.AtlasIntent
import ai.rever.boss.plugin.runtime.stateholders.AtlasStateHolder
import ai.rever.boss.plugin.runtime.stateholders.DockerIntent
import ai.rever.boss.plugin.runtime.stateholders.DockerStateHolder
import ai.rever.boss.plugin.runtime.stateholders.KubernetesIntent
import ai.rever.boss.plugin.runtime.stateholders.KubernetesStateHolder
import ai.rever.boss.plugin.runtime.stateholders.ToolCreatorIntent
import ai.rever.boss.plugin.runtime.stateholders.ToolCreatorStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the **arm table** in [resolveIntentDeserializer], which is a different
 * thing from the decoders it delegates to.
 *
 * A holder's decoder can be perfect and fully tested while the `is` arm that
 * reaches it is missing — and then the child loads, registers, heartbeats,
 * publishes state, and silently drops every intent the host sends. That is
 * indistinguishable from a rendered panel whose buttons do nothing, and it is
 * exactly what happened to the Jupyter and Flow holders before their arms were
 * added. Testing each decoder in isolation does **not** cover this: deleting an
 * arm left the whole holder suite green.
 *
 * One assertion per arm, deliberately: the point is that removing any single arm
 * fails a test.
 */
class IntentDeserializerArmsTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `routes docker intents to the docker decoder`() {
        val decoded = resolveIntentDeserializer(
            DockerStateHolder(scope),
            "SelectContainer",
            "abc123".toByteArray(),
        )

        assertEquals(DockerIntent.SelectContainer("abc123"), decoded)
    }

    @Test
    fun `routes kubernetes intents to the kubernetes decoder`() {
        val decoded = resolveIntentDeserializer(
            KubernetesStateHolder(scope),
            "SelectNamespace",
            "kube-system".toByteArray(),
        )

        assertEquals(KubernetesIntent.SelectNamespace("kube-system"), decoded)
    }

    @Test
    fun `routes atlas intents to the atlas decoder`() {
        val decoded = resolveIntentDeserializer(
            AtlasStateHolder(scope),
            "Send",
            "hello".toByteArray(),
        )

        assertEquals(AtlasIntent.Send("hello"), decoded)
    }

    @Test
    fun `routes tool creator intents to the tool creator decoder`() {
        val decoded = resolveIntentDeserializer(
            ToolCreatorStateHolder(scope),
            "SetToolName",
            "Invoice Extractor".toByteArray(),
        )

        assertEquals(ToolCreatorIntent.SetToolName("Invoice Extractor"), decoded)
    }

    /**
     * An empty payload must reach the decoder as an empty string, not as a
     * one-character string or a crash: `PluginStateSyncService` sends nullary
     * intents with no bytes at all.
     */
    @Test
    fun `passes an empty payload through as an empty string`() {
        assertEquals(
            DockerIntent.Refresh,
            resolveIntentDeserializer(DockerStateHolder(scope), "Refresh", ByteArray(0)),
        )
    }

    /** An intent type no arm knows must be dropped, not thrown. */
    @Test
    fun `drops an intent type no arm knows`() {
        assertNull(
            resolveIntentDeserializer(DockerStateHolder(scope), "NoSuchIntent", "x".toByteArray()),
        )
    }
}
