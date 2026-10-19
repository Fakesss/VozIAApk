package com.voicestudio.mobile

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.local.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/** What the generate phase leaves behind for the restart, reboot and update phases. */
@Serializable data class E2eRecord(val project: String, val item: String, val sha256: String, val exportUri: String, val exportLocation: String,
    val exportSha256: String, val m4aUri: String, val systemItem: String? = null)

internal fun e2eFile() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "e2e-record.json")
internal fun report(key: String, value: String) {
    // Visible in `am instrument -r` output, so the CI log shows exactly what was checked.
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("vozia.$key", value) })
    android.util.Log.i("VoziaE2E", "$key=$value")
}
internal fun sha256(bytes: java.io.InputStream): String = bytes.use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1 shl 16)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * End-to-end on a real device or emulator: the first run downloads the Portuguese
 * Piper voice from its pinned GitHub archive (pass acceptModelLicences=true after
 * reviewing the terms), then every step uses the app's own storage and workers.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VoziaDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val runtime = PhoneRuntime.get(context)
    private val library = Library.get(context)
    private val exporter = Exporter(context, library)
    private fun piper() = runtime.model("piper-pt-br-faber")
    private fun whisper() = runtime.model("whisper-tiny-int8")

    private suspend fun awaitTask(id: String, timeoutMs: Long = 10 * 60_000L, until: (TaskRecord) -> Boolean = { !it.active }): TaskRecord = withTimeout(timeoutMs) {
        while (true) {
            val task = library.task(id)!!
            if (until(task)) return@withTimeout task
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private suspend fun speak(text: String, choice: VoiceChoice, title: String, project: Project): TaskRecord {
        val task = library.createTask(TaskKind.SPEECH, project.id, title, TaskJson.encodeToString(SpeechInput.serializer(), SpeechInput(text, title, choice)))
        LibraryWorker.enqueue(context, task.id)
        return task
    }
    private fun rms(audio: LocalAudio) = sqrt(audio.samples.fold(0.0) { a, s -> a + s * s } / audio.samples.size)

    @Test fun a_nativeRuntimesLoad() = runBlocking<Unit> {
        val loaded = verifyNativeRuntimes()
        report("native", loaded.toString())
        assertEquals(setOf("sherpa-onnx", "omnivoice.cpp", "llama.cpp"), loaded.keys)
    }

    @Test fun b_downloadPortugueseVoice() = runBlocking<Unit> {
        val model = piper()
        if (!runtime.downloads.installed(model)) {
            assertEquals("Review the Piper pt-BR terms, then pass acceptModelLicences=true", "true", args.getString("acceptModelLicences"))
            runtime.licence.record(model, true)
            val started = System.currentTimeMillis()
            runtime.downloads.download(model, true)
            report("download_seconds", ((System.currentTimeMillis() - started) / 1000).toString())
        }
        assertTrue(runtime.downloads.installed(model))
        if (args.getString("asr") == "true" && !runtime.downloads.installed(whisper())) {
            runtime.licence.record(whisper(), true)
            runtime.downloads.download(whisper(), true)
        }
    }

    @Test fun c_generateSpeechExportAndRecord() = runBlocking<Unit> {
        val project = library.createProject("Teste E2E", ProjectKind.GENERAL)
        val text = "Olá! Este é um teste de voz em português, gerado no celular pelo VoiceStudio. O arquivo será salvo na pasta Downloads."
        val started = System.currentTimeMillis()
        val task = awaitTask(speak(text, VoiceChoice("piper-pt-br-faber", language = "pt-BR", speakerName = "Faber"), "Teste de voz", project).id)
        report("speech_seconds", ((System.currentTimeMillis() - started) / 1000.0).toString())
        assertEquals(task.error ?: "", TaskState.SUCCEEDED, task.state)
        val item = library.item(task.resultItem!!)!!
        assertTrue(library.verify(item))
        val audio = readWave(library.file(item)!!)
        report("speech_audio", "rate=${audio.sampleRate} seconds=${audio.samples.size / audio.sampleRate.toDouble()} rms=${rms(audio)}")
        assertEquals(22050, audio.sampleRate)
        assertTrue("several seconds of speech", audio.samples.size > audio.sampleRate * 3)
        assertTrue("not silence", rms(audio) > 0.01)

        if (args.getString("asr") == "true") {
            val words = runtime.engine.transcribeSegments(whisper(), audio, "pt").joinToString(" ") { it.text }.lowercase()
            report("asr_roundtrip", words)
            assertTrue(words, listOf("teste", "voz", "português", "celular", "downloads").count { it in words } >= 2)
        }

        val wav = exporter.toDownloads(item, project.name)
        report("export_location", wav.location)
        assertTrue(wav.location, wav.location.startsWith("Download/VoiceStudio/Teste E2E/") && wav.location.endsWith(".wav"))
        context.contentResolver.query(wav.uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(item.size, it.getLong(0)); assertTrue(it.getString(1), it.getString(1) in setOf("audio/wav", "audio/x-wav")); assertEquals(0, it.getInt(2))
        }
        val exportSha = sha256(context.contentResolver.openInputStream(wav.uri)!!)
        assertEquals(item.sha256, exportSha)
        val m4a = exporter.toDownloads(item, project.name, "m4a")
        report("export_m4a", m4a.location)
        val head = ByteArray(12).also { bytes -> context.contentResolver.openInputStream(m4a.uri)!!.use { java.io.DataInputStream(it).readFully(bytes) } }
        assertEquals("ftyp", String(head, 4, 4, Charsets.US_ASCII))
        // Exporting again never overwrites: the second copy gets a numbered name.
        val again = exporter.toDownloads(item, project.name)
        assertNotEquals(wav.name, again.name)
        report("export_again", again.location)
        e2eFile().writeText(Json.encodeToString(E2eRecord.serializer(), E2eRecord(project.id, item.id, item.sha256!!, wav.uri.toString(), wav.location, exportSha, m4a.uri.toString())))
    }

    @Test fun d_systemVoiceWhenTheDeviceHasOne() = runBlocking<Unit> {
        val languages = runtime.speech.languages()
        report("system_tts_languages", languages.joinToString(","))
        assumeTrue("this image has no offline TextToSpeech voice", languages.isNotEmpty())
        val project = library.project(Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText()).project)!!
        val language = languages.firstOrNull { it.startsWith("pt") } ?: languages.first()
        val task = awaitTask(speak("Teste da voz do sistema.", VoiceChoice(SYSTEM_TTS_ID, language = language, speakerName = "system"), "Voz do sistema", project).id)
        assertEquals(task.error ?: "", TaskState.SUCCEEDED, task.state)
        val item = library.item(task.resultItem!!)!!
        assertTrue(rms(readWave(library.file(item)!!)) > 0.001)
        val record = Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText())
        e2eFile().writeText(Json.encodeToString(E2eRecord.serializer(), record.copy(systemItem = item.id)))
    }

    @Test fun e_cancellingALongTaskStopsItWithoutSavingAResult() = runBlocking<Unit> {
        val project = library.project(Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText()).project)!!
        val before = library.items(project.id).size
        val long = (1..80).joinToString(" ") { "Este é o parágrafo número $it de um texto propositalmente longo para testar o cancelamento." }
        val task = speak(long, VoiceChoice("piper-pt-br-faber", language = "pt-BR"), "Cancelar", project)
        awaitTask(task.id, 120_000) { it.state == TaskState.RUNNING && it.progress > 0f }
        LibraryWorker.cancel(context, library, task.id)
        val finished = awaitTask(task.id, 60_000) { it.state == TaskState.CANCELLED }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("library:${task.id}")
        withTimeout(60_000) { work.first { infos -> infos.all { it.state.isFinished } } }
        delay(1000)
        assertEquals(TaskState.CANCELLED, library.task(task.id)!!.state)
        assertNull(finished.resultItem)
        assertEquals("no partial result was saved", before, library.items(project.id).size)
        assertEquals(WorkInfo.State.CANCELLED, work.first().single().state)
    }

    @Test fun f_missingModelFailsWithAClearError() = runBlocking<Unit> {
        val project = library.project(Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText()).project)!!
        val absent = runtime.models().first { it.capability == "tts" && !it.system && !runtime.downloads.installed(it) }
        val task = awaitTask(speak("Sem modelo.", VoiceChoice(absent.id), "Falha esperada", project).id)
        report("failure_key", task.error.orEmpty())
        assertEquals(TaskState.FAILED, task.state)
        assertTrue(task.error.orEmpty(), task.error in setOf("mobile.local.licence_required", "mobile.local.model_required"))
    }
}

