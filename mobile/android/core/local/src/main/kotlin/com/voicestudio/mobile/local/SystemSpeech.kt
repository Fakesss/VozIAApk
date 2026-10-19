package com.voicestudio.mobile.local

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The device's own TextToSpeech engine (Google, Samsung, ...). It runs offline for
 * every voice the engine has installed and needs no model download, so Speak works
 * on first launch. Text never leaves the device unless the engine's own voice is a
 * network voice; those are excluded.
 */
class SystemSpeech(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null

    /** Binds the engine once; an engine that never answers counts as missing. */
    private suspend fun ready(): TextToSpeech = engine ?: withContext(Dispatchers.Main) {
        engine ?: withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<TextToSpeech> { continuation ->
                var created: TextToSpeech? = null
                created = TextToSpeech(app) { status ->
                    val tts = created
                    if (status == TextToSpeech.SUCCESS && tts != null) { engine = tts; if (continuation.isActive) continuation.resume(tts) }
                    else { tts?.shutdown(); if (continuation.isActive) continuation.resumeWith(Result.failure(ClientFailure("mobile.local.system_tts_unavailable"))) }
                }
                continuation.invokeOnCancellation { if (engine !== created) created?.shutdown() }
            }
        } ?: throw ClientFailure("mobile.local.system_tts_unavailable")
    }

    /**
     * Drops the bound engine. Speech engines run in their own process, which the
     * system (or an aggressive battery manager) may kill; the old binding then
     * accepts requests but never reports back, so the next call binds a new one.
     */
    private suspend fun reset() = withContext(NonCancellable + Dispatchers.Main) {
        engine?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        engine = null
    }

    /** Offline voices only, sorted so a preset index stays stable between launches. */
    private fun offlineVoices(tts: TextToSpeech): List<Voice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        .filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

    suspend fun languages(): List<String> = runCatching {
        mutex.withLock { offlineVoices(ready()).map { it.locale.toLanguageTag() }.distinct() }
    }.getOrDefault(emptyList())

    suspend fun voices(language: String?): List<String> = runCatching {
        mutex.withLock { voicesFor(ready(), language).map { it.name } }
    }.getOrDefault(emptyList())

    private fun voicesFor(tts: TextToSpeech, language: String?): List<Voice> {
        val all = offlineVoices(tts)
        if (language.isNullOrBlank()) return all
        val wanted = Locale.forLanguageTag(language)
        return all.filter { it.locale.toLanguageTag().equals(language, true) }.ifEmpty { all.filter { it.locale.language == wanted.language } }
    }

    suspend fun synthesize(text: String, language: String?, speaker: Int, speed: Float, progress: (Float) -> Unit): LocalAudio = mutex.withLock {
        require(text.isNotBlank() && speed in .5f..2f)
        val chunks = splitForSynthesis(text, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3900))
        val directory = File(app.cacheDir, "system-tts").apply { mkdirs() }
        var rate = 0
        val samples = ArrayList<FloatArray>()
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val file = File(directory, UUID.randomUUID().toString() + ".wav")
            try {
                renderChunk(chunk, file, language, speaker, speed)
                val audio = readWave(file)
                if (rate == 0) rate = audio.sampleRate
                samples += if (audio.sampleRate == rate) audio.samples else MonoResampler(audio.sampleRate, rate).accept(audio.samples)
            } finally { file.delete() }
            progress((index + 1f) / chunks.size)
        }
        val joined = FloatArray(samples.sumOf { it.size })
        var offset = 0
        samples.forEach { it.copyInto(joined, offset); offset += it.size }
        if (joined.isEmpty() || rate <= 0) throw ClientFailure("mobile.local.inference_failed")
        LocalAudio(joined, rate)
    }

    /** Selects the voice, language and rate on the bound engine. */
    private suspend fun configured(language: String?, speaker: Int, speed: Float): TextToSpeech {
        val tts = ready()
        val voices = voicesFor(tts, language)
        val voice = voices.getOrNull(speaker) ?: voices.firstOrNull()
        withContext(Dispatchers.Main) {
            if (voice != null) tts.voice = voice
            else if (language != null && tts.setLanguage(Locale.forLanguageTag(language)) < TextToSpeech.LANG_AVAILABLE) throw ClientFailure("mobile.local.system_tts_language")
            tts.setSpeechRate(speed)
        }
        return tts
    }

    /** One chunk, retried once on a freshly bound engine when the first one stalls or errors. */
    private suspend fun renderChunk(text: String, file: File, language: String?, speaker: Int, speed: Float) {
        var outcome = Outcome.ERROR
        repeat(2) {
            val tts = configured(language, speaker, speed)
            outcome = try { render(tts, text, file) } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.Main) { runCatching { tts.stop() } }; throw e }
            if (outcome == Outcome.DONE && file.length() > 44) return
            reset()
        }
        throw ClientFailure(if (outcome == Outcome.TIMEOUT) "mobile.local.system_tts_timeout" else "mobile.local.inference_failed")
    }

    private enum class Outcome { DONE, ERROR, TIMEOUT }

    private suspend fun render(tts: TextToSpeech, text: String, file: File): Outcome = withTimeoutOrNull(renderTimeout(text)) {
        suspendCancellableCoroutine<Outcome> { continuation ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Outcome.DONE) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onError(utteranceId, TextToSpeech.ERROR) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && continuation.isActive) continuation.resume(Outcome.ERROR) }
            })
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(Outcome.ERROR)
            continuation.invokeOnCancellation { runCatching { tts.stop() } }
        }
    } ?: Outcome.TIMEOUT

    fun release() { engine?.shutdown(); engine = null }

    companion object {
        private const val INIT_TIMEOUT_MS = 20_000L
        /** Engines render far faster than real time; this only catches one that stopped answering. */
        fun renderTimeout(text: String): Long = (30_000L + text.length * 150L).coerceAtMost(300_000L)
    }
}

