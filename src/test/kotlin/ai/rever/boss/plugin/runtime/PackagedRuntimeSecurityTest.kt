package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.IpcTransport
import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIRegistrationResponse
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.ipc.services.KernelServiceImpl
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackagedRuntimeSecurityTest {
    private class KernelUi : PluginUIServiceGrpcKt.PluginUIServiceCoroutineImplBase() {
        val registrations = AtomicInteger()
        val updates = AtomicInteger()

        override suspend fun registerUI(request: UIRegistration): UIRegistrationResponse {
            IpcCall.requireOwnProcess(request.processId)
            registrations.incrementAndGet()
            return UIRegistrationResponse.newBuilder().setSuccess(true).build()
        }

        override fun streamUI(requests: Flow<WidgetUpdate>): Flow<UIEvent> = flow {
            requests.collect {
                IpcCall.requireOwnProcess("runtime-fixture")
                assertEquals("runtime-security-fixture", it.surfaceId)
                updates.incrementAndGet()
                emit(UIEvent.getDefaultInstance())
            }
        }
    }

    @Test
    fun `fat jar registers heartbeats serves authenticated calls and sends UI over pinned TLS`() = runBlocking {
        val root = Files.createTempDirectory("packaged-runtime-").toFile()
        val runtime = File(System.getProperty("runtime.integration.jar"))
        IpcTransport.requireCompatibleRuntime(runtime.toPath())
        val plugin = fixturePlugin(root)
        val tokens = ProcessTokenRegistry()
        val kernelIdentity = IpcTlsIdentity.create()
        val childIdentity = IpcTlsIdentity.create()
        val childAddress = "tcp://127.0.0.1:${ServerSocket(0).use { it.localPort }}"
        val processToken = tokens.issue("runtime-fixture", expectedAddress = childAddress)
        val hostToken = ProcessTokenRegistry().issue("host")
        val kernel = KernelServiceImpl()
        val ui = KernelUi()
        val server = BossIpcServer("tcp://127.0.0.1:0", tokens, kernelIdentity).addService(kernel).addService(ui).start()
        val clients = mutableListOf<BossIpcClient>()
        var child: Process? = null
        try {
            val builder = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx256m", "-cp", runtime.absolutePath + File.pathSeparator + plugin.absolutePath,
                "ai.rever.boss.plugin.runtime.PluginProcessMainKt",
            ).redirectOutput(File(root, "stdout.log")).redirectError(File(root, "stderr.log"))
            builder.environment().putAll(mapOf(
                "BOSS_KERNEL_IPC_ADDR" to "tcp://127.0.0.1:${server.port}",
                "BOSS_IPC_ADDR" to childAddress,
                "BOSS_PROCESS_ID" to "runtime-fixture",
                "BOSS_PLUGIN_CLASSPATH" to plugin.absolutePath,
                "BOSS_HOST_PID" to ProcessHandle.current().pid().toString(),
                IpcEnvironment.PROCESS_TOKEN to processToken,
                IpcEnvironment.KERNEL_CERTIFICATE to kernelIdentity.certificateBase64,
                IpcEnvironment.SERVER_CERTIFICATE to childIdentity.certificateBase64,
                IpcEnvironment.SERVER_PRIVATE_KEY to childIdentity.privateKeyBase64(),
                IpcEnvironment.HOST_TOKEN to hostToken,
            ))
            child = builder.start()
            val host = BossIpcClient(childAddress, IpcClientCredentials(childIdentity.certificateBase64, hostToken))
                .also { clients += it }
            assertTrue(host.waitForReady(60_000), File(root, "stderr.log").readText().takeLast(8000))
            assertEquals("ready", StateServiceGrpcKt.StateServiceCoroutineStub(host.channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS).getState(StateKey.newBuilder().setKey("ready").build()).key)
            assertEquals(1, kernel.registeredCount)
            withTimeout(10_000) {
                while (ui.registrations.get() == 0 || ui.updates.get() == 0) delay(20)
            }
            val heartbeat = kernel.getLastHeartbeat("runtime-fixture")
            withTimeout(10_000) { while (kernel.getLastHeartbeat("runtime-fixture") == heartbeat) delay(20) }

            val wrong = BossIpcClient(childAddress, IpcClientCredentials(childIdentity.certificateBase64, "0".repeat(64)))
                .also { clients += it }
            val rejected = assertFailsWith<StatusException> {
                StateServiceGrpcKt.StateServiceCoroutineStub(wrong.channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getState(StateKey.newBuilder().setKey("ready").build())
            }
            assertEquals(Status.Code.UNAUTHENTICATED, rejected.status.code)
            val wrongServer = BossIpcClient(
                childAddress, IpcClientCredentials(IpcTlsIdentity.create().certificateBase64, hostToken),
            ).also { clients += it }
            assertTrue(!wrongServer.waitForReady(2000))
            tokens.revokeIfCurrent("runtime-fixture", processToken)
        } finally {
            child?.destroyForcibly()
            child?.waitFor(10, TimeUnit.SECONDS)
            clients.forEach { it.shutdown(0) }
            server.stop(0)
            root.deleteRecursively()
        }
    }

    private fun fixturePlugin(root: File): File {
        val destination = File(root, "fixture.jar")
        JarOutputStream(destination.outputStream()).use { output ->
            JarFile(System.getProperty("runtime.fixture.jar")).use { fixture ->
                for (entry in fixture.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    output.putNextEntry(JarEntry(entry.name))
                    fixture.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            output.write("""{"manifestVersion":1,"pluginId":"runtime-fixture","displayName":"Fixture","version":"1.0.0","apiVersion":"1.0.0","mainClass":"ai.rever.boss.plugin.runtime.RuntimeFixturePlugin"}""".toByteArray())
            output.closeEntry()
        }
        return destination
    }
}
