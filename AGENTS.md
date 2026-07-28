# boss-microkernel-runtime

The OOP plugin child JVM entry point. BossConsole spawns this JAR as a
classpath alongside each out-of-process plugin's JAR; the runtime hosts the
plugin's state holder, streams its widget tree back over gRPC, and proxies
plugin-context calls back to the kernel.

## Architecture

- `PluginProcessMain` - the `main()` for the child JVM. Loads the plugin's
  `Plugin` impl from the classpath via reflection.
- `RemotePluginContext` - mirror of `PluginContext`; every method delegates
  to a gRPC proxy under `ai.rever.boss.plugin.ipc.*` (the
  `plugin-api-ipc.jar` upstream dependency).
- `PluginStateHolder` - MVI base class for plugin state.
- `PluginUiClient` - the plugin's **client** of the host's `PluginUIService`:
  registers surfaces, streams the widget tree up, reads user events back.
  The plugin dials the kernel here, not the other way round - see below.
- `stateholders/` - concrete state holders for the in-house plugins. A plugin
  opts in by naming one in its `plugin.json` (`isolationMode:
  "out-of-process"` + `stateHolderClass`); `PluginProcessMain` then wires
  `PluginStateSyncService` around it. A holder whose constructor never calls
  `updateState` stays at version 0, and the host's `applyState` guard drops
  version-0 envelopes - so it loads, registers, heartbeats and renders
  *nothing*. Every holder must publish once from its constructor.
  `JupyterStateHolder` and `FlowStateHolder` are the two whose surfaces are
  the plugin's own data rather than a kernel provider's; both document
  precisely what a child JVM cannot do (Jupyter: no kernel execution, and no
  AI at any api version because `RemotePluginContext` is not a
  `PluginContext` and has no LLM provider; Flow: no run, and no persistence
  because `pluginStorageFactory` is null and the wire has no storage
  service).

## Tests

```bash
./gradlew test    # 254 tests
```

The four upstream jars are on the **test** compile classpath too
(`testImplementation`), not just `compileOnly` - without that the whole test
source set fails to compile on `ai.rever.boss.ipc.proto` and nothing runs.
`build.yml` runs `./gradlew build` on every pull request, which includes `test`, so CI
does run these.

## Build

Compile-time deps come from BossConsole's IPC contract jars:

- `boss-ipc-<ipcVersion>.jar` - gRPC + protobuf stubs
- `boss-ui-sdk-<ipcVersion>.jar` - widget-tree primitives
- `plugin-api-core-<ipcVersion>.jar` - `Plugin`, `PluginContext`, manifest
- `plugin-api-ipc-<ipcVersion>.jar` - the 19 gRPC proxies

These are published as release assets by BossConsole's CI.

Plus the plugin API contract itself, which comes from a **different** repo:

- `boss-plugin-api-<version>.jar` - `ai.rever.boss.plugin.api.*`, the provider
  interfaces `RemotePluginContext` implements. Published by
  risa-labs-inc/boss-plugin-api; pinned by the `boss.plugin.api.version`
  Gradle property and resolved from a sibling `boss_plugins/boss-plugin-api`
  checkout, BossConsole's already-fetched copy, or that repo's releases.
  It used to arrive folded into `plugin-api-core`; the plugin-platform
  decoupling stopped publishing it there. **Keep the pin equal to
  BossConsole's `boss-plugin-api` in `libs.versions.toml`** - the host loads
  this contract into its own classloader, and a mismatch is invisible to the
  IPC compat gate.

### Local development

```bash
# One-time, after cloning. Requires a sibling BossConsole checkout
# (or BOSSCONSOLE_DIR env var pointing at one).
./dev-setup.sh

# Build the fatJar.
./gradlew fatJar

# Drop into ~/.boss/plugins/ to test against your local BossConsole.
cp build/libs/boss-microkernel-runtime-*-all.jar ~/.boss/plugins/
```

### CI

CI sets `CI=true`, which makes `build.gradle.kts` skip the local-deps
branch and run the `downloadDeps` task instead - that pulls the four
upstream jars from
`https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest/download/`.

To pin a specific BossConsole release, pass `-Pupstream.source=...` or
the `bossconsole_release_tag` workflow input.

## UI transport direction

