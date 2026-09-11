package ai.rever.boss.plugin.runtime

import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.WidgetTree
import ai.rever.boss.ipc.services.StateServiceImpl

/** Loaded reflectively by the actual packaged runtime in a separate JVM. */
class RuntimeFixturePlugin {
    fun registerRemote(context: RemotePluginContext) {
        context.addProcessService(StateServiceImpl())
        context.uiService.registerSurface(
            UIRegistration.newBuilder()
                .setProcessId(context.processId)
                .setSurfaceId("runtime-security-fixture")
                .setSurfaceType("panel")
                .setDisplayName("Runtime security fixture")
                .setInitialTree(WidgetTree.getDefaultInstance())
                .build(),
        )
    }
}
