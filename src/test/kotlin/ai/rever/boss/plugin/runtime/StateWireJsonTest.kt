package ai.rever.boss.plugin.runtime

import ai.rever.boss.plugin.runtime.stateholders.DockerState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire encoder must keep defaults.
 *
 * A bare `Json` encodes a freshly constructed state as `{}` - every property is still at its
 * default, so every property is omitted. The host has no copy of the plugin's classes to fill
 * those back in, so what it renders is nothing at all.
 */
class StateWireJsonTest {
    @Test
    fun `a bare Json drops a default state entirely - the regression this guards`() {
        assertEquals("{}", Json.encodeToString(DockerState.serializer(), DockerState()))
    }

    @Test
    fun `the wire encoder keeps every field, including the false availability flags`() {
        val json = StateWireJson.encodeToString(DockerState.serializer(), DockerState())

        assertTrue(json.contains("\"ready\":false"), "a false flag must still be sent: $json")
        assertTrue(json.contains("\"daemon\":\"UNKNOWN\""), json)
        assertTrue(json.contains("\"containers\":[]"), "an empty list is not the same as absent: $json")
    }
}
