/**
 * Standalone build for the microkernel runtime JAR.
 *
 * The runtime is the entry point for OOP plugin child JVMs spawned by
 * BossConsole's host. Its compile-time deps come from BossConsole's
 * IPC contract (boss-ipc, boss-ui-sdk, plugin-api-core, plugin-api-ipc),
 * which are published as release assets on the BossConsole repo
 * (Phase 1 of the migration). At build time we either use sibling
 * repo build outputs (local dev) or download those release assets (CI).
 *
 * The fatJar bundles everything the child JVM needs onto its own classpath
 * — no further deps at spawn time other than the plugin's own JAR.
 *
 * Versioning: the project version is the *runtime* version (bumped per
 * release). It is independent of `IpcVersion.CURRENT`, which lives inside
 * the boss-ipc.jar this module depends on. The runtime's `plugin.json`
 * declares `minIpcVersion` so the host can refuse to launch an
 * incompatible runtime — see `IpcVersion` in boss-ipc and the spawn-time
 * gate in BossConsole's `OutOfProcessPluginSpawnerImpl`.
 */
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    // Compose Multiplatform — Compose runtime/foundation/ui must ship inside
    // the fatJar because OOP plugins loaded by this runtime use Compose
    // (PluginUIServiceImpl streams widget trees). The host process for an
    // OOP child JVM is *this* runtime, so it owns the Compose runtime.
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "ai.rever.boss.microkernel.runtime"
version = providers.gradleProperty("runtime.version").orElse("1.0.0").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// ─── Upstream IPC jars ─────────────────────────────────────────────────────
//
// Local dev: sibling BossConsole/build/upstream-artifacts/* (run dev-setup.sh
// once, or `./gradlew assembleUpstreamJars` in BossConsole and the symlink
// pulls them in).
//
// CI: jars downloaded into build/downloaded-deps/ by the `downloadDeps`
// task before compile.
// boss-ipc tracks the IPC wire-format version (semver per IpcVersion bump policy).
// The other contract jars (boss-ui-sdk, plugin-api-core, plugin-api-ipc) are
// versioned independently. Split so a minor IPC bump like 1.0.0 → 1.1.0 does
// not falsely imply the other jars rev'd too.
// Read `ipc.version` — the same property gradle.properties declares and the release
// workflow passes via -Pipc.version. Keep these in lockstep with BossConsole's
// IpcVersion.CURRENT and the published boss-ipc-<ver>.jar (do NOT default ahead of what
// is actually published).
val bossIpcVersion: String = providers.gradleProperty("ipc.version").orElse("1.0.0").get()
val contractVersion: String = providers.gradleProperty("contract.version").orElse("1.0.0").get()

// CI: jars downloaded into build/downloaded-deps/ by the `downloadDeps` task before compile.
val explicitBossConsoleDir = providers.gradleProperty("bossconsole.dir")
    .orElse(providers.environmentVariable("BOSSCONSOLE_DIR"))
val bossConsoleDir = file(explicitBossConsoleDir.orElse("../../BossConsole").get())
val localUpstreamDir = bossConsoleDir.resolve("build/upstream-artifacts")
val useLocalDependencies: Boolean = explicitBossConsoleDir.isPresent ||
    (System.getenv("CI") != "true" && localUpstreamDir.resolve("boss-ipc-$bossIpcVersion.jar").exists())

val upstreamJarDir: File = if (useLocalDependencies) {
    localUpstreamDir
} else {
    file("build/downloaded-deps")
}

val upstreamJars = listOf(
    "boss-ipc-$bossIpcVersion.jar",
    "boss-ui-sdk-$contractVersion.jar",
    "plugin-api-core-$contractVersion.jar",
    "plugin-api-ipc-$contractVersion.jar",
)

// ─── boss-plugin-api: the plugin API contract itself ───────────────────────
//
// `ai.rever.boss.plugin.api.*` — the provider interfaces RemotePluginContext implements — does NOT
// come from BossConsole. It ships from risa-labs-inc/boss-plugin-api, and BossConsole consumes it
// the same way (see plugin-platform/plugin-api-core/build.gradle.kts, which filters this jar to that
// one package). It used to reach us folded into plugin-api-core-<ver>.jar; the plugin-platform
// decoupling stopped publishing it there, so this repo stopped compiling. Sourcing it directly is
// what every other plugin already does.
//
// Keep the pin equal to BossConsole's `boss-plugin-api` in libs.versions.toml: the host loads this
// contract into its own classloader, and a child JVM built against a different revision of it is
// exactly the mismatch the IPC compat gate cannot see.
val bossPluginApiVersion: String =
    providers.gradleProperty("boss.plugin.api.version").orElse("1.0.90").get()
val bossPluginApiJarName = "boss-plugin-api-$bossPluginApiVersion.jar"

