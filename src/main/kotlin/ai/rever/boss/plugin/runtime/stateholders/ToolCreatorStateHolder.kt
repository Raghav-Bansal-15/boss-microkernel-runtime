package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

// region State

/**
 * The Tool Creator surface, mirrored for a host-side renderer.
 *
 * The panel is a launcher, not a browse surface: an environment banner, a form,
 * and a list of builds with their logs. All three mirror cleanly. What does not
 * is the *end* of the flow — see [ToolCreatorStateHolder]. Three capability flags
 * carry that outward so a renderer offers no button it cannot honour.
 */
@Serializable
data class ToolCreatorState(
    val form: ToolCreatorForm = ToolCreatorForm(),
    val environment: ToolCreatorEnvironment = ToolCreatorEnvironment(),
    val jobs: List<ToolCreatorJob> = emptyList(),
    /** True once the holder has published its first state. */
    val ready: Boolean = false,
    /**
     * Whether the bundled scaffold templates were found on this child's
     * classpath. False means the holder was loaded without the tool-creator jar
     * beside it and cannot scaffold anything.
     */
    val scaffoldAvailable: Boolean = false,
    /**
     * Always false out-of-process — the plugin finishes by opening an AI CLI in a
     * BossTerm tab, and `openTab` is an inherited no-op on the IPC proxy. The
     * command to run by hand is put in the job log instead, which is what the
     * plugin itself does when `splitViewOperations` is null.
     */
    val terminalHandoffAvailable: Boolean = false,
    /**
     * Always false out-of-process — creating the GitHub repo needs a Plugin Store
     * publish key from `pluginStoreApiKeyProvider`, `Nothing? = null` here.
     */
    val githubAvailable: Boolean = false,
    /** Always false out-of-process — the host's plugin storage is not on the wire. */
    val persistenceAvailable: Boolean = false,
    /** Every selectable agent with its display name, so the host can render the picker. */
    val agentOptions: List<ToolCreatorAgentOption> =
        ToolCreatorAgent.entries.map { ToolCreatorAgentOption(it, it.displayName) },
    /** Every selectable permission with its display text. */
    val permissionOptions: List<ToolCreatorPermissionOption> =
        ToolCreatorPermission.entries.map { ToolCreatorPermissionOption(it, it.id, it.label, it.description) },
)

/**
 * One selectable agent, as data rather than as an enum - this is what carries the display name
 * across the wire. See [ToolCreatorAgent].
 */
@Serializable
data class ToolCreatorAgentOption(val agent: ToolCreatorAgent, val displayName: String)

/** One selectable permission, as data. See [ToolCreatorPermission]. */
@Serializable
data class ToolCreatorPermissionOption(
    val permission: ToolCreatorPermission,
    val id: String,
    val label: String,
    val description: String,
)

/** The creation form. Mirrors the plugin's `FormState`, minus its Compose dialog. */
@Serializable
data class ToolCreatorForm(
    val toolName: String = "",
    val description: String = "",
    val permissions: List<ToolCreatorPermission> = listOf(ToolCreatorPermission.READ_FILES),
    val agent: ToolCreatorAgent = ToolCreatorAgent.CLAUDE_CODE,
    val parentDir: String = "",
    /**
     * Validation message for the current form, or null when it is buildable.
     * Computed here so the host renders the plugin's own rules rather than
     * reimplementing them.
     */
    val error: String? = null,
    // Derived names, so a renderer can show the plan without duplicating the rules.
    val slug: String = "",
    val packageName: String = "",
    val classPrefix: String = "",
    val pluginId: String = "",
    val repoName: String = "",
    val targetDir: String = "",
)

/**
 * What the machine can do. Mirrors the plugin's `EnvStatus`, with `gh` reported
 * but never used — see [ToolCreatorState.githubAvailable].
 */
@Serializable
data class ToolCreatorEnvironment(
    val missingAgents: List<ToolCreatorAgent> = emptyList(),
    val gitAvailable: Boolean = false,
    val ghInstalled: Boolean = false,
    val checked: Boolean = false,
)

