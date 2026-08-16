package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The one place the CLI-backed state holders spawn a child process.
 *
 * Three rules hold for every caller, lifted from the plugins these holders
 * mirror (`DockerCli`, `KubectlCli`, `HelmCli`, `ClaudeCodeCliBackend`) because
 * each had independently arrived at them:
 *
 * 1. **Absolute binary plus a widened child PATH.** `ProcessBuilder` resolves a
 *    bare command name against the *parent* process's PATH. A plugin child JVM
 *    inherits that from the host, which on macOS is nearly empty when the app
 *    was launched from Finder — so a bare `"docker"` works in dev and fails in
 *    the shipped app.
 * 2. **argv lists, never a shell string.** Container names, resource names and
 *    paths come from daemon output and from user input; passing a
 *    `List<String>` means there is no shell to inject into.
 * 3. **Cancellation kills the child.** A cancelled coroutine must not leave a
 *    live CLI process behind, and closing the child's streams is what unblocks
 *    the reads — so the kill is wired to job completion rather than left to the
 *    blocking read returning.
 *
 * Resolution deliberately searches `PATH` **before** [ExecResult]-shaped
 * fallback install dirs, which is what lets a test put a stub binary first on a
 * spawned child's PATH and be certain the real one is never reached.
 */
internal object ProcessRunner {

    const val EXIT_BINARY_MISSING = -1
    const val EXIT_TIMEOUT = -2

    /** stdout and stderr of one finished invocation. */
    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Find [binary] on `PATH`, then in [extraDirs]. Null when it is not
     * installed anywhere we look.
     */
    fun resolve(binary: String, extraDirs: List<String>): File? {
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        return (pathDirs + extraDirs).asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, binary) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * Run `exe <args>` to completion and capture both streams.
     *
     * A null [exe] returns [EXIT_BINARY_MISSING] with [missingMessage] on
     * stderr, so callers can tell "not installed" apart from "the tool said no"
     * without a separate probe.
     */
    @Suppress("LongParameterList")
    suspend fun run(
        exe: File?,
        args: List<String>,
        extraPathDirs: List<String> = emptyList(),
        workingDir: File? = null,
        timeoutMs: Long = 30_000L,
        stdin: String? = null,
        missingMessage: String = "The required command-line tool was not found.",
        timeoutMessage: String = "the command timed out",
        environment: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(Dispatchers.IO) {
        if (exe == null) return@withContext ExecResult(EXIT_BINARY_MISSING, "", missingMessage)

        val builder = ProcessBuilder(listOf(exe.absolutePath) + args).directory(workingDir)
        if (extraPathDirs.isNotEmpty()) {
            val current = builder.environment()["PATH"].orEmpty()
            builder.environment()["PATH"] = (extraPathDirs + current)
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
        }
        builder.environment().putAll(environment)
        val process = builder.start()

        // Kill the child the moment this coroutine is cancelled; closing its
        // streams is what unblocks the reads below.
        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            if (stdin == null) {
                // Never let the tool block waiting on stdin.
                runCatching { process.outputStream.close() }
            } else {
                runCatching {
                    process.outputStream.bufferedWriter().use { it.write(stdin) }
                }
            }
            // The timeout has to bound the READS, not just the exit reap. readText() blocks
            // until the child closes stdout, so a `waitFor(timeoutMs)` placed after it bounds
            // only the gap between EOF and exit - and a child that holds stdout open and never
            // answers is exactly the case worth bounding. It is reachable: the docker CLI has
            // no client-side request timeout, so a wedged daemon socket hangs `docker version`
            // and the holder sits at daemon=UNKNOWN, busy=true, with no rows and no error.
            //
            // Raced, not wrapped. readText() has no suspension point, so simply enclosing it in
            // withTimeoutOrNull would give the cancellation nowhere to land - the timeout could
            // not fire until the read it is meant to bound had already returned. Awaiting a
            // separate job does suspend, and destroying the child on expiry closes its streams,
            // which is what actually unblocks the readers.
            coroutineScope {
                val body = async {
                    val errText = async {
                        runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
                    }
                    val outText = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
                    val err = errText.await()
                    process.waitFor()
                    ExecResult(process.exitValue(), outText, err)
                }
                withTimeoutOrNull(timeoutMs) { body.await() } ?: run {
                    process.destroyForcibly()
                    body.cancel()
                    ExecResult(EXIT_TIMEOUT, "", timeoutMessage)
                }
            }
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Run a long-lived streaming command and deliver stdout (with stderr
     * interleaved) line by line until the process ends or the caller is
     * cancelled. Suspends for the stream's lifetime; launch it yourself.
     */
    @Suppress("LongParameterList")
    suspend fun stream(
        exe: File?,
        args: List<String>,
        extraPathDirs: List<String> = emptyList(),
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        /**
         * Written to the child's stdin, which is then closed — the shape a
         * headless CLI that reads one prompt and streams a reply needs. Null
         * closes stdin immediately.
         */
        stdin: String? = null,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (exe == null) return@withContext EXIT_BINARY_MISSING

        val builder = ProcessBuilder(listOf(exe.absolutePath) + args)
            .directory(workingDir)
            .redirectErrorStream(true) // interleaved is what a log view wants
        if (extraPathDirs.isNotEmpty()) {
            val current = builder.environment()["PATH"].orEmpty()
            builder.environment()["PATH"] = (extraPathDirs + current)
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
        }
        builder.environment().putAll(environment)
        val process = builder.start()

        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            if (stdin == null) {
                runCatching { process.outputStream.close() }
            } else {
                runCatching { process.outputStream.bufferedWriter().use { it.write(stdin) } }
            }
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                onLine(line)
            }
            runCatching { process.waitFor(STREAM_DRAIN_SECONDS, TimeUnit.SECONDS) }
            if (process.isAlive) EXIT_TIMEOUT else process.exitValue()
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private const val STREAM_DRAIN_SECONDS = 5L
}
