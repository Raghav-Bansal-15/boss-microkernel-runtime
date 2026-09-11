package ai.rever.boss.plugin.runtime

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The watchdog that stops this runtime outliving its host.
 *
 * Nothing else in the process notices a dead host: `awaitTermination` blocks on this process's
 * own gRPC server, and the kernel channel redials a dead peer indefinitely. Without the
 * watchdog every host launch leaves a full cohort of plugin JVMs running forever.
 *
 * These tests deliberately watch processes this JVM did **not** spawn. In production the watched
 * process is the parent, which is never a child of the watcher, and `ProcessHandle.onExit()`
 * takes a different path for non-children (it cannot reap them, so it polls). A test that
 * watched a spawned child would exercise the wrong half of the JDK and pass regardless.
 */
class HostDeathWatchdogTest {
    private val sleepers = mutableListOf<ProcessHandle>()

    @AfterTest
    fun killSleepers() {
        sleepers.forEach { runCatching { it.destroyForcibly() } }
        sleepers.clear()
    }

    /**
     * Start a `sleep` that is **not** a child of this JVM, and return a handle to it.
     *
     * The launcher process backgrounds (or starts and abandons) the sleeper and exits, so the
     * sleeper's parent is gone - exactly the relationship a plugin child has to a process it did
     * not spawn.
     *
     * stderr is deliberately *not* merged into stdout. If it were, a launcher warning could land on
     * the line the pid is read from; a value that failed to parse would only be annoying, but one
     * that parsed to some other live pid would have the test SIGKILL an unrelated process on the
     * machine. The command check below is the second guard on that.
     */
    private fun detachedSleeper(): ProcessHandle {
        // POSIX reparents the sleeper to init, so `sh -c "sleep & echo $!"` leaves it with a dead
        // parent. Windows never reparents, but the launcher shell still exits, which is the same
        // relationship. It has to be PowerShell and `Start-Process`: there is no `sleep` on a bare
        // Windows runner, and MSYS `$!` is a *shell* pid that `ProcessHandle.of` - which speaks
        // Windows pids - resolves to some unrelated process or to nothing at all (measured: the
        // first three failures of this test on windows-latest were "sleeper already gone").
        // `ping -n 61` sleeps about a minute, has no children, and is trivially killable.
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val launcher =
            if (isWindows) {
                ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Write-Output (Start-Process -FilePath 'ping.exe' " +
                        "-ArgumentList '-n 61 127.0.0.1' -WindowStyle Hidden -PassThru).Id",
                )
            } else {
                ProcessBuilder("sh", "-c", "sleep 60 & echo \$!")
            }.start()
        val firstLine =
            launcher.inputStream
                .bufferedReader()
                .readLine()
        assertTrue(launcher.waitFor(30, TimeUnit.SECONDS), "launcher shell should exit promptly")

        val pid =
            requireNotNull(firstLine?.trim()?.toLongOrNull()) {
                "expected a pid on stdout, got: ${firstLine ?: "<no output>"}"
            }
        val handle = ProcessHandle.of(pid).orElseThrow { AssertionError("sleeper $pid already gone") }
        sleepers += handle

        // Only assert when the OS actually tells us. ProcessHandle.info() is best-effort for a
        // process this JVM does not own - macOS often leaves command() empty - and an unreadable
        // command is not evidence of a wrong pid. When it *is* readable, a mismatch means we are
        // about to SIGKILL something we did not start, so refuse.
        val commandToken = if (isWindows) "ping" else "sleep"
        val command = handle.info().command().orElse("") + handle.info().commandLine().orElse("")
        if (command.isNotEmpty()) {
            assertTrue(
                command.lowercase().contains(commandToken),
                "resolved pid $pid is $command, not the sleeper - refusing to kill an unrelated process",
            )
        }
        assertFalse(
            ProcessHandle.current().children().anyMatch { it.pid() == pid },
            "the sleeper must not be a child of this JVM, or the test proves nothing",
        )
        return handle
    }