/** One tool build. Several can run concurrently, each with its own log. */
@Serializable
data class ToolCreatorJob(
    val id: Long,
    val toolName: String,
    val agent: ToolCreatorAgent,
    val path: String,
    val status: ToolCreatorJobStatus,
    val log: List<String> = emptyList(),
    /**
     * The command the user must run themselves to reach the AI CLI, because the
     * terminal handoff is unavailable here. Empty until the build succeeds.
     */
    val handoffCommand: String = "",
)

@Serializable
enum class ToolCreatorJobStatus { RUNNING, SUCCESS, FAILED }

/**
 * Mirrors the plugin's `CliAgent`, including the launch command per agent.
 *
 * **[displayName] and [binary] do not travel.** kotlinx.serialization encodes an enum as its
 * serial name alone, so a host receives `"CLAUDE_CODE"`. Both are used child-side; what the
 * renderer needs is published as [ToolCreatorState.agentOptions].
 */
@Serializable
enum class ToolCreatorAgent(val displayName: String, val binary: String) {
    CLAUDE_CODE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    GEMINI("Gemini", "gemini"),
    OPENCODE("OpenCode", "opencode"),
    ;

    /**
     * Shell command that opens the CLI inside the scaffolded repo with the
     * tool-creator skill already engaged. Kept apostrophe-free so it survives
     * shell quoting untouched — the plugin's own constraint.
     */
    fun launchCommand(): String = when (this) {
        CLAUDE_CODE -> "claude \"/tool-creator\""
        CODEX ->
            "codex \"Load the tool-creator skill in .codex/skills/tool-creator/SKILL.md " +
                "and follow it to build this tool.\""

        GEMINI -> "gemini -i \"Follow the tool-creator instructions in GEMINI.md to build this tool.\""
        OPENCODE ->
            "opencode --prompt \"Follow the tool-creator command in " +
                ".opencode/command/tool-creator.md to build this tool.\""
    }
}

/**
 * Mirrors the plugin's `ToolPermission`. Informational: it shapes the skill and README.
 *
 * **[id], [label] and [description] do not travel** - an enum reaches the host as its serial
 * name only. The renderable form is [ToolCreatorState.permissionOptions].
 */
@Serializable
enum class ToolCreatorPermission(val id: String, val label: String, val description: String) {
    READ_FILES("files.read", "Read workspace files", "Read files and directories in the user workspace"),
    WRITE_FILES("files.write", "Write workspace files", "Create and modify files in the user workspace"),
    RUN_COMMANDS("shell.execute", "Run shell commands", "Spawn external processes on the user machine"),
    NETWORK("network.access", "Network access", "Make outbound HTTP or socket connections"),
    BROWSER("browser.control", "Control browser tabs", "Open and drive browser tabs in BOSS"),
    SECRETS("secrets.read", "Read secrets", "Access the BOSS secret manager"),
    MCP_TOOLS("mcp.tools", "Expose MCP tools", "Offer tools to AI agents via the boss MCP server"),
}

// endregion

// region Intent

sealed class ToolCreatorIntent {
    data class SetToolName(val name: String) : ToolCreatorIntent()
    data class SetDescription(val description: String) : ToolCreatorIntent()
    data class SetParentDir(val path: String) : ToolCreatorIntent()
    data class SetAgent(val agent: ToolCreatorAgent) : ToolCreatorIntent()
    data class TogglePermission(val permission: ToolCreatorPermission) : ToolCreatorIntent()

    /** Re-probe git, gh and the four CLI agents. */
    object CheckEnvironment : ToolCreatorIntent()

    /** Scaffold the repo described by the current form. Rejected if the form is invalid. */
    object StartBuild : ToolCreatorIntent()

    object ClearJobs : ToolCreatorIntent()
}

// endregion

