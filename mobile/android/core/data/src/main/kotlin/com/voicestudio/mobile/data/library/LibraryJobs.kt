package com.voicestudio.mobile.data.library

import com.voicestudio.mobile.local.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object TaskKind { const val SPEECH = "speech"; const val AUDIOBOOK = "audiobook"; const val TRANSCRIBE = "transcribe" }

/** How a text becomes speech: model, preset speaker or phone voice, language and speed. */
@Serializable data class VoiceChoice(val model: String, val speaker: Int = 0, val language: String? = null, val speed: Float = 1f,
    val voice: String? = null, val speakerName: String? = null)
@Serializable data class SpeechInput(val text: String, val title: String, val choice: VoiceChoice)
@Serializable data class AudiobookInput(val choice: VoiceChoice, val merge: Boolean = true, val gapSeconds: Double = 1.5)
@Serializable data class TranscribeInput(val source: String, val model: String, val language: String? = null)
@Serializable data class ChapterMark(val title: String, val startMs: Long)
@Serializable data class AudiobookMeta(val chapters: List<ChapterMark>)
@Serializable data class TranscriptMeta(val segments: List<SpeechSegment>)
data class JobResult(val itemId: String?)

val TaskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Speech/voice engine seen by jobs; tests replace it without native libraries. */
interface JobEngine {
    fun model(id: String): PhoneModel
    suspend fun speak(choice: VoiceChoice, text: String, progress: (Float) -> Unit): LocalAudio
    suspend fun transcribe(model: PhoneModel, audio: LocalAudio, language: String?, progress: (Float) -> Unit): List<SpeechSegment>
    suspend fun decode(source: File, output: File, progress: (Float) -> Unit): PcmFile
}

class RuntimeJobEngine(private val runtime: PhoneRuntime, private val voices: PhoneVoiceStore) : JobEngine {
    override fun model(id: String) = runtime.model(id)
    override suspend fun speak(choice: VoiceChoice, text: String, progress: (Float) -> Unit): LocalAudio {
        val model = model(choice.model)
        val voice = choice.voice?.let { id -> voices.list().firstOrNull { it.id == id } ?: throw ClientFailure("vozia.task.voice_missing") }
        return if (voice != null) runtime.engine.clone(model, text, voices.reference(voice), voice.transcript, progress)
        else runtime.engine.synthesize(model, text, choice.speaker, choice.speed, choice.language, progress)
    }
    override suspend fun transcribe(model: PhoneModel, audio: LocalAudio, language: String?, progress: (Float) -> Unit) =
        runtime.engine.transcribeSegments(model, audio, language, progress)
    override suspend fun decode(source: File, output: File, progress: (Float) -> Unit) = AndroidAudioDecoder().decode(source, output, 14400, progress)
}

/**
 * Long operations split into checkpoints inside the task's scratch folder: each
 * synthesized chunk and each transcribed window is written atomically, so a
 * process restart (or a WorkManager reschedule) continues where it stopped
 * instead of starting over. Finished media moves into the library and the scratch
 * folder is deleted.
 */
class LibraryJobs(private val library: Library, private val engine: JobEngine) {
    suspend fun run(task: TaskRecord, report: suspend (Float, String) -> Unit): JobResult = when (task.kind) {
        TaskKind.SPEECH -> speech(task, report)
        TaskKind.AUDIOBOOK -> audiobook(task, report)
        TaskKind.TRANSCRIBE -> transcribe(task, report)
        else -> throw ClientFailure("vozia.task.unknown")
    }

