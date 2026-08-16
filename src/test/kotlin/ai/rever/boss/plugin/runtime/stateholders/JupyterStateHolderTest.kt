package ai.rever.boss.plugin.runtime.stateholders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [JupyterStateHolder] — the notebook document surface an out-of-process
 * child publishes to a host renderer.
 *
 * The load/save cases go through real files: `.ipynb` handling *is* file handling,
 * and the holder reads and writes with [File] directly because the runtime's OOP
 * context has no filesystem provider.
 */
class JupyterStateHolderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tempDir: File = Files.createTempDirectory("jupyter-holder-test").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun holder() = JupyterStateHolder(scope)

    private fun notebookFile(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }

    // region initial state

    /**
     * The failure this holder exists to fix: a holder that never calls
     * `updateState` stays at version 0, and the kernel bridge's strictly-newer
     * guard means a version-0 envelope never applies host-side — the panel sits at
     * "waiting for the first synced state" forever (the `bookmarks` gap). So the
     * constructor must publish, with no data source and no intent.
     */
    @Test
    fun `publishes a versioned starting document with no prompting`() {
        val holder = holder()

        assertTrue(holder.version > 0, "version must advance past 0 or nothing ever renders")
        val state = holder.currentState()
        assertEquals(1, state.cells.size)
        assertEquals("code", state.cells.single().cellType)
        assertEquals("", state.cells.single().source)
        assertEquals(state.cells.single().id, state.selectedCellId)
        assertEquals("Untitled", state.notebookName)
        assertNull(state.notebookPath)
        assertFalse(state.dirty)
    }

    /** Honest capability reporting, so the host renders no dead Run/Assistant. */
    @Test
    fun `reports execution and AI as unavailable`() {
        val state = holder().currentState()

        assertFalse(state.executionAvailable, "no kernel can be driven from a child JVM")
        assertFalse(state.aiAvailable, "RemotePluginContext exposes no LLM provider")
    }

    // endregion

    // region load

    @Test
    fun `loads cells sources and stored outputs from a notebook on disk`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        val state = holder.currentState()
        assertNull(state.loadError, "the fixture is valid nbformat")
        assertEquals(file.absolutePath, state.notebookPath)
        assertEquals("demo", state.notebookName)
        assertEquals(listOf("md-1", "code-1", "code-2"), state.cells.map { it.id })
        assertEquals("markdown", state.cells[0].cellType)
        // `source` arrives as a line array on disk and must join back to one string.
        assertEquals("# Title\ntext\n", state.cells[0].source)
        assertEquals(7, state.cells[1].executionCount)
        assertEquals("md-1", state.selectedCellId)
        assertFalse(state.dirty, "a fresh load is not dirty")
    }

    @Test
    fun `mirrors stream data and error outputs`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        val outputs = holder.currentState().cells[1].outputs
        assertEquals(2, outputs.size)
        assertEquals("stream", outputs[0].kind)
        assertEquals("stdout", outputs[0].name)
        assertEquals("hi\n", outputs[0].text)
        assertEquals("data", outputs[1].kind)
        assertEquals("<Figure>", outputs[1].text)

        val error = holder.currentState().cells[2].outputs.single()
        assertEquals("error", error.kind)
        assertEquals("ZeroDivisionError", error.ename)
        assertEquals("division by zero", error.evalue)
        assertEquals("line one\nline two", error.text)
    }

    /**
     * The state envelope is re-sent in full on every change, so binary payloads
     * must stay out of it — their MIME types are advertised instead.
     */
    @Test
    fun `lists rich output mime types without shipping their bytes`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        val dataOutput = holder.currentState().cells[1].outputs[1]
        assertEquals(listOf("text/plain", "image/png"), dataOutput.mimeTypes)
        val encoded = Json.encodeToString(JupyterState.serializer(), holder.currentState())
        assertFalse(
            encoded.contains(PNG_BASE64),
            "the base64 image must not reach the wire: $encoded",
        )
    }

    @Test
    fun `only code cells carry outputs`() {
        val file = notebookFile("outputs-on-markdown.ipynb", MARKDOWN_WITH_OUTPUTS)
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        assertEquals(emptyList(), holder.currentState().cells.single().outputs)
    }

    @Test
    fun `reports a missing file as a load error and keeps the current document`() {
        val holder = holder()
        val before = holder.currentState().cells

        holder.onIntent(JupyterIntent.OpenNotebook(File(tempDir, "nope.ipynb").absolutePath))

        val state = holder.currentState()
        val error = assertNotNull(state.loadError)
        assertTrue(error.contains("Could not read"), error)
        assertEquals(before, state.cells, "a failed open must not destroy the open document")
    }

    @Test
    fun `reports unparseable content as a load error`() {
        val file = notebookFile("broken.ipynb", "{ this is not json")
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        val error = assertNotNull(holder.currentState().loadError)
        assertTrue(error.contains("Not a readable .ipynb"), error)
    }

    @Test
    fun `a notebook with no cells still gets one empty code cell`() {
        val file = notebookFile("empty.ipynb", """{"cells": [], "nbformat": 4, "nbformat_minor": 5}""")
        val holder = holder()

        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        assertEquals(1, holder.currentState().cells.size)
        assertEquals("code", holder.currentState().cells.single().cellType)
    }

    @Test
    fun `reload rereads the file and drops unsaved edits`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SetCellSource("code-1", "edited"))
        assertTrue(holder.currentState().dirty)

        holder.onIntent(JupyterIntent.Reload)

        assertEquals("print('hi')\n", holder.currentState().cells[1].source)
        assertFalse(holder.currentState().dirty)
    }

    @Test
    fun `reload without an open notebook is an error not a crash`() {
        val holder = holder()

        holder.onIntent(JupyterIntent.Reload)

        assertEquals("No notebook is open", holder.currentState().loadError)
    }

    // endregion

    // region authoring

    @Test
    fun `editing a cell source marks the document dirty`() {
        val holder = holder()
        val cellId = holder.currentState().cells.single().id

        holder.onIntent(JupyterIntent.SetCellSource(cellId, "x = 1"))

        assertEquals("x = 1", holder.currentState().cells.single().source)
        assertTrue(holder.currentState().dirty)
    }

    @Test
    fun `intents naming an unknown cell change nothing`() {
        val holder = holder()
        val before = holder.currentState()

        holder.onIntent(JupyterIntent.SetCellSource("ghost", "x = 1"))
        holder.onIntent(JupyterIntent.SelectCell("ghost"))
        holder.onIntent(JupyterIntent.ChangeCellType("ghost", "markdown"))

        assertEquals(before.cells, holder.currentState().cells)
        assertEquals(before.selectedCellId, holder.currentState().selectedCellId)
        assertFalse(holder.currentState().dirty)
    }

    @Test
    fun `adds a cell after the named one and selects it`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        holder.onIntent(JupyterIntent.AddCell("md-1", "markdown"))

        val cells = holder.currentState().cells
        assertEquals(4, cells.size)
        assertEquals("md-1", cells[0].id)
        assertEquals("markdown", cells[1].cellType)
        assertEquals("code-1", cells[2].id)
        assertEquals(cells[1].id, holder.currentState().selectedCellId)
    }

    @Test
    fun `adds a cell at the end when no anchor is given`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        holder.onIntent(JupyterIntent.AddCell(null, "code"))

        assertEquals(4, holder.currentState().cells.size)
        assertEquals("code", holder.currentState().cells.last().cellType)
    }

    @Test
    fun `deleting the selected cell moves the selection`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SelectCell("code-1"))

        holder.onIntent(JupyterIntent.DeleteCell("code-1"))

        assertEquals(listOf("md-1", "code-2"), holder.currentState().cells.map { it.id })
        assertEquals("md-1", holder.currentState().selectedCellId)
    }

    /** A notebook always has at least one cell, like the plugin's own model. */
    @Test
    fun `refuses to delete the last remaining cell`() {
        val holder = holder()
        val cellId = holder.currentState().cells.single().id

        holder.onIntent(JupyterIntent.DeleteCell(cellId))

        assertEquals(1, holder.currentState().cells.size)
    }

    @Test
    fun `moves a cell up and down and refuses to move past the ends`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        holder.onIntent(JupyterIntent.MoveCell("code-1", up = true))
        assertEquals(listOf("code-1", "md-1", "code-2"), holder.currentState().cells.map { it.id })

        holder.onIntent(JupyterIntent.MoveCell("code-1", up = true))
        assertEquals(
            listOf("code-1", "md-1", "code-2"),
            holder.currentState().cells.map { it.id },
            "the first cell cannot move up",
        )

        holder.onIntent(JupyterIntent.MoveCell("code-2", up = false))
        assertEquals(
            listOf("code-1", "md-1", "code-2"),
            holder.currentState().cells.map { it.id },
            "the last cell cannot move down",
        )
    }

    @Test
    fun `changing a code cell to markdown drops its outputs`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        holder.onIntent(JupyterIntent.ChangeCellType("code-1", "markdown"))

        val cell = holder.currentState().cells[1]
        assertEquals("markdown", cell.cellType)
        assertEquals(emptyList(), cell.outputs)
        assertNull(cell.executionCount)
    }

    @Test
    fun `an unrecognized cell type falls back to code`() {
        val holder = holder()

        holder.onIntent(JupyterIntent.AddCell(null, "SQL"))

        assertEquals("code", holder.currentState().cells.last().cellType)
    }

    @Test
    fun `clearing outputs empties every cell's outputs and execution counts`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))

        holder.onIntent(JupyterIntent.ClearAllOutputs)

        assertTrue(holder.currentState().cells.all { it.outputs.isEmpty() })
        assertTrue(holder.currentState().cells.all { it.executionCount == null })
        assertTrue(holder.currentState().dirty)
    }

    // endregion

    // region save

    @Test
    fun `saving writes the edited source back as an nbformat line array`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SetCellSource("code-1", "print('edited')\nprint('again')\n"))

        holder.onIntent(JupyterIntent.Save)

        assertNull(holder.currentState().saveError)
        assertFalse(holder.currentState().dirty, "a successful save clears dirty")
        val saved = file.readJson()
        assertEquals(
            listOf("print('edited')\n", "print('again')\n"),
            saved.cells()[1].jsonObject["source"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * The holder models a subset of nbformat, so a save must not be a downgrade:
     * anything it does not describe has to survive verbatim.
     */
    @Test
    fun `saving preserves unmodelled notebook and cell keys`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SetCellSource("code-1", "edited\n"))

        holder.onIntent(JupyterIntent.Save)

        val saved = file.readJson()
        assertEquals("survive", saved["unmodelled_root_key"]!!.jsonPrimitive.content)
        assertEquals(
            "python3",
            saved["metadata"]!!.jsonObject["kernelspec"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        val codeCell = saved.cells()[1].jsonObject
        assertEquals("keep me", codeCell["collapsed_by_default"]!!.jsonPrimitive.content)
        assertEquals("true", codeCell["metadata"]!!.jsonObject["scrolled"]!!.jsonPrimitive.content)
    }

    /**
     * This holder cannot re-execute a cell, so it must never replace a rich stored
     * output with the text-only mirror it publishes.
     */
    @Test
    fun `saving preserves rich stored outputs verbatim`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SetCellSource("code-1", "edited\n"))

        holder.onIntent(JupyterIntent.Save)

        val outputs = file.readJson().cells()[1].jsonObject["outputs"]!!.jsonArray
        assertEquals(2, outputs.size)
        val data = outputs[1].jsonObject["data"]!!.jsonObject
        assertEquals(PNG_BASE64, data["image/png"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a saved document reloads to the same state`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.SetCellSource("code-1", "round\n"))
        holder.onIntent(JupyterIntent.Save)
        val afterSave = holder.currentState()

        holder.onIntent(JupyterIntent.Reload)

        assertEquals(afterSave.cells, holder.currentState().cells)
    }

    @Test
    fun `cells added out-of-process survive the round trip`() {
        val file = notebookFile("demo.ipynb", NOTEBOOK)
        val holder = holder()
        holder.onIntent(JupyterIntent.OpenNotebook(file.absolutePath))
        holder.onIntent(JupyterIntent.AddCell("code-2", "markdown"))
        val addedId = holder.currentState().cells.last().id
        holder.onIntent(JupyterIntent.SetCellSource(addedId, "## new\n"))

        holder.onIntent(JupyterIntent.Save)
        holder.onIntent(JupyterIntent.Reload)

        val reloaded = holder.currentState().cells.last()
        assertEquals(addedId, reloaded.id, "a generated cell id must be written out")
        assertEquals("markdown", reloaded.cellType)
        assertEquals("## new\n", reloaded.source)
    }

    @Test
    fun `saving with no open path is an error not a crash`() {
        val holder = holder()

        holder.onIntent(JupyterIntent.Save)

        assertEquals("No path to save to — open a notebook first", holder.currentState().saveError)
    }

    @Test
    fun `clearing errors resets both load and save errors`() {
        val holder = holder()
        holder.onIntent(JupyterIntent.Save)
        holder.onIntent(JupyterIntent.Reload)
        assertNotNull(holder.currentState().saveError)
        assertNotNull(holder.currentState().loadError)

        holder.onIntent(JupyterIntent.ClearError)

        assertNull(holder.currentState().saveError)
        assertNull(holder.currentState().loadError)
    }

    // endregion

    // region intent decoding

    @Test
    fun `decodes single-field intents from a bare string payload`() {
        assertEquals(
            JupyterIntent.OpenNotebook("/tmp/a.ipynb"),
            decodeJupyterIntent("OpenNotebook", "/tmp/a.ipynb"),
        )
        assertEquals(JupyterIntent.SelectCell("c1"), decodeJupyterIntent("SelectCell", "c1"))
        assertEquals(JupyterIntent.DeleteCell("c1"), decodeJupyterIntent("DeleteCell", "c1"))
        assertEquals(JupyterIntent.Save, decodeJupyterIntent("Save", ""))
        assertEquals(JupyterIntent.Reload, decodeJupyterIntent("Reload", ""))
        assertEquals(JupyterIntent.ClearAllOutputs, decodeJupyterIntent("ClearAllOutputs", ""))
        assertEquals(JupyterIntent.ClearError, decodeJupyterIntent("ClearError", ""))
    }

    @Test
    fun `decodes multi-field intents from a json payload`() {
        assertEquals(
            JupyterIntent.SetCellSource("c1", "x = 1"),
            decodeJupyterIntent("SetCellSource", """{"cellId":"c1","source":"x = 1"}"""),
        )
        assertEquals(
            JupyterIntent.AddCell("c1", "markdown"),
            decodeJupyterIntent("AddCell", """{"afterCellId":"c1","cellType":"markdown"}"""),
        )
        assertEquals(
            JupyterIntent.AddCell(null, "code"),
            decodeJupyterIntent("AddCell", "{}"),
            "an anchorless AddCell is valid — it appends",
        )
        assertEquals(
            JupyterIntent.MoveCell("c1", up = true),
            decodeJupyterIntent("MoveCell", """{"cellId":"c1","up":true}"""),
        )
        assertEquals(
            JupyterIntent.ChangeCellType("c1", "raw"),
            decodeJupyterIntent("ChangeCellType", """{"cellId":"c1","cellType":"raw"}"""),
        )
    }

    /**
     * A dropped intent is recoverable; an intent applied from a half-read message
     * silently edits the wrong thing. So anything malformed must decode to null.
     */
    @Test
    fun `refuses unknown types blank payloads and incomplete json`() {
        assertNull(decodeJupyterIntent("NoSuchIntent", "whatever"))
        assertNull(decodeJupyterIntent("OpenNotebook", ""))
        assertNull(decodeJupyterIntent("SelectCell", ""))
        assertNull(decodeJupyterIntent("SetCellSource", """{"source":"orphan"}"""))
        assertNull(decodeJupyterIntent("SetCellSource", "not json at all"))
        assertNull(decodeJupyterIntent("MoveCell", """{"cellId":"c1"}"""))
        assertNull(decodeJupyterIntent("ChangeCellType", """{"cellId":"c1"}"""))
    }

    // endregion

    private fun File.readJson(): JsonObject =
        Json.parseToJsonElement(readText()).jsonObject

    private fun JsonObject.cells(): JsonArray = this["cells"]!!.jsonArray

    private companion object {
        const val PNG_BASE64 = "QUJD"

        val NOTEBOOK = """
            {
             "cells": [
              {
               "cell_type": "markdown",
               "id": "md-1",
               "metadata": {},
               "source": ["# Title\n", "text\n"]
              },
              {
               "cell_type": "code",
               "id": "code-1",
               "execution_count": 7,
               "metadata": {"scrolled": true},
               "collapsed_by_default": "keep me",
               "source": ["print('hi')\n"],
               "outputs": [
                {"output_type": "stream", "name": "stdout", "text": ["hi\n"]},
                {
                 "output_type": "display_data",
                 "data": {"text/plain": ["<Figure>"], "image/png": "$PNG_BASE64"},
                 "metadata": {}
                }
               ]
              },
              {
               "cell_type": "code",
               "id": "code-2",
               "execution_count": null,
               "metadata": {},
               "source": ["1/0\n"],
               "outputs": [
                {
                 "output_type": "error",
                 "ename": "ZeroDivisionError",
                 "evalue": "division by zero",
                 "traceback": ["line one", "line two"]
                }
               ]
              }
             ],
             "metadata": {"kernelspec": {"name": "python3"}},
             "unmodelled_root_key": "survive",
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        val MARKDOWN_WITH_OUTPUTS = """
            {
             "cells": [
              {
               "cell_type": "markdown",
               "id": "md-only",
               "source": ["text"],
               "outputs": [{"output_type": "stream", "name": "stdout", "text": ["nope"]}]
              }
             ],
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()
    }
}
