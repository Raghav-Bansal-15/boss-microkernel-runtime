package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

// region State

/**
 * The notebook document surface, mirrored for a host-side renderer.
 *
 * This is the *document*, not the whole plugin: see [JupyterStateHolder] for
 * what a child JVM can and cannot do. [executionAvailable] and [aiAvailable]
 * are always `false` here and exist so the host renders honest affordances
 * (no Run button, no Assistant) rather than buttons that do nothing.
 */
@Serializable
data class JupyterState(
    val notebookPath: String? = null,
    val notebookName: String = "Untitled",
    val cells: List<JupyterCell> = emptyList(),
    val selectedCellId: String? = null,
    val dirty: Boolean = false,
    val nbformat: Int = 4,
    val nbformatMinor: Int = 5,
    val loadError: String? = null,
    val saveError: String? = null,
    /** Always false out-of-process — no kernel can be driven from this holder. */
    val executionAvailable: Boolean = false,
    /** Always false out-of-process — `RemotePluginContext` carries no LLM provider. */
    val aiAvailable: Boolean = false,
)

/** One notebook cell. [cellType] is the nbformat string: `code`, `markdown` or `raw`. */
@Serializable
data class JupyterCell(
    val id: String,
    val cellType: String,
    val source: String,
    val executionCount: Int? = null,
    val outputs: List<JupyterOutput> = emptyList(),
)

/**
 * One stored cell output.
 *
 * [kind] is `stream`, `data` or `error`. Binary representations (`image/png`,
 * …) are **listed** in [mimeTypes] but their bytes are deliberately not
 * mirrored: the state envelope is re-sent in full on every change, so
 * inlining plot payloads would put megabytes on the wire per keystroke. A
 * host that wants to render them needs a separate blob channel.
 */
@Serializable
data class JupyterOutput(
    val kind: String,
    /** Stream name (`stdout` / `stderr`) for [kind] == `stream`. */
    val name: String = "",
    /** `stream` text, `text/plain` rendering, or the joined error traceback. */
    val text: String = "",
    /** MIME types present on a `data` output, in nbformat order. */
    val mimeTypes: List<String> = emptyList(),
    val ename: String = "",
    val evalue: String = "",
)

// endregion

// region Intent

sealed class JupyterIntent {
    /** Read `path` from disk and replace the document. */
    data class OpenNotebook(val path: String) : JupyterIntent()

    /** Re-read the currently open path, discarding unsaved edits. */
    object Reload : JupyterIntent()

    data class SelectCell(val cellId: String) : JupyterIntent()
    data class SetCellSource(val cellId: String, val source: String) : JupyterIntent()

    /** Insert an empty cell of [cellType] after [afterCellId] (null = at the end). */
    data class AddCell(val afterCellId: String?, val cellType: String) : JupyterIntent()

    data class DeleteCell(val cellId: String) : JupyterIntent()
    data class MoveCell(val cellId: String, val up: Boolean) : JupyterIntent()
    data class ChangeCellType(val cellId: String, val cellType: String) : JupyterIntent()
    object ClearAllOutputs : JupyterIntent()

    /** Write the document back to [JupyterState.notebookPath]. */
    object Save : JupyterIntent()

    object ClearError : JupyterIntent()
}

// endregion