    private suspend fun speech(task: TaskRecord, report: suspend (Float, String) -> Unit): JobResult {
        val input = TaskJson.decodeFromString<SpeechInput>(task.input)
        val project = task.projectId ?: throw ClientFailure("vozia.task.project_missing")
        val scratch = library.taskDirectory(task.id)
        val wav = synthesizeText(input.text, input.choice, File(scratch, "speech")) { p -> report(p * .97f, "vozia.task.speaking") }
        report(.98f, "vozia.task.saving")
        val item = library.addFile(project, ItemKind.SPEECH, input.title, input.text, wav.file, "audio/wav", "wav",
            engine = input.choice.model, voice = input.choice.speakerName, language = input.choice.language, durationMs = wav.durationMs)
        scratch.deleteRecursively()
        return JobResult(item.id)
    }

    private suspend fun audiobook(task: TaskRecord, report: suspend (Float, String) -> Unit): JobResult {
        val input = TaskJson.decodeFromString<AudiobookInput>(task.input)
        val project = task.projectId ?: throw ClientFailure("vozia.task.project_missing")
        val chapters = library.chapters(project)
        if (chapters.isEmpty()) throw ClientFailure("vozia.audiobook.no_chapters")
        val total = chapters.sumOf { it.text.length }.coerceAtLeast(1).toFloat()
        var done = chapters.filter { it.itemId != null }.sumOf { it.text.length }.toFloat()
        val scratch = library.taskDirectory(task.id)
        for (chapter in chapters) {
            currentCoroutineContext().ensureActive()
            if (chapter.itemId != null && library.item(chapter.itemId) != null) continue
            library.updateChapter(chapter.id, state = "running")
            val base = done
            val wav = synthesizeText(chapter.text, input.choice, File(scratch, chapter.id)) { p ->
                report(((base + p * chapter.text.length) / total) * (if (input.merge) .9f else .98f), "vozia.task.chapter")
            }
            val item = library.addFile(project, ItemKind.CHAPTER, chapter.title, chapter.text.take(500), wav.file, "audio/wav", "wav",
                engine = input.choice.model, voice = input.choice.speakerName, language = input.choice.language, position = chapter.position, durationMs = wav.durationMs)
            library.updateChapter(chapter.id, itemId = item.id, state = "done")
            File(scratch, chapter.id).deleteRecursively()
            done += chapter.text.length
        }
        var result: String? = null
        if (input.merge) {
            report(.92f, "vozia.task.merging")
            result = merge(project, library.chapters(project), input, scratch)
        }
        scratch.deleteRecursively()
        return JobResult(result)
    }

    /** Concatenates chapter WAVs with a pause between them and records chapter start times. */
    private suspend fun merge(project: String, chapters: List<Chapter>, input: AudiobookInput, scratch: File): String {
        val items = chapters.map { chapter -> library.item(chapter.itemId ?: throw ClientFailure("vozia.audiobook.no_chapters")) ?: throw ClientFailure("vozia.export.missing") }
        val files = items.map { library.file(it) ?: throw ClientFailure("vozia.export.missing") }
        val rate = files.map { waveFormat(it).first }.distinct().singleOrNull() ?: throw ClientFailure("vozia.audiobook.mixed_rates")
        val output = File(scratch.apply { mkdirs() }, "book.wav")
        val marks = mutableListOf<ChapterMark>()
        var frames = 0L
        WaveWriter(output, rate).use { writer ->
            files.forEachIndexed { index, file ->
                if (index > 0) writer.silence(input.gapSeconds)
                marks += ChapterMark(chapters[index].title, writer.frames * 1000 / rate)
                appendWave(file, writer)
            }
            frames = writer.frames
        }
        library.items(project, listOf(ItemKind.AUDIOBOOK)).forEach { library.deleteItem(it.id) }
        val name = library.project(project)?.name ?: "Audiobook"
        val durationMs = frames * 1000 / rate
        val item = library.addFile(project, ItemKind.AUDIOBOOK, name, "", output, "audio/wav", "wav", engine = input.choice.model,
            voice = input.choice.speakerName, language = input.choice.language, durationMs = durationMs,
            meta = TaskJson.encodeToString(AudiobookMeta.serializer(), AudiobookMeta(marks)))
        output.delete()
        return item.id
    }

