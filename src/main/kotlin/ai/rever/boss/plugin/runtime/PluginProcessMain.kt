package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.HealthContract
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.zip.ZipFile

private val logger = LoggerFactory.getLogger("PluginProcessMain")

/**
 * Minimal fields from META-INF/boss-plugin/plugin.json needed for process bootstrap.
 * Full manifest parsing happens inside the plugin's own classloader.
 */
@Serializable
private data class PluginJsonManifest(
    val pluginId: String = "",
    val displayName: String = "",
    val version: String = "1.0.0",
    val mainClass: String = "",
    val description: String = "",
    val stateHolderClass: String = "",
    val isolationMode: String = "",
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Generic entry point for out-of-process plugin processes.
 *
 * Every plugin that runs out-of-process uses this as its main class.
 * The actual plugin code is loaded from BOSS_PLUGIN_CLASSPATH via URLClassLoader.
 *
 * Steps:
 * 1. Read BOSS_PLUGIN_CLASSPATH — path to the plugin JAR
 * 2. Read META-INF/boss-plugin/plugin.json from the JAR
 * 3. Build a ProcessManifest and register with the kernel
 * 4. Dial the kernel's PluginUIService (see PluginUiClient) and start this process's own server
 * 5. Await termination
 */
fun main() = runBlocking {
    logger.info("Boss plugin runtime starting...")

    val bootstrap = ChildProcessBootstrap()

    // 1. Locate plugin JAR
    val classpathEnv = System.getenv("BOSS_PLUGIN_CLASSPATH")
        ?: throw IllegalStateException("BOSS_PLUGIN_CLASSPATH environment variable not set")

    logger.info("Loading plugin from: {}", classpathEnv)

    // 2. Read plugin manifest from JAR
    val pluginManifest = readPluginManifest(classpathEnv)
        ?: throw IllegalStateException(
            "No META-INF/boss-plugin/plugin.json found in JAR: $classpathEnv"
        )

    logger.info(
        "Loaded plugin: {} v{} (class={})",
        pluginManifest.displayName, pluginManifest.version, pluginManifest.mainClass,
    )

    // 3. Build ProcessManifest for kernel registration
    val processManifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setDisplayName(pluginManifest.displayName)
        .setProcessType(ProcessType.PROCESS_TYPE_PLUGIN)
        .setVersion(pluginManifest.version)
        .setMainClass(pluginManifest.mainClass)
        .setBehaviorSpec(pluginManifest.description)
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5_000)
                .setStartupTimeoutMs(30_000)
                .build()
        )
        .build()

    // 4. Connect to kernel
    val connection = bootstrap.connect(processManifest)

    // 5. Create RemotePluginContext with kernel channel for data provider access
    //    and a reference to this process's gRPC server so plugins can register
    //    their own services via `ctx.addProcessService(...)` (e.g. the terminal
    //    plugin hosting `TerminalService` for the host's grid renderer).
    //
    //    UI is not one of those services: `PluginUIService` is hosted by the KERNEL and dialled
    //    from here (see PluginUiClient). Adding a UI service to this process's server is what the
    //    runtime used to do, and it left both ends waiting to be called.
    val remoteContext = RemotePluginContext(
        processId = bootstrap.processId,
        uiService = PluginUiClient(kernelChannel = connection.kernelClient.channel),
        kernelChannel = connection.kernelClient.channel,
        processServer = connection.processServer,
    )

    // 6. Load and initialize the plugin's state holder (if declared)
    val stateHolderClass = pluginManifest.stateHolderClass
    if (stateHolderClass.isNotEmpty()) {
        try {
            val holderClazz = Class.forName(stateHolderClass)
            val stateHolder = try {
                // Try constructor(CoroutineScope, RemotePluginContext)
                holderClazz
                    .getConstructor(kotlinx.coroutines.CoroutineScope::class.java, RemotePluginContext::class.java)
                    .newInstance(remoteContext.pluginScope, remoteContext)
            } catch (_: NoSuchMethodException) {
                // Fall back to constructor(CoroutineScope)
                holderClazz
                    .getConstructor(kotlinx.coroutines.CoroutineScope::class.java)
                    .newInstance(remoteContext.pluginScope)
            }

            logger.info("State holder loaded: {}", stateHolderClass)

            // Wire PluginStateSyncService if the holder extends PluginStateHolder
            @Suppress("UNCHECKED_CAST")
            if (stateHolder is PluginStateHolder<*, *, *>) {
                val syncService = createStateSyncService(
                    pluginId = pluginManifest.pluginId,
                    instanceId = bootstrap.processId,
                    stateHolder = stateHolder as PluginStateHolder<Any, Any, Any>,
                    scope = remoteContext.pluginScope,
                )
                connection.processServer.addService(syncService)
                logger.info("PluginStateSyncService wired for: {}", pluginManifest.pluginId)
            }
        } catch (e: Exception) {
            logger.error("Failed to load state holder: {}", stateHolderClass, e)
        }
    } else {
        // Try loading mainClass and calling registerRemote via reflection
        if (pluginManifest.mainClass.isNotEmpty()) {
            try {
                val pluginClazz = Class.forName(pluginManifest.mainClass)
                val pluginInstance = pluginClazz.getDeclaredConstructor().newInstance()

                // Check for registerRemote(RemotePluginContext) method
                val registerMethod = pluginClazz.getMethod("registerRemote", RemotePluginContext::class.java)
                registerMethod.invoke(pluginInstance, remoteContext)
                logger.info("Plugin registered via registerRemote: {}", pluginManifest.mainClass)
            } catch (_: NoSuchMethodException) {
                logger.info("Plugin {} has no registerRemote method — running in UI-only mode", pluginManifest.pluginId)
            } catch (e: Exception) {
                logger.error("Failed to load plugin class: {}", pluginManifest.mainClass, e)
            }
        }
    }

    // 7. Start gRPC server
    connection.startServer()

    logger.info(
        "Plugin process {} running on: {}",
        bootstrap.processId, bootstrap.processAddress,
    )

    // 8. Await termination
    connection.awaitTermination()

    // Cleanup
    remoteContext.dispose()
}