// Resolution order mirrors the upstream jars: locally built sibling first (both live under
// boss_plugins/), then the copy BossConsole already fetched for its own build, then a download.
val bossPluginApiCandidates =
    listOf(
        file("../boss-plugin-api/build/libs/$bossPluginApiJarName"),
        bossConsoleDir.resolve("plugin-platform/plugin-api-core/build/api-contract/$bossPluginApiJarName"),
    )
val bossPluginApiJar: File =
    bossPluginApiCandidates.firstOrNull { it.exists() }
        ?: file("build/downloaded-deps/$bossPluginApiJarName")

dependencies {
    // The four IPC-contract jars from BossConsole. compileOnly so they
    // appear on the compile classpath but we control bundling explicitly
    // in the fatJar task below.
    upstreamJars.forEach { jar ->
        compileOnly(files("$upstreamJarDir/$jar"))
    }
    compileOnly(files(bossPluginApiJar))

    // Tests need the same contract, and `compileOnly` does not reach the test source set — without
    // this the suite cannot even be compiled, which is how the transport inversion went unnoticed
    // here. Runtime too: the tests stand up real gRPC servers against these stubs.
    upstreamJars.forEach { jar ->
        testImplementation(files("$upstreamJarDir/$jar"))
    }
    testImplementation(files(bossPluginApiJar))

    // ...and again for tests. `compileOnly` does not reach the test compile
    // classpath, so without this `src/test` cannot see `ai.rever.boss.ipc.proto`
    // and the *whole test source set* fails to compile — which is exactly what it
    // was doing: `PluginStateSyncTest` and `PluginOopIntegrationTest` have never
    // run, because CI only builds `fatJar` and never invokes `test`.
    upstreamJars.forEach { jar ->
        testImplementation(files("$upstreamJarDir/$jar"))
    }

    // Transitive runtime libs. Versions match BossConsole's libs.versions.toml.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // gRPC + protobuf + netty. boss-ipc declares these as `api` so they
    // need to resolve to the same coordinates here for a self-contained
    // child JVM classpath. We also need them at compile time because the
    // generated stubs in boss-ipc reference them.
    // protobuf must be >= the protobuf pin in BossConsole's libs.versions.toml:
    // the boss-ipc gencode is generated with that version and the protobuf
    // runtime refuses to load gencode newer than itself (child JVM dies at
    // startup with ProtobufRuntimeVersionException). Keep in lockstep.
    implementation("io.grpc:grpc-netty:1.84.0")
    implementation("io.grpc:grpc-protobuf:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")
    implementation("io.grpc:grpc-util:1.84.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("io.grpc:grpc-kotlin-stub:1.5.0")
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    implementation("com.google.protobuf:protobuf-kotlin:4.35.1")
    implementation("io.netty:netty-transport-native-unix-common:4.2.17.Final")
    implementation("io.netty:netty-transport-native-kqueue:4.2.17.Final:osx-aarch_64")
    implementation("io.netty:netty-transport-native-kqueue:4.2.17.Final:osx-x86_64")
    implementation("io.netty:netty-transport-native-epoll:4.2.17.Final:linux-aarch_64")
    implementation("io.netty:netty-transport-native-epoll:4.2.17.Final:linux-x86_64")

    // Compose runtime — bundled into fatJar so OOP plugins can use it.
    // Versions match BossConsole.
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext used by plugin Component classes.
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.decompose:extensions-compose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("com.arkivanov.essenty:state-keeper:2.5.0")
    implementation("com.arkivanov.essenty:back-handler:2.5.0")
    implementation("com.arkivanov.essenty:instance-keeper:2.5.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// ─── downloadDeps: pull upstream jars from BossConsole release ────────────
tasks.register("downloadDeps") {
    group = "build setup"
    description = "Download upstream IPC jars from BossConsole release assets (CI / fresh-clone use)."
    val out = file("build/downloaded-deps")
    val jars = upstreamJars
    val source = providers.gradleProperty("upstream.source").orElse(
        // Default: latest release on the public BossConsole-Releases repo.
        // Override with -Pupstream.source=https://github.com/.../tag/vX.Y.Z
        "https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest/download"
    )
    outputs.dir(out)
    doLast {
        out.mkdirs()
        val baseUrl = source.get().trimEnd('/')
        jars.forEach { jar ->
            val dest = File(out, jar)
            if (dest.exists() && dest.length() > 0) {
                logger.lifecycle("✓ already present: ${dest.name} (${dest.length() / 1024} KB)")
                return@forEach
            }
            val url = "$baseUrl/$jar"
            logger.lifecycle("↓ $url")
            val conn = URI(url).toURL().openConnection().apply {
                setRequestProperty("User-Agent", "boss-microkernel-runtime-build/1.0")
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            conn.getInputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!dest.exists() || dest.length() == 0L) {
                throw GradleException("Failed to download $jar from $url")
            }
            logger.lifecycle("  ${dest.length() / 1024} KB")
        }
    }
}

// ─── downloadApiContract: pull boss-plugin-api from its own release repo ───
//
// Separate from downloadDeps because it comes from a different repository: the BossConsole
// contract jars share one release base URL, this one does not.
tasks.register("downloadApiContract") {
    group = "build setup"
    description = "Download the pinned boss-plugin-api jar (CI / fresh-clone use)."
    val dest = bossPluginApiJar
    val version = bossPluginApiVersion
    val name = bossPluginApiJarName
    outputs.file(dest)
    doLast {
        if (dest.exists() && dest.length() > 0) {
            logger.lifecycle("✓ already present: ${dest.name} (${dest.length() / 1024} KB)")
            return@doLast
        }
        dest.parentFile.mkdirs()
        val url = "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v$version/$name"
        logger.lifecycle("↓ $url")
        val conn = URI(url).toURL().openConnection().apply {
            setRequestProperty("User-Agent", "boss-microkernel-runtime-build/1.0")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        conn.getInputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (!dest.exists() || dest.length() == 0L) {
            throw GradleException("Failed to download $name from $url")
        }
        logger.lifecycle("  ${dest.length() / 1024} KB")
    }
}

tasks.named("compileKotlin") {
    if (!useLocalDependencies) {
        dependsOn("downloadDeps")
    }
    // Independent of useLocalDependencies: the API contract has its own resolution order and may
    // need downloading even when the BossConsole jars are sitting in a sibling checkout.
    if (!bossPluginApiJar.exists()) {
        dependsOn("downloadApiContract")
    }
}

// ─── fatJar: bundle runtime classes + upstream + transitive runtime libs ──
tasks.jar {
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Self-contained runtime JAR for child JVM spawn (matches the legacy in-tree :fatJar output)."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Original dependency signatures cannot authenticate a newly assembled, flattened JAR.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
    }

    // Our merged service files first (EXCLUDE ensures these win over deps' SPIs).
    from("src/main/resources")
    // Our compiled module classes.
    with(tasks.jar.get())
    // The upstream IPC jars (compileOnly) — must be bundled or the child JVM has nothing to talk to.
    upstreamJars.forEach { jar ->
        from(zipTree(file("$upstreamJarDir/$jar")))
    }
    // The API contract too: the child JVM loads these interfaces itself — it does not share the
    // host's classloader — so leaving them out is a NoClassDefFoundError on the first provider call.
    from(zipTree(bossPluginApiJar))
    // Everything else from the runtime classpath (kotlinx, slf4j, grpc, netty, protobuf).
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// ─── publishMetadata: print the plugin-store payload (release.yml uses sha256) ──
tasks.register("computeFatJarSha256") {
    group = "publishing"
    description = "Compute SHA-256 of the produced fatJar; prints to stdout."
    dependsOn("fatJar")
    doLast {
        val jar = layout.buildDirectory.file("libs/boss-microkernel-runtime-$version-all.jar")
            .get().asFile
        if (!jar.exists()) throw GradleException("fatJar output not found: $jar")
        val md = MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        logger.lifecycle("fatJar=${jar.absolutePath}")
        logger.lifecycle("size=${jar.length()}")
        logger.lifecycle("sha256=$hex")
        // Also emit machine-readable form for CI shell `eval`.
        File(layout.buildDirectory.get().asFile, "fatjar-sha256.txt").writeText(hex)
        File(layout.buildDirectory.get().asFile, "fatjar-size.txt").writeText(jar.length().toString())
    }
}

// Exercise the packaged runtime and a tiny fixture plugin, not the Gradle test classpath.
val integrationFixtureJar = tasks.register<Jar>("integrationFixtureJar") {
    dependsOn(tasks.testClasses)
    archiveClassifier.set("integration-fixture")
    from(sourceSets.test.get().output) {
        include("ai/rever/boss/plugin/runtime/RuntimeFixturePlugin*.class")
    }
}

tasks.test {
    dependsOn("fatJar", integrationFixtureJar)
    systemProperty("runtime.integration.jar", tasks.named<Jar>("fatJar").flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("runtime.fixture.jar", integrationFixtureJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    // Synthetic inherited authority makes subprocess scrubbing tests exercise real environment inheritance.
    listOf("BOSS_PROCESS_TOKEN", "BOSS_KERNEL_TLS_CERT", "BOSS_IPC_TLS_CERT", "BOSS_IPC_TLS_KEY", "BOSS_HOST_TOKEN").forEach {
        environment(it, "synthetic-inherited")
    }
}