    private suspend fun transcribe(task: TaskRecord, report: suspend (Float, String) -> Unit): JobResult {
        val input = TaskJson.decodeFromString<TranscribeInput>(task.input)
        val project = task.projectId ?: throw ClientFailure("vozia.task.project_missing")
        val source = library.item(input.source) ?: throw ClientFailure("vozia.export.missing")
        val model = engine.model(input.model)
        val scratch = library.taskDirectory(task.id).apply { mkdirs() }
        val pcmFile = File(scratch, "audio.pcm")
        val decodedMarker = File(scratch, "audio.done")
        if (!decodedMarker.exists()) {
            report(0f, "vozia.task.decoding")
            engine.decode(library.file(source) ?: throw ClientFailure("vozia.export.missing"), pcmFile) {}
            decodedMarker.writeText("ok")
        }
        val pcm = PcmFile(pcmFile)
        val window = pcm.sampleRate * 300L
        val segments = mutableListOf<SpeechSegment>()
        var start = 0L; var index = 0
        while (start < pcm.frames) {
            currentCoroutineContext().ensureActive()
            val checkpoint = File(scratch, "window-%05d.json".format(index))
            val count = minOf(window, pcm.frames - start).toInt()
            if (checkpoint.exists()) segments += TaskJson.decodeFromString<TranscriptMeta>(checkpoint.readText()).segments
            else {
                val offset = start.toDouble() / pcm.sampleRate
                // Progress is reported between five-minute windows, after each checkpoint.
                val part = engine.transcribe(model, pcm.read(start, count), input.language) {}
                    .map { it.copy(start = it.start + offset, end = it.end + offset) }
                atomicWrite(checkpoint, TaskJson.encodeToString(TranscriptMeta.serializer(), TranscriptMeta(part)))
                segments += part
            }
            start += count; index++
            report((start.toFloat() / pcm.frames) * .95f, "vozia.task.transcribing")
        }
        val text = segments.joinToString("\n") { it.text.trim() }.trim()
        if (text.isEmpty()) throw ClientFailure("mobile.simple.no_speech")
        report(.97f, "vozia.task.saving")
        val title = source.title.substringBeforeLast('.').ifEmpty { source.title }
        val meta = TaskJson.encodeToString(TranscriptMeta.serializer(), TranscriptMeta(segments))
        val transcript = library.addText(project, ItemKind.TRANSCRIPT, title, text, text + "\n", "text/plain", "txt", input.language, source.id, model.id, meta)
        library.addText(project, ItemKind.SUBTITLE, "$title (SRT)", "", Subtitles.srt(segments), "application/x-subrip", "srt", input.language, source.id, model.id)
        library.addText(project, ItemKind.SUBTITLE, "$title (VTT)", "", Subtitles.vtt(segments), "text/vtt", "vtt", input.language, source.id, model.id)
        scratch.deleteRecursively()
        return JobResult(transcript.id)
    }

    data class Rendered(val file: File, val durationMs: Long)