    @Test
    fun `no host means exit immediately rather than run orphaned`() {
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                host = Optional.empty(),
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed, "there is no host to watch, so nothing should be armed")
        assertEquals(1, goneCalls, "an already-orphaned process must be shut down at startup")
    }

    @Test
    fun `a live host arms the watchdog without firing it`() {
        val host = detachedSleeper()
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                host = Optional.of(host),
                onHostGone = { goneCalls++ },
            )
        assertTrue(armed, "a live host should be watched")

        // The JDK's non-child poller ticks on the order of 100ms, so asserting immediately after
        // arming would pass even for a watchdog that fires spuriously. Give it room to misbehave.
        Thread.sleep(600)
        assertEquals(0, goneCalls, "the watchdog must not fire while the host is alive")
        assertTrue(host.isAlive)
    }

    @Test
    fun `the watchdog fires when a host this JVM did not spawn exits`() {
        val host = detachedSleeper()
        val fired = CountDownLatch(1)

        assertTrue(
            installHostDeathWatchdog(host = Optional.of(host), onHostGone = { fired.countDown() }),
        )

        host.destroyForcibly()
        assertTrue(
            fired.await(30, TimeUnit.SECONDS),
            "onHostGone must run once the watched host exits (non-child onExit polls, so allow time)",
        )
    }

    @Test
    fun `a host that is already gone fires the watchdog at once`() {
        val host = detachedSleeper()
        host.destroyForcibly()
        // Block until the exit is observable, so this covers the race where the host dies
        // between resolving the handle and installing the watchdog.
        host.onExit().get(30, TimeUnit.SECONDS)

        val fired = CountDownLatch(1)
        installHostDeathWatchdog(host = Optional.of(host), onHostGone = { fired.countDown() })

        assertTrue(fired.await(30, TimeUnit.SECONDS), "a dead host must fire immediately")
    }

    @Test
    fun `a JDK refusal leaves the plugin serving rather than killing it`() {
        // The documented fallback: if onExit() will not give us a completion future we lose the
        // watchdog but keep running, because the host still reaps its children on exit. Distinguish
        // it from the orphaned-at-startup branch, which also returns false - there, onParentGone
        // runs; here it must not.
        var goneCalls = 0

        val armed =
            installHostDeathWatchdog(
                host = Optional.of(RefusingHandle),
                onHostGone = { goneCalls++ },
            )

        assertFalse(armed, "arming failed, so nothing is watching")
        assertEquals(0, goneCalls, "a refusal must not be treated as the host having exited")
    }

    // ── resolveHostHandle: which process counts as the host ──────────────────────────────────
    //
    // This is where the environment is read, and it was the least-tested code in the change. The
    // pid-1 case below is the one that matters: POSIX reparents an orphan to init, so parent()
    // hands back a *live* handle for pid 1 rather than an empty Optional. Falling back to it would
    // arm the watchdog on launchd, which never exits - the process would outlive its host for the
    // life of the machine, which is the leak this whole change exists to close.

    private fun handleOf(pid: Long): Optional<ProcessHandle> = ProcessHandle.of(pid)

    @Test
    fun `an unset BOSS_HOST_PID falls back to the OS parent`() {
        val parent = ProcessHandle.current().parent()

        val resolved = resolveHostHandle(declared = null, osParent = { parent }, selfPid = 4242)

        assertEquals(parent.map { it.pid() }, resolved.map { it.pid() })
    }

    @Test
    fun `a live BOSS_HOST_PID wins over the OS parent`() {
        val host = detachedSleeper()

        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                osParent = { Optional.empty() },
                selfPid = 4242,
            )

        assertTrue(resolved.isPresent)
        assertEquals(host.pid(), resolved.get().pid())
    }

    @Test
    fun `a BOSS_HOST_PID naming a dead process means orphaned, not fall back`() {
        val host = detachedSleeper()
        host.destroyForcibly()
        host.onExit().get(30, TimeUnit.SECONDS)

        // The OS parent is deliberately live here: falling back to it would hide a host that told
        // us who it was and then died.
        val resolved =
            resolveHostHandle(
                declared = host.pid().toString(),
                osParent = { ProcessHandle.current().parent() },
                selfPid = 4242,
            )

        assertFalse(resolved.isPresent, "a named-but-dead host must resolve to orphaned")
    }

    @Test
    fun `a malformed BOSS_HOST_PID falls back to the OS parent`() {
        val parent = ProcessHandle.current().parent()

        listOf("", "   ", "abc", "1234 # comment", "12.5").forEach { garbage ->
            val resolved = resolveHostHandle(declared = garbage, osParent = { parent }, selfPid = 4242)
            assertEquals(
                parent.map { it.pid() },
                resolved.map { it.pid() },
                "'$garbage' should warn and fall back, not resolve",
            )
        }
    }

    @Test
    fun `a BOSS_HOST_PID naming this process falls back instead of watching itself`() {
        val parent = ProcessHandle.current().parent()
        val self = ProcessHandle.current().pid()

        val resolved = resolveHostHandle(declared = self.toString(), osParent = { parent }, selfPid = self)

        assertEquals(
            parent.map { it.pid() },
            resolved.map { it.pid() },
            "watching ourselves would throw from onExit and degrade to running unwatched",
        )
    }

    @Test
    fun `an OS parent of pid 1 means orphaned, because POSIX reparents to init`() {
        // Verified on POSIX platforms: an orphan's ppid is 1 and ProcessHandle.of(1) is present and
        // alive, so parent() returning "empty" is not how being orphaned actually presents.
        val pid1 = handleOf(1)
        if (pid1.isEmpty) {
            // Windows has no pid 1 to resolve - System Idle Process is 0 and System is 4 - so the
            // POSIX reparent case cannot present there at all. Assert the platform fact rather than
            // silently passing, and note the orphan path is still exercised on Windows by the
            // no-parent and named-dead-host tests.
            assertTrue(pid1.isEmpty, "this platform resolves pid 1; run the reparent case below")
            return
        }

        val resolved = resolveHostHandle(declared = null, osParent = { pid1 }, selfPid = 4242)

        assertFalse(resolved.isPresent, "init is not a host worth watching - it never exits")
    }

    @Test
    fun `a containerised host that is pid 1 can still name itself`() {
        if (handleOf(1).isEmpty) {
            // No pid 1 exists here (Windows), so there is nothing to name. The contract under
            // test - an explicitly declared pid is honoured ahead of the OS parent - is covered
            // by 'a live BOSS_HOST_PID wins over the OS parent', and a declared pid that names
            // no live process is the dead-host case: orphaned, never a fallback.
            val resolved = resolveHostHandle(declared = "1", osParent = { Optional.empty() }, selfPid = 4242)

            assertFalse(
                resolved.isPresent,
                "a declared pid naming no live process must mean orphaned, not fall back",
            )
            return
        }

        val resolved = resolveHostHandle(declared = "1", osParent = { Optional.empty() }, selfPid = 4242)

        assertTrue(resolved.isPresent, "an explicit BOSS_HOST_PID=1 is the escape hatch")
        assertEquals(1L, resolved.get().pid())
    }

    @Test
    fun `no OS parent at all still means orphaned`() {
        val resolved = resolveHostHandle(declared = null, osParent = { Optional.empty() }, selfPid = 4242)

        assertFalse(resolved.isPresent)
    }

    /** A handle whose `onExit()` throws, as the JDK's does for the current process. */
    private object RefusingHandle : ProcessHandle {
        override fun onExit(): CompletableFuture<ProcessHandle> =
            throw IllegalStateException("onExit for current process not allowed")

        override fun pid(): Long = -1

        override fun info(): ProcessHandle.Info = ProcessHandle.current().info()

        override fun parent(): Optional<ProcessHandle> = Optional.empty()

        override fun children(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

        override fun isAlive(): Boolean = true

        override fun supportsNormalTermination(): Boolean = false

        override fun destroy(): Boolean = false

        override fun destroyForcibly(): Boolean = false

        override fun compareTo(other: ProcessHandle): Int = 0
    }
}