/**
 * StateHolder for the Jupyter Notebook plugin — the **document** surface.
 *
 * The in-process plugin is a notebook editor *and* a kernel client: it spawns
 * `python -m ipykernel_launcher`, speaks Jupyter's 5-socket ZeroMQ protocol
 * through the `jeromq` it bundles, and offers Cursor-style AI (Cmd+K, "Fix
 * with AI", the Assistant panel). None of that is reachable from here, and
 * this holder does not pretend otherwise:
 *
 * - **Execution is unavailable.** The ZMQ client and kernel manager live in
 *   the plugin's own jar and are Compose-coupled through its view model; the
 *   runtime has no dependency on them. [JupyterState.executionAvailable] is
 *   always false. Stored outputs from the last save *are* mirrored, so an
 *   already-executed notebook still renders its results.
 * - **AI is unavailable, structurally.** A child JVM receives
 *   [RemotePluginContext], which by its own documentation does not implement
 *   `PluginContext` and exposes no LLM provider at any api version, so
 *   `PluginContext.llmProvider` — the plugin's provider source — cannot be
 *   reached. This is not a wiring gap that a newer api version fixes.
 *   [JupyterState.aiAvailable] is always false.
 *
 * What it does mirror is everything the notebook surface needs that does not
 * depend on either: the cell list (type, source, execution count and stored
 * outputs), the selection, the document name/path and the dirty flag — plus
 * authoring intents (edit source, add/delete/move cells, change cell type,
 * clear outputs) and a [JupyterIntent.Save] that writes canonical nbformat
 * back to disk.
 *
 * File I/O is done directly with [File]: the runtime's
 * `RemotePluginContext.fileSystemDataProvider` is null (the context is built
 * without an event-bus channel), and a child JVM has filesystem access
 * anyway.
 *
 * Unknown per-cell and notebook-level JSON is preserved verbatim across a
 * load/save round trip, so saving does not destroy fields this model does not
 * describe (widget state, cell metadata, attachments, …).
 */