/** Splits at paragraph, sentence, then word boundaries; no chunk exceeds [limit] characters. */
fun splitForSynthesis(text: String, limit: Int): List<String> {
    require(limit >= 50)
    val result = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() { current.toString().trim().takeIf { it.isNotEmpty() }?.let(result::add); current.setLength(0) }
    val sentences = Regex("(?<=[.!?…;:])\\s+|\\n\\s*\\n").split(text.trim())
    for (sentence in sentences) {
        var rest = sentence.trim()
        while (rest.length > limit) {
            val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
            flush(); result += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
        }
        if (current.length + rest.length + 1 > limit) flush()
        if (current.isNotEmpty()) current.append(' ')
        current.append(rest)
    }
    flush()
    return result
}

/** Reads 16-bit or float PCM WAV (mono or downmixed) written by TTS engines. */
fun readWave(file: File): LocalAudio = RandomAccessFile(file, "r").use { input ->
    fun bytes(count: Int) = ByteArray(count).also { input.readFully(it) }
    val header = ByteBuffer.wrap(bytes(12)).order(ByteOrder.LITTLE_ENDIAN)
    require(String(header.array(), 0, 4, Charsets.US_ASCII) == "RIFF" && String(header.array(), 8, 4, Charsets.US_ASCII) == "WAVE")
    var channels = 1; var rate = 0; var bits = 16; var format = 1
    while (input.filePointer + 8 <= input.length()) {
        val chunk = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN)
        val name = String(chunk.array(), 0, 4, Charsets.US_ASCII)
        val size = chunk.getInt(4).toLong() and 0xffffffffL
        if (name == "fmt ") {
            val fmt = ByteBuffer.wrap(bytes(size.toInt())).order(ByteOrder.LITTLE_ENDIAN)
            format = fmt.getShort(0).toInt(); channels = fmt.getShort(2).toInt(); rate = fmt.getInt(4); bits = fmt.getShort(14).toInt()
        } else if (name == "data") {
            // Some engines leave the streaming placeholder size (0 or 0xffffffff) in place.
            val available = input.length() - input.filePointer
            val length = (if (size == 0L || size > available) available else size).toInt()
            require(rate > 0 && channels in 1..8 && (format == 1 && bits == 16 || format == 3 && bits == 32))
            val data = ByteBuffer.wrap(bytes(length)).order(ByteOrder.LITTLE_ENDIAN)
            val frameBytes = channels * bits / 8
            val frames = length / frameBytes
            val samples = FloatArray(frames) {
                var sum = 0f
                repeat(channels) { sum += if (bits == 16) data.short / 32768f else data.float }
                sum / channels
            }
            return@use LocalAudio(samples, rate)
        } else input.seek(input.filePointer + size + (size and 1))
    }
    throw ClientFailure("mobile.local.inference_failed")
}
