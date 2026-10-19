package com.voicestudio.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.local.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
private fun rms(audio: LocalAudio) = sqrt(audio.samples.fold(0.0) { a, s -> a + s * s } / audio.samples.size)

/** Downloads [model] into the app's own model store (the one the workers use) after recording the licence. */
private suspend fun install(runtime: PhoneRuntime, model: PhoneModel) {
    if (runtime.downloads.installed(model)) return
    assertEquals("Review the model terms, then pass acceptModelLicences=true", "true", InstrumentationRegistry.getArguments().getString("acceptModelLicences"))
    runtime.licence.record(model, true)
    val started = System.currentTimeMillis()
    runtime.downloads.download(model, true)
    report("download_${model.id}", "${(System.currentTimeMillis() - started) / 1000} s")
    assertTrue(runtime.downloads.installed(model))
}

private suspend fun awaitTask(library: Library, id: String, timeoutMs: Long, until: (TaskRecord) -> Boolean = { !it.active }): TaskRecord = withTimeout(timeoutMs) {
    while (true) {
        val task = library.task(id)!!
        if (until(task)) return@withTimeout task
        delay(200)
    }
    @Suppress("UNREACHABLE_CODE") error("unreachable")
}

private suspend fun speak(library: Library, project: Project, text: String, choice: VoiceChoice, title: String): TaskRecord {
    val task = library.createTask(TaskKind.SPEECH, project.id, title, TaskJson.encodeToString(SpeechInput.serializer(), SpeechInput(text, title, choice)))
    LibraryWorker.enqueue(context, task.id)
    return task
}

/**
 * Every downloadable text-to-speech model, through the same background task the
 * Speak screen uses: download, generate in the model's best language, save, and
 * check the WAV holds real speech. One failing model does not hide the others.
 */
