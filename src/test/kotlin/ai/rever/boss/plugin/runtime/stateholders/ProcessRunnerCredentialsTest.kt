package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRunnerCredentialsTest {
    private val java = File(System.getProperty("java.home"), "bin/java")
    private val arguments = listOf(
        "-cp", File(EnvironmentProbe::class.java.protectionDomain.codeSource.location.toURI()).absolutePath,
        EnvironmentProbe::class.java.name,
    )
    private val credentials = listOf(
        "BOSS_PROCESS_TOKEN", "BOSS_KERNEL_TLS_CERT", "BOSS_IPC_TLS_CERT", "BOSS_IPC_TLS_KEY", "BOSS_HOST_TOKEN",
    )

    private fun environment(override: Boolean): Map<String, String> = buildMap {
        put("BOSS_TEST_NORMAL", "preserved")
        if (override) credentials.forEach { name ->
            put(name, "synthetic-override")
            put(name.lowercase(), "synthetic-case-override")
        }
    }

    @Test
    fun `fixture really inherits credentials before the runner scrubs them`() {
        val builder = ProcessBuilder(listOf(java.absolutePath) + arguments)
        builder.environment().putAll(environment(false))
        val process = builder.start()
        assertEquals("leaked:preserved", process.inputStream.bufferedReader().readText().trim())
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `run strips inherited and overridden credentials while retaining ordinary environment`() = runBlocking {
        for (override in listOf(false, true)) {
            val result = ProcessRunner.run(java, arguments, environment = environment(override))
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("clean:preserved", result.stdout.trim())
        }
    }

    @Test
    fun `stream strips inherited and overridden credentials while retaining ordinary environment`() = runBlocking {
        for (override in listOf(false, true)) {
            val lines = mutableListOf<String>()
            val result = ProcessRunner.stream(java, arguments, environment = environment(override)) { lines += it }
            assertEquals(0, result)
            assertEquals(listOf("clean:preserved"), lines)
        }
    }
}