/**
 * Run in a new process after force-stop, after `adb reboot` and after installing an
 * update: everything recorded by [VoziaDeviceTest] must still be there, intact.
 */
@RunWith(AndroidJUnit4::class)
class VoziaPersistenceTest {
    @Test fun projectAudioAndExportsSurvive() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText())
        val library = Library.get(context)
        val project = library.project(record.project)
        assertNotNull("project survived", project)
        val item = library.item(record.item)!!
        assertEquals(record.sha256, item.sha256)
        assertTrue("private WAV intact", library.verify(item))
        assertTrue(library.exports(item.id).any { it.location == record.exportLocation })
        assertEquals("exported copy intact", record.exportSha256, sha256(context.contentResolver.openInputStream(Uri.parse(record.exportUri))!!))
        assertNotNull(context.contentResolver.openInputStream(Uri.parse(record.m4aUri))?.use { it.read() })
        record.systemItem?.let { assertTrue(library.verify(library.item(it)!!)) }
        assertTrue("model still installed", PhoneRuntime.get(context).downloads.installed(PhoneRuntime.get(context).model("piper-pt-br-faber")))
        val id = ContentUris.parseId(Uri.parse(record.exportUri))
        report("persisted", "project=${project!!.name} item=${item.title} export=${record.exportLocation} mediaId=$id version=${BuildConfig.VERSION_NAME}")
    }
}

