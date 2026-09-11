package ai.rever.boss.plugin.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The transport contract the host byte-compares at spawn time
 * (`IpcTransport.requireCompatibleRuntime`).
 *
 * The comparison is exact, so anything that rewrites the file defeats the gate. There is no
 * `.gitattributes` covering the repository at large - this is the one resource whose bytes are
 * semantics, and the whole runtime test suite is otherwise indifferent to line endings. The
 * Windows runners used to check this file out with CRLF, the fat jar then carried
 * `pinned-tls-v1;subprocess-env-v1\r\n`, and the host refused every runtime that CI had approved:
 * the integration test above it reported only "unsupported IPC transport", with nothing pointing
 * at the checkout. This pins the resource so a checkout conversion fails here, at the file.
 */
class RuntimeContractMarkerTest {
    @Test
    fun `the security contract resource is byte-identical to what the host compares`() {
        val content =
            javaClass
                .getResourceAsStream("/META-INF/boss-runtime/security-contract")
                ?.readBytes()
                ?.toString(Charsets.UTF_8)

        assertEquals(
            "pinned-tls-v1;subprocess-env-v1\n",
            content,
            "the contract marker must be exactly what IpcTransport compares - a rewritten " +
                "line ending here makes the host refuse every build this suite approved",
        )
    }
}