    /** Synthesizes [text] in sentence-aligned chunks, reusing chunk checkpoints already on disk. */
    private suspend fun synthesizeText(text: String, choice: VoiceChoice, directory: File, progress: suspend (Float) -> Unit): Rendered {
        val chunks = splitForSynthesis(text, CHUNK)
        if (chunks.isEmpty()) throw ClientFailure("vozia.task.empty_text")
        directory.mkdirs()
        val identity = File(directory, "choice.json")
        val choiceJson = TaskJson.encodeToString(VoiceChoice.serializer(), choice) + chunks.size
        if (identity.exists() && identity.readText() != choiceJson) directory.listFiles().orEmpty().forEach { it.delete() }
        if (!identity.exists()) atomicWrite(identity, choiceJson)
        var rate = 0
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val file = File(directory, "chunk-%05d.pcm".format(index))
            val rateFile = File(directory, "chunk-%05d.rate".format(index))
            if (!file.exists() || !rateFile.exists()) {
                val audio = engine.speak(choice, chunk) {}
                if (audio.samples.isEmpty()) throw ClientFailure("mobile.local.inference_failed")
                writePcmAtomic(audio, file)
                atomicWrite(rateFile, audio.sampleRate.toString())
            }
            val chunkRate = rateFile.readText().toInt()
            if (rate == 0) rate = chunkRate else if (rate != chunkRate) throw ClientFailure("vozia.audiobook.mixed_rates")
            progress((index + 1f) / chunks.size)
        }
        val output = File(directory, "out.wav")
        var frames = 0L
        WaveWriter(output, rate).use { writer ->
            chunks.indices.forEach { index ->
                if (index > 0) writer.silence(GAP)
                val pcm = PcmFile(File(directory, "chunk-%05d.pcm".format(index)), rate)
                var offset = 0L
                while (offset < pcm.frames) {
                    val count = minOf(1L shl 16, pcm.frames - offset).toInt()
                    writer.write(pcm.read(offset, count).samples); offset += count
                }
            }
            frames = writer.frames
        }
        return Rendered(output, frames * 1000 / rate)
    }

    companion object {
        const val CHUNK = 900
        const val GAP = .12
        internal fun atomicWrite(file: File, text: String) {
            val pending = File(file.path + ".pending")
            FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        internal fun writePcmAtomic(audio: LocalAudio, file: File) {
            val pending = File(file.path + ".pending")
            writePcm(audio, pending)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        /** (sample rate, data offset, data length) of a 16-bit mono WAV written by [WaveWriter]. */
        internal fun waveFormat(file: File): Triple<Int, Long, Long> = RandomAccessFile(file, "r").use { input ->
            val head = ByteArray(12); input.readFully(head)
            require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE")
            var rate = 0; var channels = 0; var bits = 0
            while (input.filePointer + 8 <= input.length()) {
                val chunk = ByteArray(8); input.readFully(chunk)
                val name = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                if (name == "fmt ") {
                    val fmt = ByteArray(size.toInt()); input.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = b.getShort(2).toInt(); rate = b.getInt(4); bits = b.getShort(14).toInt()
                } else if (name == "data") {
                    if (channels != 1 || bits != 16) throw ClientFailure("vozia.audiobook.mixed_rates")
                    return@use Triple(rate, input.filePointer, size)
                } else input.seek(input.filePointer + size + (size and 1))
            }
            throw ClientFailure("vozia.export.missing")
        }
        internal suspend fun appendWave(file: File, writer: WaveWriter) {
            val (_, offset, length) = waveFormat(file)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val bytes = ByteArray(1 shl 16)
                var remaining = length
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt()); if (n <= 0) break
                    val shorts = ByteBuffer.wrap(bytes, 0, n - n % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val floats = FloatArray(shorts.remaining()) { shorts.get() / 32768f }
                    writer.write(floats); remaining -= n
                }
            }
        }
    }
}

/** SubRip and WebVTT from timed Whisper segments. */
object Subtitles {
    private fun stamp(seconds: Double, separator: Char): String {
        val total = (seconds.coerceAtLeast(0.0) * 1000).toLong()
        return "%02d:%02d:%02d%c%03d".format(java.util.Locale.ROOT, total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, separator, total % 1000)
    }
    fun srt(segments: List<SpeechSegment>): String = segments.filter { it.text.isNotBlank() }.mapIndexed { index, s ->
        "${index + 1}\n${stamp(s.start, ',')} --> ${stamp(maxOf(s.end, s.start + .2), ',')}\n${s.text.trim()}\n"
    }.joinToString("\n")
    fun vtt(segments: List<SpeechSegment>): String = "WEBVTT\n\n" + segments.filter { it.text.isNotBlank() }.joinToString("\n") { s ->
        "${stamp(s.start, '.')} --> ${stamp(maxOf(s.end, s.start + .2), '.')}\n${s.text.trim()}\n"
    }
}