class JupyterStateHolder : PluginStateHolder<JupyterState, JupyterIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(JupyterStateHolder::class.java)

    /** Original JSON of each loaded cell, keyed by cell id — preserved on save. */
    private val rawCells = mutableMapOf<String, JsonObject>()

    /** Original notebook-level JSON (metadata and any unmodelled top-level keys). */
    private var rawRoot: JsonObject = JsonObject(emptyMap())

    constructor(scope: CoroutineScope) : super(emptyNotebook(), scope) {
        // Publish the empty-notebook state so a host renderer has a version > 0
        // to apply even before any notebook is opened. Without this the holder
        // would sit at version 0 and never render (the bookmarks failure mode).
        onIntent(JupyterIntent.SelectCell(currentState().cells.first().id))
    }

    constructor(scope: CoroutineScope, context: RemotePluginContext) : this(scope) {
        logger.info(
            "JupyterStateHolder started (projectPath={}, execution/AI unavailable out-of-process)",
            context.projectPath,
        )
    }

    override fun onIntent(intent: JupyterIntent) {
        when (intent) {
            is JupyterIntent.OpenNotebook -> load(intent.path)

            is JupyterIntent.Reload -> {
                val path = currentState().notebookPath
                if (path == null) {
                    updateState { copy(loadError = "No notebook is open") }
                } else {
                    load(path)
                }
            }

            is JupyterIntent.SelectCell -> updateState {
                if (cells.none { it.id == intent.cellId }) this else copy(selectedCellId = intent.cellId)
            }

            is JupyterIntent.SetCellSource -> updateState {
                if (cells.none { it.id == intent.cellId }) {
                    this
                } else {
                    copy(
                        cells = cells.map {
                            if (it.id == intent.cellId) it.copy(source = intent.source) else it
                        },
                        dirty = true,
                    )
                }
            }

            is JupyterIntent.AddCell -> {
                val fresh = JupyterCell(
                    id = UUID.randomUUID().toString(),
                    cellType = normalizeType(intent.cellType),
                    source = "",
                )
                updateState {
                    val at = cells.indexOfFirst { it.id == intent.afterCellId }
                    val insertAt = if (at < 0) cells.size else at + 1
                    copy(
                        cells = cells.toMutableList().apply { add(insertAt, fresh) },
                        selectedCellId = fresh.id,
                        dirty = true,
                    )
                }
            }

            is JupyterIntent.DeleteCell -> updateState {
                // A notebook always has at least one cell, like the plugin's own model.
                if (cells.size <= 1 || cells.none { it.id == intent.cellId }) {
                    this
                } else {
                    val remaining = cells.filter { it.id != intent.cellId }
                    copy(
                        cells = remaining,
                        selectedCellId = if (selectedCellId == intent.cellId) {
                            remaining.first().id
                        } else {
                            selectedCellId
                        },
                        dirty = true,
                    )
                }
            }

            is JupyterIntent.MoveCell -> updateState {
                val from = cells.indexOfFirst { it.id == intent.cellId }
                val to = if (intent.up) from - 1 else from + 1
                if (from < 0 || to !in cells.indices) {
                    this
                } else {
                    copy(
                        cells = cells.toMutableList().apply { add(to, removeAt(from)) },
                        dirty = true,
                    )
                }
            }

            is JupyterIntent.ChangeCellType -> {
                val target = normalizeType(intent.cellType)
                updateState {
                    if (cells.none { it.id == intent.cellId }) {
                        this
                    } else {
                        copy(
                            cells = cells.map { cell ->
                                if (cell.id != intent.cellId) {
                                    cell
                                } else {
                                    // Only code cells carry outputs / execution counts.
                                    cell.copy(
                                        cellType = target,
                                        outputs = if (target == CODE) cell.outputs else emptyList(),
                                        executionCount = if (target == CODE) cell.executionCount else null,
                                    )
                                }
                            },
                            dirty = true,
                        )
                    }
                }
            }

            is JupyterIntent.ClearAllOutputs -> updateState {
                copy(
                    cells = cells.map { it.copy(outputs = emptyList(), executionCount = null) },
                    dirty = true,
                )
            }

            is JupyterIntent.Save -> save()

            is JupyterIntent.ClearError -> updateState { copy(loadError = null, saveError = null) }
        }
    }

    // region load / save

    private fun load(path: String) {
        val file = File(path)
        val text = runCatching { file.readText() }.getOrElse { error ->
            logger.warn("Failed to read notebook {}: {}", path, error.message)
            updateState { copy(loadError = "Could not read $path: ${error.message}") }
            return
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { error ->
            logger.warn("Failed to parse notebook {}: {}", path, error.message)
            updateState { copy(loadError = "Not a readable .ipynb: ${error.message}") }
            return
        }

        rawRoot = root
        rawCells.clear()
        val parsed = (root["cells"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map { obj ->
                val cell = parseCell(obj)
                rawCells[cell.id] = obj
                cell
            }
            .orEmpty()
            .ifEmpty { listOf(emptyCodeCell()) }

        updateState {
            JupyterState(
                notebookPath = path,
                notebookName = file.name.removeSuffix(".ipynb").ifBlank { file.name },
                cells = parsed,
                selectedCellId = parsed.first().id,
                dirty = false,
                nbformat = root["nbformat"]?.jsonPrimitive?.intOrNull ?: 4,
                nbformatMinor = root["nbformat_minor"]?.jsonPrimitive?.intOrNull ?: 5,
            )
        }
        logger.info("Loaded notebook {} ({} cells)", path, parsed.size)
    }

    private fun save() {
        val state = currentState()
        val path = state.notebookPath
        if (path == null) {
            updateState { copy(saveError = "No path to save to — open a notebook first") }
            return
        }
        val serialized = runCatching { json.encodeToString(JsonObject.serializer(), toJson(state)) }
            .getOrElse { error ->
                logger.warn("Failed to serialize notebook {}: {}", path, error.message)
                updateState { copy(saveError = "Could not serialize: ${error.message}") }
                return
            }
        val written = runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(serialized)
        }
        if (written.isFailure) {
            val message = written.exceptionOrNull()?.message
            logger.warn("Failed to write notebook {}: {}", path, message)
            updateState { copy(saveError = "Could not write $path: $message") }
            return
        }
        updateState { copy(dirty = false, saveError = null) }
        logger.info("Saved notebook {} ({} cells)", path, state.cells.size)
    }

    // endregion

    // region nbformat mapping

    private fun parseCell(obj: JsonObject): JupyterCell {
        val cellType = normalizeType(obj["cell_type"]?.jsonPrimitive?.contentOrNull ?: CODE)
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val outputs = if (cellType == CODE) {
            (obj["outputs"] as? JsonArray)?.mapNotNull { parseOutput(it as? JsonObject) }.orEmpty()
        } else {
            emptyList()
        }
        return JupyterCell(
            id = id,
            cellType = cellType,
            source = elementToText(obj["source"]),
            executionCount = obj["execution_count"]?.jsonPrimitive?.intOrNull,
            outputs = outputs,
        )
    }

    private fun parseOutput(obj: JsonObject?): JupyterOutput? {
        if (obj == null) return null
        return when (obj["output_type"]?.jsonPrimitive?.contentOrNull) {
            "stream" -> JupyterOutput(
                kind = "stream",
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "stdout",
                text = elementToText(obj["text"]),
            )

            "execute_result", "display_data" -> {
                val data = obj["data"] as? JsonObject
                JupyterOutput(
                    kind = "data",
                    text = elementToText(data?.get("text/plain")),
                    mimeTypes = data?.keys?.toList().orEmpty(),
                )
            }

            "error" -> JupyterOutput(
                kind = "error",
                text = (obj["traceback"] as? JsonArray)
                    ?.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                    .orEmpty(),
                ename = obj["ename"]?.jsonPrimitive?.contentOrNull ?: "",
                evalue = obj["evalue"]?.jsonPrimitive?.contentOrNull ?: "",
            )

            else -> null
        }
    }

    /**
     * Rebuild the notebook JSON. Every key of the original document and of each
     * originally-loaded cell survives; only the fields this model owns are
     * replaced, and cells added here are emitted minimally.
     */
    private fun toJson(state: JupyterState): JsonObject = buildJsonObject {
        rawRoot.forEach { (key, value) -> if (key != "cells") put(key, value) }
        put("nbformat", state.nbformat)
        put("nbformat_minor", state.nbformatMinor)
        if ("metadata" !in rawRoot) put("metadata", JsonObject(emptyMap()))
        put("cells", buildJsonArray { state.cells.forEach { add(cellToJson(it)) } })
    }

    private fun cellToJson(cell: JupyterCell): JsonObject = buildJsonObject {
        val raw = rawCells[cell.id]
        raw?.forEach { (key, value) ->
            if (key !in ownedCellKeys) put(key, value)
        }
        put("cell_type", cell.cellType)
        put("id", cell.id)
        if (raw == null || "metadata" !in raw) put("metadata", JsonObject(emptyMap()))
        put("source", textToLineArray(cell.source))
        if (cell.cellType == CODE) {
            val count = cell.executionCount
            if (count != null) put("execution_count", count) else put("execution_count", JsonNull)
            // Outputs are preserved verbatim from the loaded document when the cell
            // still has them, because this holder cannot re-execute a cell and must
            // not silently downgrade a rich stored output to its text mirror.
            put("outputs", rawOutputsFor(cell, raw))
        }
    }

    private fun rawOutputsFor(cell: JupyterCell, raw: JsonObject?): JsonArray {
        if (cell.outputs.isEmpty()) return JsonArray(emptyList())
        val original = raw?.get("outputs") as? JsonArray
        return if (original != null && original.size == cell.outputs.size) {
            original
        } else {
            buildJsonArray { cell.outputs.forEach { add(outputToJson(it)) } }
        }
    }

    private fun outputToJson(output: JupyterOutput): JsonObject = buildJsonObject {
        when (output.kind) {
            "stream" -> {
                put("output_type", "stream")
                put("name", output.name.ifBlank { "stdout" })
                put("text", textToLineArray(output.text))
            }

            "error" -> {
                put("output_type", "error")
                put("ename", output.ename)
                put("evalue", output.evalue)
                put(
                    "traceback",
                    buildJsonArray { output.text.split("\n").forEach { add(JsonPrimitive(it)) } },
                )
            }

            else -> {
                put("output_type", "display_data")
                put("data", buildJsonObject { put("text/plain", textToLineArray(output.text)) })
                put("metadata", JsonObject(emptyMap()))
            }
        }
    }

    // endregion

    private companion object {
        const val CODE = "code"
        const val MARKDOWN = "markdown"
        const val RAW = "raw"

        /** Cell keys this holder rewrites — everything else is copied through. */
        val ownedCellKeys = setOf("cell_type", "id", "source", "execution_count", "outputs")

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            prettyPrintIndent = " "
        }

        fun normalizeType(raw: String): String = when (raw.lowercase()) {
            MARKDOWN -> MARKDOWN
            RAW -> RAW
            else -> CODE
        }

        fun emptyCodeCell() = JupyterCell(UUID.randomUUID().toString(), CODE, "")

        /**
         * A fresh untitled notebook with one empty code cell — the same starting
         * document the in-process plugin shows for New Tab → Jupyter.
         *
         * Selection is left unset so the constructor's first
         * [JupyterIntent.SelectCell] is a real state change.
         */
        fun emptyNotebook(): JupyterState = JupyterState(cells = listOf(emptyCodeCell()))

        /** Split text into nbformat line-array form (each line keeps its newline). */
        fun textToLineArray(text: String): JsonArray {
            if (text.isEmpty()) return JsonArray(emptyList())
            val lines = Regex("(?<=\n)").split(text).filter { it.isNotEmpty() }
            return buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } }
        }

        /** Read a value that may be a single string or a list of strings. */
        fun elementToText(element: JsonElement?): String = when (element) {
            null -> ""
            is JsonArray -> element.joinToString("") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            is JsonPrimitive -> element.contentOrNull ?: ""
            else -> element.toString()
        }
    }
}