`ui_protocol.proto` makes the **plugin the client and the kernel the server**,
in both directions: the plugin calls `RegisterUI`, then opens `StreamUI` and
sends `WidgetUpdate`s up while reading `UIEvent`s back off the same call.

Do not reintroduce a `PluginUIService` implementation in this process. This repo
used to have one, and when BossConsole was corrected to serve the contract
(BossConsole #50) both ends sat waiting to be dialled - no plugin UI connected at
all, with no error anywhere. `PluginUiClientTest` stands up the kernel's half and
asserts the plugin dials it, which is the only way that failure is visible from
this side.

Two `StreamUI` details the client has to respect:

- The call carries no surface id - it binds to the surface of its **first**
  `WidgetUpdate`, so there is one call per surface and the first message must be
  a real tree (an update with neither `oneof` arm set is treated as malformed).
- The call **ending** unregisters the surface. Recovery is register-then-reopen,
  never a bare reconnect.

## Host lifetime is this process's lifetime

`PluginProcessMain` arms a watchdog before it connects and **halts with exit code 3** when the host
process goes away. Nothing else here would notice: `awaitTermination` blocks on this process's own
gRPC server, which the host's death does not touch, and the kernel channel is a `ManagedChannel`, so
it answers a dead peer by redialling for as long as the JVM lives. Orphans therefore used to
accumulate one cohort per host launch - 434 of them once held 27 GB and 45k threads on a developer
machine.

Which process counts as the host (`resolveHostHandle`):

1. `BOSS_HOST_PID`, if the host sets it. This makes the relationship a stated contract and survives
   an intermediate wrapper process. If it names a **dead** process the host is gone and we halt - we
   do *not* fall back, because the host told us who it was.
2. Otherwise this JVM's **OS parent**, which is correct only while BossConsole spawns the child
   directly - `ProcessSpawner` calls `ProcessBuilder.start()` with no wrapper today.

**An OS parent of pid 1 counts as orphaned, not as a host.** POSIX reparents an orphan to
init/launchd, so `ProcessHandle.current().parent()` returns a *live pid-1 handle* rather than an
empty `Optional` - verified on macOS, where an orphan's ppid is 1 and `ProcessHandle.of(1)` resolves
to `/sbin/launchd`. Watching init would never fire, so the JVM would outlive its host for the life of
the machine: the leak, through a narrower window. A host that genuinely is pid 1 (containerised) must
name itself in `BOSS_HOST_PID`, which is checked first and bypasses this rule.

A malformed `BOSS_HOST_PID`, or one naming this very process, warns and falls back to the parent
rather than failing silently.

If a launcher shell or supervisor is ever introduced between host and child *without* setting
`BOSS_HOST_PID`, the inferred parent becomes a short-lived process and every plugin halts at startup
with exit 3. The host would see immediate child deaths and no plugin UI, which looks nothing like
its cause - so set `BOSS_HOST_PID` when changing how children are spawned.

The host reaps its children on exit as well (`KernelBootstrap`'s shutdown hook, BossConsole#131).
Both halves are needed: a shutdown hook cannot run when the host is SIGKILLed or dies in native
code, and only the child covers that.

## Versioning

Two independent versions:

- **Runtime version** (`plugin.json#version`, `runtime.version` Gradle
  property): bumped per release.
- **IPC version** (`gradle.properties#ipc.version`, `plugin.json#minIpcVersion`):
  matches BossConsole's `IpcVersion.CURRENT` - the wire-format contract.
  Bumped only when the proto surface changes. The host refuses to spawn a
  runtime whose `minIpcVersion` is incompatible with its own IPC version.

## Release

`workflow_dispatch` on `.github/workflows/release.yml`:

1. Bumps `plugin.json#version`.
2. Resolves IPC version from `gradle.properties` (overridable).
3. Downloads upstream jars from BossConsole-Releases.
4. Builds the fatJar.
5. Computes SHA-256.
6. Creates a GitHub Release in this repo with the JAR attached.
7. POSTs metadata to the BOSS Plugin Store
   (`/plugin-store/github/metadata`) with `{githubUrl, sha256}`.

The store endpoint extracts the manifest from the JAR server-side and
uses the streaming-computed hash as the integrity anchor; the `sha256`
in the payload is a sanity check.