@RunWith(AndroidJUnit4::class)
class VoziaModelMatrixTest {
    @Test fun everyTextToSpeechModelSpeaksThroughABackgroundTask() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("modelMatrix") == "true")
        val runtime = PhoneRuntime.get(context)
        val library = Library.get(context)
        val project = library.createProject("Modelos", ProjectKind.GENERAL)
        val wanted = args.getString("models")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val models = runtime.models().filter { it.capability == "tts" && !it.system && (wanted == null || it.id in wanted) && it.fits(runtime.ramBytes) }
        assertTrue(models.isNotEmpty())
        val failures = mutableListOf<String>()
        for (model in models) {
            try {
                install(runtime, model)
                val language = model.languages.firstOrNull { it.lowercase().startsWith("pt") } ?: model.languages.firstOrNull { it.lowercase().startsWith("en") } ?: model.languages.first()
                // Typographic quotes, a dash and an emoji, as pasted text has: with the Piper download's
                // trimmed espeak data these crashed the app process before the shared espeak folder.
                val text = if (language.lowercase().startsWith("pt")) "Olá! Ele disse “bom dia” — «teste» de voz em português, feito no celular 😀."
                    else "Hello! She said “good morning” — a voice test made on the phone 😀."
                val started = System.currentTimeMillis()
                val task = awaitTask(library, speak(library, project, text, VoiceChoice(model.id, language = language), model.id).id, 10 * 60_000L)
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                assertEquals(task.error ?: "", TaskState.SUCCEEDED, task.state)
                val item = library.item(task.resultItem!!)!!
                val audio = readWave(library.file(item)!!)
                val duration = audio.samples.size / audio.sampleRate.toDouble()
                report("tts_${model.id}", "language=$language rate=${audio.sampleRate} audio=%.2fs took=%.1fs rms=%.3f".format(duration, seconds, rms(audio)))
                assertTrue("${model.id}: at least a second of speech", duration > 1.0)
                assertTrue("${model.id}: not silence", rms(audio) > 0.005)
            } catch (e: Throwable) {
                failures += "${model.id}: $e"
                report("tts_${model.id}_failed", e.toString())
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

/**
 * The phone bug these guard against: a slow cloned-voice (OmniVoice) task used to
 * hold the only inference slot, so every other speech task sat at 0%, and a
 * cancelled clone kept running in native code. Now a small model runs alongside
 * it, OmniVoice reports progress per step, and a cancel frees its slot at once.
 */
@RunWith(AndroidJUnit4::class)
class VoziaHeavyTaskTest {
    @Test fun cloneTaskReportsProgressDoesNotBlockSpeechAndCancelsPromptly() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("heavyTasks") == "true")
        val runtime = PhoneRuntime.get(context)
        val library = Library.get(context)
        val piper = runtime.model("piper-pt-br-faber")
        val whisper = runtime.model("whisper-tiny-int8")
        val omni = runtime.models().first { it.capability == "clone" }
        assumeTrue("needs ${omni.minRamGb} GB of RAM", omni.fits(runtime.ramBytes))
        for (model in listOf(piper, whisper, omni)) install(runtime, model)
        report("cpu_variant", nativeCpuVariant())
        val flags = File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("flags") || it.startsWith("Features") }.orEmpty().substringAfter(':').split(' ').toSet()
        report("cpu_flags", listOf("avx", "avx2", "fma", "f16c", "bmi2", "asimddp", "asimdhp", "i8mm", "bf16").filter { it in flags }.joinToString(" "))

        // A phone voice made from a Piper recording, as the Voices screen does with the microphone.
        val reference = File(context.cacheDir, "reference.wav")
        markSynthetic(runtime.engine.synthesize(piper, "Bom dia. Esta é a minha voz de referência para o teste de clonagem.", language = "pt-BR"), reference)
        val voices = PhoneVoiceStore(File(context.noBackupFilesDir, "voices"))
        val voice = voices.create("Voz de teste", reference, true, whisper, runtime.engine)
        reference.delete()
        val project = library.createProject("Clonagem", ProjectKind.GENERAL)
        val cloned = VoiceChoice(omni.id, voice = voice.id, speakerName = voice.name)

        // 1. A cloned-voice task shows progress while OmniVoice runs.
        val long = "Este texto é lido com a voz clonada. Ele é longo o bastante para manter o modelo ocupado por um bom tempo, " +
            "enquanto outra tarefa com um modelo pequeno precisa terminar normalmente, sem esperar na fila."
        var started = System.currentTimeMillis()
        val clone = speak(library, project, long, cloned, "Clone longo")
        // Past 0.23 means OmniVoice finished loading and reported decoding steps.
        val moving = awaitTask(library, clone.id, 20 * 60_000L) { it.state == TaskState.RUNNING && it.progress > 0.23f || !it.active }
        assertTrue("clone task still running: ${moving.state} ${moving.error}", moving.active)
        report("clone_first_progress", "${(System.currentTimeMillis() - started) / 1000} s, progress=${moving.progress}, stage=${moving.stage}")

        // 2. A Piper task finishes while the clone is still working.
        started = System.currentTimeMillis()
        val quick = awaitTask(library, speak(library, project, "Esta fala não pode esperar a clonagem.", VoiceChoice(piper.id, language = "pt-BR"), "Rápida").id, 5 * 60_000L)
        assertEquals(quick.error ?: "", TaskState.SUCCEEDED, quick.state)
        report("piper_during_clone", "${(System.currentTimeMillis() - started) / 1000.0} s")
        assertTrue("the clone must still be running, so the Piper task really ran alongside it", library.task(clone.id)!!.active)

        // 3. Cancelling the clone frees OmniVoice within seconds: a new clone task starts moving right away.
        LibraryWorker.cancel(context, library, clone.id)
        started = System.currentTimeMillis()
        val short = speak(library, project, "Olá, tudo bem?", cloned, "Clone curto")
        val next = awaitTask(library, short.id, 10 * 60_000L) { it.state == TaskState.RUNNING && it.progress > 0.25f || !it.active }
        val freed = (System.currentTimeMillis() - started) / 1000
        report("clone_after_cancel", "$freed s until the next clone task reached ${next.progress}")
        assertEquals(TaskState.CANCELLED, library.task(clone.id)!!.state)

        // 4. The short clone completes with real audio at OmniVoice's 24 kHz.
        started = System.currentTimeMillis()
        val done = awaitTask(library, short.id, 30 * 60_000L)
        assertEquals(done.error ?: "", TaskState.SUCCEEDED, done.state)
        val audio = readWave(library.file(library.item(done.resultItem!!)!!)!!)
        report("clone_short", "audio=%.2fs rms=%.3f finished %d s after cancel".format(audio.samples.size / audio.sampleRate.toDouble(), rms(audio), freed + (System.currentTimeMillis() - started) / 1000))
        assertEquals(24000, audio.sampleRate)
        assertTrue(rms(audio) > 0.005)
        voices.delete(voice)
    }
}