/**
 * Create a PluginStateSyncService with generic serialization using JSON.
 * Uses kotlinx.serialization for state and intent type resolution.
 */
@Suppress("UNCHECKED_CAST")
private fun createStateSyncService(
    pluginId: String,
    instanceId: String,
    stateHolder: PluginStateHolder<Any, Any, Any>,
    scope: kotlinx.coroutines.CoroutineScope,
): PluginStateSyncService<Any, Any> {
    return PluginStateSyncService(
        pluginId = pluginId,
        instanceId = instanceId,
        stateHolder = stateHolder,
        serializeState = { state ->
            try {
                // StateWireJson, not a bare Json: the latter omits every property still at its
                // default, which encoded a fresh state as `{}` and silently dropped the
                // "this is unavailable out-of-process" flags whenever they were false.
                StateWireJson.encodeToString(
                    kotlinx.serialization.serializer(state!!::class.java),
                    state,
                ).toByteArray()
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("PluginProcessMain")
                    .warn("State serialization failed for {}, falling back to toString(): {}",
                        state?.javaClass?.simpleName, e.message)
                state.toString().toByteArray()
            }
        },
        deserializeIntent = { intentType, payloadBytes ->
            resolveIntentDeserializer(stateHolder, intentType, payloadBytes)
        },
        stateTypeName = stateHolder::class.java.simpleName,
        scope = scope,
    )
}

/**
 * Resolve intent deserialization based on the state holder type.
 * Maps intent type strings to concrete intent objects for each known state holder.
 *
 * `internal` rather than private so the arm table itself is testable. A holder
 * whose arm is missing here loads, registers, heartbeats and publishes state, and
 * then silently drops every intent the host sends — indistinguishable from a UI
 * whose buttons do nothing. Deleting an arm has to fail a test.
 */