/**
 * StateHolder for the Tool Creator plugin — the **scaffold** surface.
 *
 * This plugin has an unusually clean split: a pure engine that writes files and
 * runs `git`, and a Compose panel that ends by opening an AI CLI in a terminal
 * tab. The engine half transplants perfectly into a child JVM. The ending does
 * not, and neither do two smaller conveniences:
 *
 * - **The terminal handoff is unavailable.** The plugin finishes with
 *   `PluginContext.splitViewOperations.openTab(TerminalTabInfo(...))`.
 *   `SplitViewOperationsProxy` does not override `openTab`, so the call reaches
 *   `SplitViewOperations`' empty default body and does nothing at all — no tab,
 *   no error. Rather than pretend, this holder always takes the degraded path the
 *   plugin already defines for a null `splitViewOperations`: it puts
 *   `cd <dir> && <agent launch command>` in the job log and in
 *   [ToolCreatorJob.handoffCommand], and [ToolCreatorState.terminalHandoffAvailable]
 *   is false so a renderer shows it as a command to copy rather than a button.
 * - **GitHub repo creation is unavailable.** In-process the plugin mints a Plugin
 *   Store publish key via `pluginStoreApiKeyProvider` and installs it as the new
 *   repo's `BOSS_STORE_PLUGIN_PUBLISH_KEY` secret. That provider is
 *   `Nothing? = null` here, so a repo created from this holder would be a real
 *   remote repo whose release CI cannot publish — worse than not creating it. On
 *   top of that, `gh repo create` from a bare wire intent has none of the
 *   confirmation the in-process dialog gives. So this holder is local-only:
 *   scaffold, `git init`, `git add`, one commit, and stop.
 *   [ToolCreatorState.githubAvailable] is always false.
 * - **The Toolbox "New Tool" deep link is unavailable.** It arrives as a
 *   `CustomPluginEvent` on `PluginContext.applicationEventBus`, also
 *   `Nothing? = null`. A host renderer opens the form itself instead.
 * - **The native directory picker is not used.** The plugin deliberately bypasses
 *   `directoryPickerProvider` for an AWT dialog parented to its Compose
 *   `DialogWindow`, because an ownerless dialog can appear behind that window on
 *   macOS. There is no such window here; the path arrives as
 *   [ToolCreatorIntent.SetParentDir] and the host may use its own picker.
 *
 * **Templates are read from the plugin's own jar, not copied here.** The child
 * JVM is launched as `java -cp runtime-all.jar:plugin.jar`, so the tool-creator
 * jar's bundled `scaffold` templates are on this classloader. Rendering them here
 * keeps the plugin the single source of truth for what a scaffolded repo
 * contains — a duplicated copy in the runtime would silently rot into generating
 * repos that no longer build. When those resources are absent (a holder loaded
 * without the plugin jar beside it), [ToolCreatorState.scaffoldAvailable] is
 * false and [ToolCreatorIntent.StartBuild] refuses rather than writing a
 * half-scaffold.
 */