/**
 * Run while the device's data partition is almost full (device-e2e.sh fills it with
 * fallocate and removes the filler afterwards): every write path must refuse with a
 * storage message and leave no partial file behind.
 */
@RunWith(AndroidJUnit4::class)
class VoziaLowSpaceTest {
    @Test fun lowStorageIsReportedAndLeavesNoPartialFiles() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("lowSpace") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val free = android.os.StatFs(context.filesDir.path).availableBytes
        report("low_space_free_bytes", free.toString())
        assertTrue("the script should leave less than 32 MB free, found $free", free < 32L * 1024 * 1024)
        val runtime = PhoneRuntime.get(context)
        val big = runtime.model("kokoro-multi-v1-0-int8")
        runtime.licence.record(big, true)
        try { runtime.downloads.download(big, true); fail("download must not start") }
        catch (e: com.voicestudio.mobile.network.ClientFailure) { assertEquals("mobile.local.storage_required", e.key) }
        assertFalse(runtime.downloads.installed(big))
        val library = Library.get(context)
        val record = Json.decodeFromString(E2eRecord.serializer(), e2eFile().readText())
        val item = library.item(record.item)!!
        val project = library.project(record.project)!!
        val before = library.items(project.id).size
        // Sparse: 64 MB long without using disk space the device does not have.
        val large = File(context.cacheDir, "large.wav").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(64L shl 20) } }
        try {
            library.addFile(project.id, ItemKind.IMPORT, "grande", "", large, "audio/wav", "wav")
            fail("a file larger than the free space must be refused")
        } catch (e: com.voicestudio.mobile.network.ClientFailure) { assertEquals("mobile.local.storage_required", e.key) }
        catch (e: java.io.IOException) { fail("raw IOException instead of a storage message: $e") }
        finally { large.delete() }
        assertEquals(before, library.items(project.id).size)
        assertTrue("no partial files", library.root.walkTopDown().none { it.name.endsWith(".part") })
        val exported = runCatching { Exporter(context, library).toDownloads(item, "Sem espaço") }
        report("low_space_export", exported.exceptionOrNull()?.let { (it as? com.voicestudio.mobile.network.ClientFailure)?.key ?: it.toString() } ?: "saved ${exported.getOrNull()?.location}")
        // A small WAV may still fit; when it does not, the message must be the storage one.
        exported.exceptionOrNull()?.let { assertEquals("vozia.export.no_space", (it as com.voicestudio.mobile.network.ClientFailure).key) }
    }
}