internal fun resolveIntentDeserializer(
    stateHolder: PluginStateHolder<*, *, *>,
    intentType: String,
    payloadBytes: ByteArray,
): Any? {
    val payloadStr = if (payloadBytes.isNotEmpty()) String(payloadBytes) else ""

    return when (stateHolder) {
        is ai.rever.boss.plugin.runtime.stateholders.ConsoleStateHolder -> {
            when (intentType) {
                "SetFilter" -> ai.rever.boss.plugin.runtime.stateholders.ConsoleIntent.SetFilter(
                    ai.rever.boss.plugin.runtime.stateholders.ConsoleLogFilter.valueOf(payloadStr.ifEmpty { "ALL" })
                )
                "SetSearchQuery" -> ai.rever.boss.plugin.runtime.stateholders.ConsoleIntent.SetSearchQuery(payloadStr)
                "ToggleAutoScroll" -> ai.rever.boss.plugin.runtime.stateholders.ConsoleIntent.ToggleAutoScroll
                "ClearLogs" -> ai.rever.boss.plugin.runtime.stateholders.ConsoleIntent.ClearLogs
                else -> null
            }
        }
        is ai.rever.boss.plugin.runtime.stateholders.PerformanceStateHolder -> {
            when (intentType) {
                "RequestGC" -> ai.rever.boss.plugin.runtime.stateholders.PerformanceIntent.RequestGC
                "ExportMetrics" -> ai.rever.boss.plugin.runtime.stateholders.PerformanceIntent.ExportMetrics
                else -> null
            }
        }
        is ai.rever.boss.plugin.runtime.stateholders.DownloadsStateHolder -> {
            when (intentType) {
                "Pause" -> ai.rever.boss.plugin.runtime.stateholders.DownloadsIntent.Pause(payloadStr)
                "Resume" -> ai.rever.boss.plugin.runtime.stateholders.DownloadsIntent.Resume(payloadStr)
                "Cancel" -> ai.rever.boss.plugin.runtime.stateholders.DownloadsIntent.Cancel(payloadStr)
                "Remove" -> ai.rever.boss.plugin.runtime.stateholders.DownloadsIntent.Remove(payloadStr)
                "ClearCompleted" -> ai.rever.boss.plugin.runtime.stateholders.DownloadsIntent.ClearCompleted
                else -> null
            }
        }
        is ai.rever.boss.plugin.runtime.stateholders.GitStateHolder -> {
            when (intentType) {
                "RefreshStatus" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.RefreshStatus
                "RefreshLog" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.RefreshLog()
                "Stage" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.Stage(payloadStr)
                "Unstage" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.Unstage(payloadStr)
                "StageAll" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.StageAll
                "UnstageAll" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.UnstageAll
                "DiscardChanges" -> ai.rever.boss.plugin.runtime.stateholders.GitIntent.DiscardChanges(payloadStr)
                else -> null
            }
        }
        // A holder with no arm here loads, registers, heartbeats and publishes
        // state — and then silently drops every intent the host sends it, which
        // looks exactly like a UI whose buttons do nothing. Each of the four
        // below owns its decoder next to its intent type.
        is ai.rever.boss.plugin.runtime.stateholders.DockerStateHolder -> {
            ai.rever.boss.plugin.runtime.stateholders.decodeDockerIntent(intentType, payloadStr)
        }
        is ai.rever.boss.plugin.runtime.stateholders.KubernetesStateHolder -> {
            ai.rever.boss.plugin.runtime.stateholders.decodeKubernetesIntent(intentType, payloadStr)
        }
        is ai.rever.boss.plugin.runtime.stateholders.AtlasStateHolder -> {
            ai.rever.boss.plugin.runtime.stateholders.decodeAtlasIntent(intentType, payloadStr)
        }
        is ai.rever.boss.plugin.runtime.stateholders.ToolCreatorStateHolder -> {
            ai.rever.boss.plugin.runtime.stateholders.decodeToolCreatorIntent(intentType, payloadStr)
        }
        else -> {
            logger.debug("No intent deserializer for {}: {}", stateHolder::class.java.simpleName, intentType)
            null
        }
    }
}

private fun readPluginManifest(jarPath: String): PluginJsonManifest? {
    return try {
        ZipFile(jarPath).use { zip ->
            val entry = zip.getEntry("META-INF/boss-plugin/plugin.json") ?: return null
            val content = zip.getInputStream(entry).bufferedReader().readText()
            json.decodeFromString<PluginJsonManifest>(content)
        }
    } catch (e: Exception) {
        logger.error("Failed to read plugin manifest from: {}", jarPath, e)
        null
    }
}