class ToolCreatorStateHolder : PluginStateHolder<ToolCreatorState, ToolCreatorIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(ToolCreatorStateHolder::class.java)

    private var jobCounter = 1L

    constructor(scope: CoroutineScope) : this(scope, defaultParentDir())

    constructor(scope: CoroutineScope, context: RemotePluginContext) :
        this(scope, context.projectPath?.let { File(it).parent } ?: defaultParentDir()) {
        logger.info(
            "ToolCreatorStateHolder started (terminal handoff and GitHub unavailable out-of-process)",
        )
    }

    private constructor(scope: CoroutineScope, parentDir: String) : super(ToolCreatorState(), scope) {
        // Publish an initial versioned state before probing the machine. A holder
        // that never calls updateState stays at version 0 and never renders at
        // all (the bookmarks failure mode).
        updateState {
            copy(
                form = form.copy(parentDir = parentDir).derive(),
                scaffoldAvailable = templatesPresent(),
                ready = true,
            )
        }
        onIntent(ToolCreatorIntent.CheckEnvironment)
    }

    override fun onIntent(intent: ToolCreatorIntent) {
        when (intent) {
            is ToolCreatorIntent.SetToolName -> updateState { copy(form = form.copy(toolName = intent.name).derive()) }

            is ToolCreatorIntent.SetDescription -> updateState {
                copy(form = form.copy(description = intent.description).derive())
            }

            is ToolCreatorIntent.SetParentDir -> updateState {
                copy(form = form.copy(parentDir = intent.path).derive())
            }

            is ToolCreatorIntent.SetAgent -> updateState { copy(form = form.copy(agent = intent.agent)) }

            is ToolCreatorIntent.TogglePermission -> updateState {
                val next = if (intent.permission in form.permissions) {
                    form.permissions - intent.permission
                } else {
                    form.permissions + intent.permission
                }
                copy(form = form.copy(permissions = next))
            }

            is ToolCreatorIntent.CheckEnvironment -> checkEnvironment()

            is ToolCreatorIntent.StartBuild -> startBuild()

            is ToolCreatorIntent.ClearJobs -> updateState {
                copy(jobs = jobs.filter { it.status == ToolCreatorJobStatus.RUNNING })
            }
        }
    }

    // region environment

    private fun checkEnvironment() {
        scope.launch {
            val missing = ToolCreatorAgent.entries.filterNot { agentInstalled(it) }
            val git = ProcessRunner.resolve("git", BIN_DIRS) != null
            val gh = ProcessRunner.resolve("gh", BIN_DIRS) != null
            updateState {
                copy(
                    environment = ToolCreatorEnvironment(
                        missingAgents = missing,
                        gitAvailable = git,
                        ghInstalled = gh,
                        checked = true,
                    ),
                )
            }
        }
    }

    private fun agentInstalled(agent: ToolCreatorAgent): Boolean =
        ProcessRunner.resolve(agent.binary, BIN_DIRS) != null

    // endregion

    // region build

    private fun startBuild() {
        val state = currentState()
        val form = state.form
        if (!state.scaffoldAvailable) {
            updateState {
                copy(form = this.form.copy(error = "Scaffold templates are not on this runtime's classpath"))
            }
            return
        }
        if (form.error != null) return
        if (state.jobs.any { it.status == ToolCreatorJobStatus.RUNNING && it.toolName == form.toolName.trim() }) {
            updateState { copy(form = this.form.copy(error = "A build for that name is already running")) }
            return
        }

        val jobId = jobCounter++
        val job = ToolCreatorJob(
            id = jobId,
            toolName = form.toolName.trim(),
            agent = form.agent,
            path = form.targetDir,
            status = ToolCreatorJobStatus.RUNNING,
        )
        updateState { copy(jobs = jobs + job) }

        scope.launch {
            val outcome = runCatching { scaffold(form) { line -> appendLog(jobId, line) } }
            if (outcome.isSuccess) {
                val command = "cd ${form.targetDir} && ${form.agent.launchCommand()}"
                appendLog(
                    jobId,
                    "Terminal unavailable out-of-process — run manually: $command",
                )
                updateState {
                    copy(
                        jobs = jobs.map {
                            if (it.id == jobId) {
                                it.copy(status = ToolCreatorJobStatus.SUCCESS, handoffCommand = command)
                            } else {
                                it
                            }
                        },
                    )
                }
            } else {
                val message = outcome.exceptionOrNull()?.message ?: "Scaffold failed"
                appendLog(jobId, "Failed: $message")
                updateState {
                    copy(
                        jobs = jobs.map {
                            if (it.id == jobId) it.copy(status = ToolCreatorJobStatus.FAILED) else it
                        },
                    )
                }
            }
        }
    }

    private fun appendLog(jobId: Long, line: String) {
        updateState {
            copy(
                jobs = jobs.map {
                    if (it.id == jobId) it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) else it
                },
            )
        }
    }

    /**
     * Write a complete, buildable plugin repo from the templates bundled in the
     * tool-creator jar, then `git init` it. Order and file set mirror the
     * plugin's `ScaffoldGenerator.scaffold`, minus its GitHub half.
     */
    private suspend fun scaffold(form: ToolCreatorForm, log: (String) -> Unit): File {
        val dir = File(form.targetDir)
        if (dir.exists()) throw ScaffoldFailure("Directory already exists: ${dir.absolutePath}")
        if (!dir.mkdirs()) throw ScaffoldFailure("Could not create ${dir.absolutePath}")

        val tokens = tokensFor(form)
        log("Scaffolding ${form.repoName} in ${dir.absolutePath}")

        val packagePath = "src/main/kotlin/ai/rever/boss/plugin/dynamic/${form.packageName}"
        val files = mapOf(
            "settings.gradle.kts" to "scaffold/settings.gradle.kts.tmpl",
            "build.gradle.kts" to "scaffold/build.gradle.kts.tmpl",
            ".gitignore" to "scaffold/gitignore.tmpl",
            ".github/workflows/build.yml" to "scaffold/workflow-build.yml.tmpl",
            ".github/workflows/claude-code-review.yml" to "scaffold/workflow-claude-review.yml.tmpl",
            "README.md" to "scaffold/README.md.tmpl",
            "CLAUDE.md" to "scaffold/CLAUDE.md.tmpl",
            "AGENTS.md" to "scaffold/AGENTS.md.tmpl",
            "GEMINI.md" to "scaffold/GEMINI.md.tmpl",
            "src/main/resources/META-INF/boss-plugin/plugin.json" to "scaffold/plugin.json.tmpl",
            "$packagePath/${form.classPrefix}DynamicPlugin.kt" to "scaffold/src/DynamicPlugin.kt.tmpl",
            "$packagePath/${form.classPrefix}Info.kt" to "scaffold/src/Info.kt.tmpl",
            "$packagePath/${form.classPrefix}Component.kt" to "scaffold/src/Component.kt.tmpl",
            "$packagePath/${form.classPrefix}ViewModel.kt" to "scaffold/src/ViewModel.kt.tmpl",
            "$packagePath/${form.classPrefix}Content.kt" to "scaffold/src/Content.kt.tmpl",
        )
        files.forEach { (relativePath, resource) -> write(dir, relativePath, render(resource, tokens)) }

        writeSkills(dir, form, tokens)
        writeGradleWrapper(dir)
        copyApiJar(dir, form, log)
        initGit(dir, form, log)

        log("Scaffold complete")
        return dir
    }

    /**
     * The tool-creator skill, in every CLI's native format, so the repo works
     * with whichever agent the user opens it with later — not just the one the
     * form named.
     */
    private fun writeSkills(dir: File, form: ToolCreatorForm, tokens: Map<String, String>) {
        val body = render("scaffold/skill/skill-body.md.tmpl", tokens)
        val description = "Build the ${form.toolName.trim()} BOSS plugin in this repo following house conventions"

        val skillMd = "---\nname: tool-creator\ndescription: $description\n---\n\n$body"
        write(dir, ".claude/skills/tool-creator/SKILL.md", skillMd)
        write(dir, ".codex/skills/tool-creator/SKILL.md", skillMd)

        val quote = "\"\"\""
        val toml = "description = \"${description.replace("\"", "\\\"")}\"\n\nprompt = $quote\n$body\n$quote\n"
        write(dir, ".gemini/commands/tool-creator.toml", toml)

        write(dir, ".opencode/command/tool-creator.md", "---\ndescription: $description\n---\n\n$body")
    }

    private fun writeGradleWrapper(dir: File) {
        File(dir, "gradle/wrapper").mkdirs()
        File(dir, "gradle/wrapper/gradle-wrapper.jar")
            .writeBytes(resourceBytes("scaffold/wrapper/gradle-wrapper.jar"))
        write(dir, "gradle/wrapper/gradle-wrapper.properties", resourceText("scaffold/wrapper/gradle-wrapper.properties"))
        write(dir, "gradlew", resourceText("scaffold/wrapper/gradlew"))
        write(dir, "gradlew.bat", resourceText("scaffold/wrapper/gradlew.bat"))
        File(dir, "gradlew").setExecutable(true, false)
    }

    /**
     * Copy the newest boss-plugin-api jar we can find into `libs/` so the
     * scaffold compiles standalone outside the Boss workspace. Read-only against
     * the operator's plugin directories, exactly as the plugin does.
     */
    private fun copyApiJar(dir: File, form: ToolCreatorForm, log: (String) -> Unit) {
        val home = System.getProperty("user.home").orEmpty()
        val candidates = listOf(
            File(form.parentDir, "boss-plugin-api/build/libs"),
            File(home, ".boss_debug/plugins"),
            File(home, ".boss/plugins"),
        ).flatMap { location ->
            location.listFiles()?.filter {
                it.name.startsWith("boss-plugin-api-") && it.name.endsWith(".jar")
            }.orEmpty()
        }
        val newest = candidates.maxByOrNull { versionKey(it.name) }
        if (newest == null) {
            log("Warning: no boss-plugin-api jar found — local builds need ../boss-plugin-api or libs/")
            return
        }
        val libs = File(dir, "libs").apply { mkdirs() }
        newest.copyTo(File(libs, newest.name))
        log("Bundled ${newest.name} into libs/")
    }

    private suspend fun initGit(dir: File, form: ToolCreatorForm, log: (String) -> Unit) {
        val git = ProcessRunner.resolve("git", BIN_DIRS)
        if (git == null) {
            log("Warning: git not found — skipping repo init")
            return
        }
        suspend fun git(vararg args: String) = ProcessRunner.run(
            exe = git,
            args = args.toList(),
            extraPathDirs = BIN_DIRS,
            workingDir = dir,
            timeoutMs = GIT_TIMEOUT_MS,
        )
        git("init", "-b", "main")
        git("add", "-A")
        val commit = git("commit", "-m", "🎉 Scaffold ${form.toolName.trim()} via BOSS Tool Creator")
        if (commit.exitCode == 0) {
            log("Initialized git repo with initial commit")
        } else {
            log("Warning: git commit failed: ${(commit.stderr.ifBlank { commit.stdout }).take(MAX_ERROR_CHARS)}")
        }
    }

    // endregion

    private fun write(dir: File, relativePath: String, content: String) {
        val target = File(dir, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
    }

    private fun render(resource: String, tokens: Map<String, String>): String =
        tokens.entries.fold(resourceText(resource)) { acc, (key, value) -> acc.replace("@@$key@@", value) }

    private fun resourceText(resource: String): String =
        javaClass.classLoader.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw ScaffoldFailure("Bundled template missing: $resource")

    private fun resourceBytes(resource: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw ScaffoldFailure("Bundled template missing: $resource")

    private fun templatesPresent(): Boolean =
        javaClass.classLoader.getResource(PROBE_TEMPLATE) != null

    private fun tokensFor(form: ToolCreatorForm): Map<String, String> {
        // Form permissions deliberately do NOT go into the scaffolded manifest's
        // requiredPermissions: the store treats that field as an RBAC install
        // gate and these ids are not in the RBAC catalog. They inform the AI
        // agent through the skill body and README instead.
        val bullets = if (form.permissions.isEmpty()) {
            "- (none requested)"
        } else {
            form.permissions.joinToString("\n") { "- `${it.id}` — ${it.description}" }
        }
        val name = form.toolName.trim()
        val description = form.description.trim()
        return mapOf(
            "TOOL_NAME" to name,
            "TOOL_NAME_JSON" to jsonEscape(name),
            "TOOL_NAME_KT" to ktEscape(name),
            "TOOL_DESCRIPTION" to description,
            "TOOL_DESCRIPTION_JSON" to jsonEscape(description),
            "TOOL_DESCRIPTION_KT" to ktEscape(description),
            "SLUG" to form.slug,
            "PACKAGE" to form.packageName,
            "CLASS_PREFIX" to form.classPrefix,
            "PLUGIN_ID" to form.pluginId,
            "REPO_NAME" to form.repoName,
            "PERMISSIONS_BULLETS" to bullets,
            "AGENT_DISPLAY" to form.agent.displayName,
        )
    }

    /** Raised for a fatal scaffold problem; recoverable ones go to the job log. */
    internal class ScaffoldFailure(message: String) : Exception(message)

    internal companion object {
        private const val MAX_LOG_LINES = 500
        private const val MAX_ERROR_CHARS = 200
        private const val GIT_TIMEOUT_MS = 60_000L

        /** Any one bundled template proves the plugin jar is on this classpath. */
        private const val PROBE_TEMPLATE = "scaffold/build.gradle.kts.tmpl"

        /** Install dirs to search beyond PATH, matching the plugin's `CliAgent.isInstalled`. */
        internal val BIN_DIRS: List<String> by lazy {
            val home = System.getProperty("user.home").orEmpty()
            listOf("$home/.local/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
                .filter { it.isNotBlank() }
        }

        internal fun defaultParentDir(): String =
            File(System.getProperty("user.home").orEmpty(), "Development/Boss/boss_plugins")
                .takeIf { it.isDirectory }?.absolutePath
                ?: System.getProperty("user.home").orEmpty()

        /**
         * Derive every name from [ToolCreatorForm.toolName] and validate, applying
         * the plugin's `ScaffoldSpec` rules verbatim so a form accepted here is
         * accepted in-process too.
         */
        internal fun ToolCreatorForm.derive(): ToolCreatorForm {
            val slug = toolName.trim().replace(NON_ALNUM, "-").trim('-').lowercase()
            val packageName = slug.replace("-", "")
            val classPrefix = slug.split('-')
                .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
            val target = if (parentDir.isBlank() || slug.isEmpty()) "" else File(parentDir, slug).absolutePath
            return copy(
                slug = slug,
                packageName = packageName,
                classPrefix = classPrefix,
                pluginId = if (packageName.isEmpty()) "" else "ai.rever.boss.plugin.dynamic.$packageName",
                repoName = if (slug.isEmpty()) "" else "boss-plugin-$slug",
                targetDir = target,
                error = validate(toolName, description, parentDir, slug),
            )
        }

        private val NON_ALNUM = Regex("[^A-Za-z0-9]+")

        /** The plugin's `ScaffoldSpec.validate`, with the slug already computed. */
        private fun validate(
            toolName: String,
            description: String,
            parentDir: String,
            slug: String,
        ): String? = when {
            toolName.isBlank() -> "Tool name is required"
            slug.isEmpty() || !slug.first().isLetter() -> "Tool name must start with a letter"
            description.isBlank() -> "Tool description is required"
            parentDir.isBlank() -> "Location is required"
            !File(parentDir).isDirectory -> "Location does not exist: $parentDir"
            File(parentDir, slug).exists() -> "Directory already exists: ${File(parentDir, slug).absolutePath}"
            else -> null
        }

        internal fun jsonEscape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

        /** Escape for embedding inside a Kotlin string literal in generated sources. */
        internal fun ktEscape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

        /** Zero-padded so `1.0.66` sorts above `1.0.9`, matching the plugin's helper. */
        internal fun versionKey(jarName: String): String = jarName
            .removePrefix("boss-plugin-api-").removeSuffix(".jar")
            .split(".")
            .joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(4, '0') }
    }
}

/**
 * Decode one wire intent for [ToolCreatorStateHolder].
 *
 * Every intent is single-field or nullary, so all take the payload as a bare
 * string. An unknown [intentType], or an enum name this build does not know,
 * decodes to null so `PluginStateSyncService` drops it rather than acting on a
 * half-read message.
 */
internal fun decodeToolCreatorIntent(intentType: String, payload: String): ToolCreatorIntent? = when (intentType) {
    "SetToolName" -> ToolCreatorIntent.SetToolName(payload)
    "SetDescription" -> ToolCreatorIntent.SetDescription(payload)
    "SetParentDir" -> ToolCreatorIntent.SetParentDir(payload)

    "SetAgent" -> runCatching { ToolCreatorAgent.valueOf(payload.trim().uppercase()) }
        .getOrNull()?.let { ToolCreatorIntent.SetAgent(it) }

    "TogglePermission" -> runCatching { ToolCreatorPermission.valueOf(payload.trim().uppercase()) }
        .getOrNull()?.let { ToolCreatorIntent.TogglePermission(it) }

    "CheckEnvironment" -> ToolCreatorIntent.CheckEnvironment
    "StartBuild" -> ToolCreatorIntent.StartBuild
    "ClearJobs" -> ToolCreatorIntent.ClearJobs
    else -> null
}
