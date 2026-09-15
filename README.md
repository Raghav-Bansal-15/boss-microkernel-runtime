# BOSS Microkernel Runtime

The shared runtime that BOSS loads into every out-of-process plugin child JVM.

This is not a plugin you install. It is the `main()` of the child process BossConsole spawns
when a plugin declares `isolationMode: out-of-process`, and the gRPC bridge that lets code
running in that child talk to the host as if it were in-process.

## What it does

- **Boots the child JVM.** `PluginProcessMain` reads `BOSS_PLUGIN_CLASSPATH`, extracts
  `META-INF/boss-plugin/plugin.json` from the plugin jar, builds a `ProcessManifest`, and
  connects back to the kernel through `ChildProcessBootstrap`.
- **Mirrors `PluginContext` across the process boundary.** `RemotePluginContext` wires roughly
  twenty gRPC provider proxies (auth, workspace, git, downloads, secrets, supabase, log,
  activeTabs, splitView, contextMenu, runConfig, panelEvent, roleManagement, project,
  notification and more). Providers that are inherently Compose-bound are deliberately null:
  `bookmarkDataProvider`, `applicationEventBus`, `pluginStorageFactory`, `clipboardProvider`,
  `browserService`.
- **Carries plugin state.** `PluginStateHolder<S, I, E>` is an MVI base class; the child's
  state is published to the host over `PluginStateSyncService`.
- **Ships ready-made state holders** under `stateholders/` for the in-house panels that run
  out of process: Console, Performance, Downloads, Git, Codebase, Bookmarks, Admin, Analytics,
  Rpa, RunConfigurations, SecretManager and TopOfMind.
- **Drives the UI from the child side.** `PluginUiClient` dials the kernel's `PluginUIService`.
  The direction matters: an earlier server-side implementation deadlocked both ends with no
  error output (BossConsole #50), and `PluginUiClientTest` exists to keep it that way.

## How a plugin uses it

A plugin opts in from its own manifest. It does not add a dependency:

```json
{
  "isolationMode": "out-of-process",
  "fallback": "in-process",
  "stateHolderClass": "ai.rever.boss.plugin.runtime.stateholders.ConsoleStateHolder"
}
```

The host puts this runtime's fat jar on the child classpath next to the plugin jar and uses
`ai.rever.boss.plugin.runtime.PluginProcessMainKt` as the entry point. `stateHolderClass` names
a class in **this** jar, not in the plugin.

A plugin can instead expose `registerRemote(RemotePluginContext)` on its main class. If it has
neither, the runtime logs that it is running in UI-only mode and carries on.

## Requirements

- JDK 17
- `apiVersion` 1.0.0, `minIpcVersion` 1.1.0. The host refuses to spawn a runtime whose IPC
  version it cannot talk to, so the runtime version and the IPC version move independently.
- gRPC, protobuf and netty native transports for macOS arm64/x64 and Linux arm64/x64. The
  **protobuf version must stay at or above BossConsole's pin**, or the child JVM dies during
  startup.
- Compose is bundled inside the fat jar: the child JVM owns its own copy rather than borrowing
  the host's.

## Build

```bash
./gradlew fatJar    # build/libs/boss-microkernel-runtime-<version>-all.jar
```

Local development needs `./dev-setup.sh` and a sibling `BossConsole` checkout, or `CI=true` to
force the `downloadDeps` path.

## Notes

- `systemPlugin: true`, `isDynamic: false`, `canUnload: false`, `loadPriority: 0`.
- Toolbox treats `ai.rever.boss.microkernel.runtime` as never hot-reloadable: it is a classpath
  component, not a loadable plugin, so updating it needs a restart.

See [AGENTS.md](AGENTS.md) for architecture and conventions.
