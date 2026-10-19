package com.voicestudio.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.local.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStorageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val name = "test-" + UUID.randomUUID() + ".db"
    private val root = File(app.filesDir, "library-" + UUID.randomUUID())
    private val opened = mutableListOf<Library>()
    private fun library() = Library(app, name, root).also { opened += it }
    @After fun close() { opened.forEach { it.close() }; root.deleteRecursively(); app.deleteDatabase(name) }
    private fun tone(seconds: Double, rate: Int = 22050) = LocalAudio(FloatArray((seconds * rate).toInt()) { (sin(2 * PI * 220 * it / rate) * .3).toFloat() }, rate)

    @Test fun projectsItemsAndExportsSurviveAProcessRestart() = runBlocking<Unit> {
        val first = library()
        val project = first.createProject("Meu livro", ProjectKind.AUDIOBOOK)
        val item = first.addAudio(project.id, ItemKind.SPEECH, "Olá: mundo/teste?", "Olá mundo", tone(1.0), "piper-pt-br-faber", "Faber", "pt-BR")
        first.recordExport(item.id, "content://media/external/downloads/1", "Download/VoiceStudio/Meu livro/Olá mundo teste.wav", "Olá mundo teste.wav", item.size)
        first.close()
        // A new instance on the same database file models the app starting again.
        val second = library()
        assertEquals(listOf("Meu livro"), second.projects().map { it.name })
        val reloaded = second.item(item.id)!!
        assertEquals(item.sha256, reloaded.sha256)
        assertTrue(second.verify(reloaded))
        assertEquals(1000L, reloaded.durationMs)
        assertEquals("Download/VoiceStudio/Meu livro/Olá mundo teste.wav", second.exports(item.id).single().location)
        val wav = second.file(reloaded)!!
        assertEquals("RIFF", String(wav.readBytes().copyOfRange(0, 4)))
        assertEquals(22050, readWave(wav).sampleRate)
        assertTrue("synthetic provenance marker", String(wav.readBytes(), Charsets.ISO_8859_1).contains("VoiceStudio synthetic audio"))
    }

    @Test fun deletingAProjectReportsAndRemovesOnlyPrivateFiles() = runBlocking<Unit> {
        val library = library()
        val keep = library.createProject("Outro", ProjectKind.GENERAL)
        val gone = library.createProject("Excluir", ProjectKind.GENERAL)
        val kept = library.addAudio(keep.id, ItemKind.SPEECH, "fica", "", tone(.2), null, null, null)
        val removed = library.addAudio(gone.id, ItemKind.SPEECH, "vai", "", tone(.2), null, null, null)
        library.recordExport(removed.id, "content://x", "Download/VoiceStudio/x.wav", "x.wav", removed.size)
        val outside = File(app.cacheDir, "exported-copy.wav").apply { writeBytes(library.file(removed)!!.readBytes()) }
        val report = library.deleteProject(gone.id)
        assertEquals(DeletionReport(1, removed.size, 1), report)
        assertNull(library.item(removed.id)); assertFalse(File(root, gone.id).exists())
        assertTrue("other projects untouched", library.verify(library.item(kept.id)!!))
        assertTrue("exported copies are never deleted", outside.exists())
        outside.delete()
    }

    @Test fun interruptedWritesAreCleanedAndRunningTasksRequeued() = runBlocking<Unit> {
        val library = library()
        val project = library.createProject("P", ProjectKind.GENERAL)
        val task = library.createTask(TaskKind.SPEECH, project.id, "t", "{}")
        library.updateTask(task.id, state = TaskState.RUNNING)
        val partial = File(root, project.id + "/broken.wav.part").apply { parentFile!!.mkdirs(); writeText("half") }
        library.recover()
        assertFalse(partial.exists())
        assertEquals(TaskState.QUEUED, library.task(task.id)!!.state)
        try { library.deleteProject(project.id); fail("a project with waiting tasks is protected") } catch (e: ClientFailure) { assertEquals("vozia.project.busy", e.key) }
    }

    private class FakeEngine(val failAt: Int = -1, val midway: Boolean = false) : JobEngine {
        var calls = 0
        override fun model(id: String) = PhoneModel(id, "asr", "sherpa-onnx", files = emptyList(), totalBytes = 0, languages = listOf("pt"), minRamGb = 0.0,
            licence = ModelLicence("x", "commercial-ok", false, "", "", "0".repeat(64)), name = id)
        override suspend fun speak(choice: VoiceChoice, text: String, progress: (Float) -> Unit): LocalAudio {
            if (calls++ == failAt) throw ClientFailure("mobile.local.inference_failed")
            // A slow engine reporting halfway through, from its own thread in the real app.
            if (midway) { progress(.5f); kotlinx.coroutines.delay(50) }
            return LocalAudio(FloatArray(2205) { .1f }, 22050)
        }
        override suspend fun transcribe(model: PhoneModel, audio: LocalAudio, language: String?, progress: (Float) -> Unit) =
            listOf(SpeechSegment(0.5, 1.75, "Olá, mundo."), SpeechSegment(2.0, 3.0, "Segunda frase."))
        override suspend fun decode(source: File, output: File, progress: (Float) -> Unit): PcmFile { writePcm(LocalAudio(FloatArray(24000 * 4) { .1f }, 24000), output); return PcmFile(output) }
    }

    @Test fun longSpeechResumesFromCheckpointsAfterAFailure() = runBlocking<Unit> {
        val library = library()
        val project = library.createProject("P", ProjectKind.GENERAL)
        val text = (1..30).joinToString(" ") { "Esta é a frase número $it de um texto longo para teste." }
        val chunks = splitForSynthesis(text, LibraryJobs.CHUNK).size
        assertTrue(chunks >= 2)
        val input = TaskJson.encodeToString(SpeechInput.serializer(), SpeechInput(text, "Longo", VoiceChoice("piper-pt-br-faber")))
        val task = library.createTask(TaskKind.SPEECH, project.id, "Longo", input)
        val failing = FakeEngine(failAt = 1)
        try { LibraryJobs(library, failing).run(task) { _, _ -> }; fail() } catch (e: ClientFailure) { assertEquals("mobile.local.inference_failed", e.key) }
        val resumed = FakeEngine()
        val result = LibraryJobs(library, resumed).run(task) { _, _ -> }
        assertEquals("only chunks without a checkpoint are synthesized again", chunks - 1, resumed.calls)
        val item = library.item(result.itemId!!)!!
        assertTrue(library.verify(item))
        assertTrue(item.durationMs >= chunks * 100L)
        assertFalse("scratch folder removed", library.taskDirectory(task.id).exists())
    }

    @Test fun progressMovesInsideAChunkAndClonedVoicesUseShortChunks() = runBlocking<Unit> {
        val library = library()
        val project = library.createProject("P", ProjectKind.GENERAL)
        val text = (1..12).joinToString(" ") { "Esta é a frase número $it de um texto para a voz clonada." }
        val input = TaskJson.encodeToString(SpeechInput.serializer(), SpeechInput(text, "Clone", VoiceChoice("omnivoice-q4-k-m", voice = "v")))
        val task = library.createTask(TaskKind.SPEECH, project.id, "Clone", input)
        val engine = FakeEngine(midway = true)
        val reported = mutableListOf<Float>()
        LibraryJobs(library, engine).run(task) { value, _ -> reported += value }
        val chunks = splitForSynthesis(text, LibraryJobs.CLONE_CHUNK).size
        assertTrue("a cloned voice is split finer than ordinary speech", chunks > splitForSynthesis(text, LibraryJobs.CHUNK).size)
        assertEquals(chunks, engine.calls)
        // Halfway through the first chunk is reported before that chunk is done.
        val firstHalf = .5f / chunks * .97f
        assertTrue(reported.toString(), reported.any { kotlin.math.abs(it - firstHalf) < 1e-4 })
        assertEquals(reported.sorted(), reported)
    }

    @Test fun audiobookCreatesChaptersAndAMergedBookWithChapterMarks() = runBlocking<Unit> {
        val library = library()
        val project = library.createProject("Livro", ProjectKind.AUDIOBOOK)
        library.replaceChapters(project.id, TextImport.split("Capítulo 1\nEra uma vez.\n\nCapítulo 2\nFim da história.") { "Parte $it" })
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), library.chapters(project.id).map { it.title })
        val input = TaskJson.encodeToString(AudiobookInput.serializer(), AudiobookInput(VoiceChoice("supertonic-3-int8", language = "pt"), merge = true, gapSeconds = .5))
        val task = library.createTask(TaskKind.AUDIOBOOK, project.id, "Livro", input)
        val result = LibraryJobs(library, FakeEngine()).run(task) { _, _ -> }
        val items = library.items(project.id)
        assertEquals(2, items.count { it.kind == ItemKind.CHAPTER })
        val book = library.item(result.itemId!!)!!
        assertEquals(ItemKind.AUDIOBOOK, book.kind)
        val marks = TaskJson.decodeFromString(AudiobookMeta.serializer(), book.meta).chapters
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), marks.map { it.title })
        assertEquals(0L, marks[0].startMs); assertEquals(600L, marks[1].startMs)
        assertTrue(library.chapters(project.id).all { it.state == "done" && it.itemId != null })
        try { library.replaceChapters(project.id, listOf("x" to "y")); fail() } catch (e: ClientFailure) { assertEquals("vozia.audiobook.chapters_generated", e.key) }
    }

    @Test fun transcriptionProducesTextSrtAndVtt() = runBlocking<Unit> {
        val library = library()
        val project = library.createProject("Entrevista", ProjectKind.TRANSCRIPTION)
        val source = File(app.cacheDir, "in.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
        val imported = library.addFile(project.id, ItemKind.IMPORT, "entrevista.m4a", "", source, "audio/mp4", "m4a")
        val task = library.createTask(TaskKind.TRANSCRIBE, project.id, "Entrevista",
            TaskJson.encodeToString(TranscribeInput.serializer(), TranscribeInput(imported.id, "whisper-base-int8", "pt")))
        LibraryJobs(library, FakeEngine()).run(task) { _, _ -> }
        val items = library.items(project.id)
        assertEquals("Olá, mundo.\nSegunda frase.\n", library.file(items.first { it.kind == ItemKind.TRANSCRIPT })!!.readText())
        val srt = library.file(items.first { it.mime == "application/x-subrip" })!!.readText()
        assertTrue(srt.startsWith("1\n00:00:00,500 --> 00:00:01,750\nOlá, mundo.\n"))
        val vtt = library.file(items.first { it.mime == "text/vtt" })!!.readText()
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.500 --> 00:00:01.750\n"))
    }

    @Test fun safeNamesAndCollisions() {
        assertEquals("Olá mundo teste.wav", safeFileName("Olá: mundo/teste?", "wav"))
        assertEquals("VoiceStudio.wav", safeFileName("  ..  ", "WAV"))
        assertEquals("_CON.txt", safeFileName("CON", "txt"))
        assertTrue(safeFileName("a".repeat(300), "wav").length <= 84)
        val taken = setOf("Livro.wav", "Livro (2).wav")
        assertEquals("Livro (3).wav", uniqueName("Livro.wav") { it in taken })
    }

    @Test fun textImportHandlesHeadingsLegacyEncodingAndEpub() {
        val latin1 = "Capítulo 1\nAção e emoção.\n\nCapítulo 2\nFim.".toByteArray(Charsets.ISO_8859_1)
        val chapters = TextImport.chapters(latin1, "livro.txt") { "Parte $it" }
        assertEquals(listOf("Capítulo 1" to "Ação e emoção.", "Capítulo 2" to "Fim."), chapters)
        val plain = TextImport.split(("Parágrafo longo. ".repeat(40) + "\n\n").repeat(30), 2000) { "Parte $it" }
        assertTrue(plain.size > 1 && plain.first().first == "Parte 1")
        val epub = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
                put("OEBPS/content.opf", """<package><manifest><item id="c2" href="ch2.xhtml"/><item id="c1" href="ch1.xhtml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
                put("OEBPS/ch1.xhtml", "<html><body><h1>Um</h1><p>Primeiro &amp; único.</p></body></html>")
                put("OEBPS/ch2.xhtml", "<html><body><h2>Dois</h2><p>Segundo.</p></body></html>")
            }
        }.toByteArray()
        assertEquals(listOf("Um" to "Primeiro & único.", "Dois" to "Segundo."), TextImport.chapters(epub, "book.epub") { "Parte $it" })
    }
}