/**
 * Decode one wire intent for [JupyterStateHolder].
 *
 * Single-field intents take the payload as a bare string (the convention the
 * other holders' arms use); multi-field intents take a JSON object. An unknown
 * [intentType], or a payload missing the fields the intent needs, decodes to
 * null so `PluginStateSyncService` drops it rather than editing the document
 * from a half-read message.
 */
internal fun decodeJupyterIntent(intentType: String, payload: String): JupyterIntent? {
    val obj = runCatching { jupyterIntentJson.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    fun str(key: String): String? = obj?.get(key)?.jsonPrimitive?.contentOrNull

    return when (intentType) {
        "SetCellSource" -> {
            val cellId = str("cellId") ?: return null
            JupyterIntent.SetCellSource(cellId, str("source") ?: "")
        }

        "AddCell" -> JupyterIntent.AddCell(str("afterCellId"), str("cellType") ?: "code")

        "MoveCell" -> {
            val cellId = str("cellId") ?: return null
            val up = obj?.get("up")?.jsonPrimitive?.booleanOrNull ?: return null
            JupyterIntent.MoveCell(cellId, up)
        }

        "ChangeCellType" -> {
            val cellId = str("cellId") ?: return null
            val cellType = str("cellType") ?: return null
            JupyterIntent.ChangeCellType(cellId, cellType)
        }

        "OpenNotebook" -> payload.ifBlank { null }?.let { JupyterIntent.OpenNotebook(it) }
        "SelectCell" -> payload.ifBlank { null }?.let { JupyterIntent.SelectCell(it) }
        "DeleteCell" -> payload.ifBlank { null }?.let { JupyterIntent.DeleteCell(it) }

        "Reload" -> JupyterIntent.Reload
        "ClearAllOutputs" -> JupyterIntent.ClearAllOutputs
        "Save" -> JupyterIntent.Save
        "ClearError" -> JupyterIntent.ClearError

        else -> null
    }
}

private val jupyterIntentJson = Json { ignoreUnknownKeys = true; isLenient = true }
