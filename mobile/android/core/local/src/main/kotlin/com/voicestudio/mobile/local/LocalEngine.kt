package com.voicestudio.mobile.local

import com.k2fsa.sherpa.onnx.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM is mono float [-1,1]; reference cloning audio is explicitly 24 kHz. */
data class LocalAudio(val samples: FloatArray, val sampleRate: Int)
@kotlinx.serialization.Serializable data class SpeechSegment(val start: Double,val end: Double,val text: String)
data class Transcript(val text: String, val final: Boolean, val progress: Float)
interface LocalEngine {
    suspend fun synthesize(model: PhoneModel, text: String, speaker: Int = 0, speed: Float = 1f, language: String? = null, progress: (Float)->Unit = {}): LocalAudio
    suspend fun clone(model: PhoneModel, text: String, reference: LocalAudio, referenceText: String, progress: (Float)->Unit = {}): LocalAudio
    suspend fun transcribe(model: PhoneModel, audio: LocalAudio, progress: (Float)->Unit = {}): String
    suspend fun transcribeSegments(model: PhoneModel, audio: LocalAudio, progress: (Float)->Unit = {}): List<SpeechSegment> {
        val text=transcribe(model,audio,progress).trim()
        return if(text.isEmpty()) emptyList() else listOf(SpeechSegment(0.0,audio.samples.size.toDouble()/audio.sampleRate,text))
    }
    /** [language] forces a Whisper language code ("pt", "en", ...); null keeps the catalogue default (auto). */
    suspend fun transcribeSegments(model: PhoneModel, audio: LocalAudio, language: String?, progress: (Float)->Unit = {}): List<SpeechSegment> =
        transcribeSegments(model,audio,progress)
    fun streamTranscribe(model: PhoneModel, audio: Flow<LocalAudio>): Flow<Transcript>
    suspend fun translate(model: PhoneModel, text: String, targetLanguage: String, progress: (Float)->Unit = {}): String
}
/** JNI callbacks never surface native diagnostic text or user audio to logs. */
@androidx.annotation.Keep
class NativeControl(private val job: Job, private val report: (Float)->Unit, private val allowed: ()->Boolean) {
    fun cancelled(): Boolean = !job.isActive || !allowed()
    fun progress(value: Float) {if(!cancelled()) report(value)}
}
@androidx.annotation.Keep
internal object NativeBridge {
    /** The CPU variant loaded ("" is the baseline build), null before [load]. */
    @Volatile var variant: String?=null
        private set
    /**
     * Loads the fastest build of each runtime this CPU supports (see build.sh). A
     * variant missing from the APK for this ABI is skipped; one built for features
     * the CPU lacks is never tried, since it would crash with an illegal instruction.
     */
    fun load() {synchronized(this) {
        if(variant!=null) return
        val candidates=cpuVariants(cpuFeatures())
        val loaded=listOf("vs_omni","vs_llama").map {runtime ->
            candidates.dropLast(1).firstOrNull {suffix -> runCatching {System.loadLibrary(runtime+suffix)}.isSuccess}
                ?: "".also {System.loadLibrary(runtime)}
        }
        variant=loaded.first()
    }}
    external fun omniAbi(): Int
    external fun llamaDevices(): Int
    external fun cloneAudio(model: String, codec: String, text: String, referenceText: String, reference: FloatArray, control: NativeControl): FloatArray?
    external fun translateText(model: String, prompt: String, formatted: Boolean, control: NativeControl): ByteArray?
}

/**
 * Lets a caller show that its job waits for another job's model: [waiting] gets
 * true while the lane is busy and false once this job holds it.
 */
class EngineWait(val waiting: suspend (Boolean)->Unit): kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key: kotlin.coroutines.CoroutineContext.Key<EngineWait>
}

/**
 * [catalogue] lists every known model, so the shared espeak-ng folder covers all installed
 * voices; [seedEspeak] adds bundled espeak files to it.
 */
class PhoneLocalEngine(private val models: ModelDownloads, private val system: SystemSpeech? = null,
    private val catalogue: ()->List<PhoneModel> = {emptyList()}, private val seedEspeak: (File)->Unit = {}): LocalEngine {
    companion object {
        // One large GGUF model (OmniVoice, llama.cpp) and one small ONNX model run at a
        // time: a slow clone or dub no longer holds up ordinary speech or transcription,
        // and memory stays bounded to one model of each kind.
        private val heavyLease=Mutex()
        private val lightLease=Mutex()
        internal fun lease(model: PhoneModel): Mutex = if(model.capability in setOf("clone","translate")) heavyLease else lightLease
    }
    internal suspend fun <T> execute(model: PhoneModel, capability: String, progress: (Float)->Unit, block: suspend (File,Job)->T): T =
        withContext(Dispatchers.Default) {
            if(model.capability != capability) throw ClientFailure("mobile.local.model_incompatible")
            try {
                // Fail before queueing for a lane: a missing model or withdrawn licence must not wait behind other jobs.
                models.licence.requireAccepted(model)
                if(!models.installed(model)) throw ClientFailure("mobile.local.model_required")
                val lease=lease(model)
                val wait=currentCoroutineContext()[EngineWait]
                if(!lease.tryLock()) {
                    runCatching {wait?.waiting?.invoke(true)}
                    lease.lock()
                }
                try {
                    runCatching {wait?.waiting?.invoke(false)}
                    models.withModel(model) { directory ->
                        currentCoroutineContext().ensureActive();progress(0f)
                        val result=block(directory,currentCoroutineContext().job)
                        currentCoroutineContext().ensureActive();models.licence.requireAccepted(model);progress(1f);result
                    }
                } finally {lease.unlock()}
            } catch(e:CancellationException) {throw e}
            catch(e:ClientFailure) {throw e}
            catch(e:LinkageError) {throw ClientFailure("mobile.local.runtime_failed")}
            catch(e:Exception) {throw ClientFailure("mobile.local.inference_failed")}
        }
    override suspend fun synthesize(model: PhoneModel, text: String, speaker: Int, speed: Float, language: String?, progress: (Float)->Unit): LocalAudio {
        if(model.system) {
            if(model.capability!="tts") throw ClientFailure("mobile.local.model_incompatible")
            val engine=system ?: throw ClientFailure("mobile.local.system_tts_unavailable")
            return try {engine.synthesize(text,language,speaker,speed,progress)}
                catch(e:CancellationException) {throw e} catch(e:ClientFailure) {throw e} catch(e:Exception) {throw ClientFailure("mobile.local.inference_failed")}
        }
        return synthesizeNative(model,text,speaker,speed,language,progress)
    }
    private suspend fun synthesizeNative(model: PhoneModel, raw: String, speaker: Int, speed: Float, language: String?, progress: (Float)->Unit): LocalAudio = execute(model,"tts",progress) {dir,job ->
        require(raw.isNotBlank() && raw.length<=20000 && speed in .5f..2f && (language==null||language in model.languages))
        val text=speakable(raw).ifBlank {throw ClientFailure("vozia.task.empty_text")}
        // espeak-ng keeps the first data folder it is given for the whole process: always pass the shared one.
        val dataDir=if(dataDirectory(model,dir).isEmpty()) "" else models.sharedEspeak(catalogue()+model,seedEspeak).path
        val config=OfflineTtsModelConfig(numThreads=inferenceThreads(model),provider="cpu")
        val type=model.option("model_type",model.option("type",if(model.id.contains("kitten")) "kitten" else "kokoro"))
        when(type) {
            "vits" -> config.vits=OfflineTtsVitsModelConfig(model=model.role(dir,"model"),tokens=model.role(dir,"tokens"),dataDir=dataDir,
                lexicon=model.files.filter {it.role=="lexicon"}.joinToString(",") {File(dir,it.path).absolutePath},lengthScale=model.option("length_scale","1.0").toFloatOrNull() ?: 1f)
            "supertonic" -> config.supertonic=OfflineTtsSupertonicModelConfig(durationPredictor=model.role(dir,"duration-predictor"),textEncoder=model.role(dir,"text-encoder"),
                vectorEstimator=model.role(dir,"vector-estimator"),vocoder=model.role(dir,"vocoder"),ttsJson=model.role(dir,"tts-json"),
                unicodeIndexer=model.role(dir,"unicode-indexer"),voiceStyle=model.role(dir,"voices"))
            "kitten" -> config.kitten=OfflineTtsKittenModelConfig(model=model.role(dir,"model"),voices=model.role(dir,"voices"),tokens=model.role(dir,"tokens"),dataDir=dataDir)
            "kokoro" -> config.kokoro=OfflineTtsKokoroModelConfig(model=model.role(dir,"model"),voices=model.role(dir,"voices"),tokens=model.role(dir,"tokens"),dataDir=dataDir,
                lexicon=model.files.filter {it.role=="lexicon"}.joinToString(",") {File(dir,it.path).absolutePath},lang=espeakVoice(language ?: model.option("lang")),dictDir=model.role(dir,"dict"))
            else -> throw ClientFailure("mobile.local.model_incompatible")
        }
        // Kokoro's rule FSTs rewrite numbers and dates as Chinese: apply them to Chinese text only.
        val chinese=type!="kokoro"||(language ?: model.option("lang")).lowercase().let {it.startsWith("zh")||it.startsWith("cmn")}
        val tts=OfflineTts(config=OfflineTtsConfig(model=config,maxNumSentences=1,ruleFsts=if(!chinese) "" else model.files.filter {it.role=="rule-fst"}.joinToString(",") {File(dir,it.path).absolutePath}))
        try {
            if(speaker !in 0 until tts.numSpeakers()) throw ClientFailure("mobile.local.model_incompatible")
            var chunks=0
            val callback=NativeTtsCallback {
                if(!job.isActive||!models.licence.accepted(model)) 0 else {chunks++;progress((chunks.toFloat()/(chunks+1)).coerceAtMost(.95f));1}
            }
            val audio=if(type=="supertonic") tts.generateWithConfigAndCallback(text,GenerationConfig(sid=speaker,speed=speed,
                    numSteps=model.option("num_steps","8").toIntOrNull()?.coerceIn(1,32) ?: 8,extra=mapOf("lang" to (language ?: model.languages.firstOrNull {it=="en"} ?: model.languages.first()))),callback)
                else tts.generateWithCallback(text,speaker,speed,callback)
            job.ensureActive();LocalAudio(audio.samples,audio.sampleRate)
        } finally {tts.release()}
    }
    override suspend fun transcribe(model: PhoneModel, audio: LocalAudio, progress: (Float)->Unit): String =
        transcribeSegments(model,audio,progress).joinToString(" ") {it.text}
    override suspend fun transcribeSegments(model: PhoneModel, audio: LocalAudio, progress: (Float)->Unit): List<SpeechSegment> =
        transcribeSegments(model,audio,null,progress)
    override suspend fun transcribeSegments(model: PhoneModel, audio: LocalAudio, language: String?, progress: (Float)->Unit): List<SpeechSegment> = execute(model,"asr",progress) {dir,job ->
        require(audio.sampleRate>0 && audio.samples.isNotEmpty() && (language==null||language.matches(Regex("[a-z]{2,3}"))))
        val recognizer=OfflineRecognizer(config=OfflineRecognizerConfig(
            featConfig=FeatureConfig(sampleRate=16000,featureDim=80),
            modelConfig=OfflineModelConfig(whisper=OfflineWhisperModelConfig(encoder=model.role(dir,"encoder"),decoder=model.role(dir,"decoder"),language=language ?: model.option("language"),task="transcribe",enableTokenTimestamps=true),
                tokens=model.role(dir,"tokens"),numThreads=2,provider="cpu")))
        try {
            val segments=mutableListOf<SpeechSegment>()
            // Phrase-sized windows cut at pauses. The catalogue Whisper models have no token
            // timestamps, so each window's speech bounds come from its loudness instead;
            // silent windows are skipped (Whisper invents text for silence).
            for((start,limit) in speechWindows(audio.samples,audio.sampleRate,SEGMENT_SECONDS,3)) {
                job.ensureActive();models.licence.requireAccepted(model)
                val voiced=voicedSpan(audio.samples,start,limit,audio.sampleRate)
                if(voiced==null) {progress(limit.toFloat()/audio.samples.size);continue}
                val stream=recognizer.createStream()
                try {
                    stream.acceptWaveform(audio.samples.copyOfRange(start,limit),audio.sampleRate);recognizer.decode(stream)
                    val result=recognizer.getResult(stream)
                    val text=result.text.trim()
                    if(text.isNotEmpty()) {
                        val duration=(limit-start).toDouble()/audio.sampleRate
                        val valid=result.timestamps.filter {it.isFinite()&&it>=0&&it<duration}
                        val loudFirst=(voiced.first-start).toDouble()/audio.sampleRate
                        val loudEnd=(voiced.second-start).toDouble()/audio.sampleRate
                        val first=(valid.minOrNull()?.toDouble() ?: loudFirst).coerceIn(0.0,(duration-.02).coerceAtLeast(0.0))
                        val last=valid.maxOrNull()?.toDouble()
                        val tail=result.durations.lastOrNull()?.takeIf {it.isFinite()&&it>0}?.toDouble() ?: .5
                        val end=(if(last==null) loudEnd else last+tail).coerceIn(minOf(first+.01,duration),duration)
                        val offset=start.toDouble()/audio.sampleRate
                        segments+=SpeechSegment(offset+first,offset+end,text)
                    }
                } finally {stream.release()}
                progress(limit.toFloat()/audio.samples.size)
            }
            segments
        } finally {recognizer.release()}
    }
    override fun streamTranscribe(model: PhoneModel, audio: Flow<LocalAudio>): Flow<Transcript> = channelFlow {
        execute(model,"asr-stream",{}) {dir,job ->
            val recognizer=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=16000,featureDim=80),
                modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=model.role(dir,"encoder"),decoder=model.role(dir,"decoder"),joiner=model.role(dir,"joiner")),
                    tokens=model.role(dir,"tokens"),numThreads=2,provider="cpu"),enableEndpoint=true))
            val stream=recognizer.createStream()
            var committed=""
            // sherpa ends the whole process if a stream's sample rate changes, so the
            // first chunk fixes the rate and anything else is rejected here instead.
            var rate=0
            try {
                audio.collect { chunk ->
                    job.ensureActive();models.licence.requireAccepted(model)
                    if(rate==0) rate=chunk.sampleRate
                    if(chunk.sampleRate!=rate || rate<=0) throw ClientFailure("mobile.local.model_incompatible")
                    stream.acceptWaveform(chunk.samples,chunk.sampleRate)
                    while(recognizer.isReady(stream)) {job.ensureActive();recognizer.decode(stream)}
                    val text=recognizer.getResult(stream).text
                    val endpoint=recognizer.isEndpoint(stream)
                    send(Transcript(listOf(committed,text).filter {it.isNotBlank()}.joinToString(" "),endpoint,0f))
                    if(endpoint) {committed=listOf(committed,text).filter {it.isNotBlank()}.joinToString(" ");recognizer.reset(stream)}
                }
                if(rate>0) stream.acceptWaveform(FloatArray(rate/2),rate)
                stream.inputFinished()
                while(recognizer.isReady(stream)) {job.ensureActive();recognizer.decode(stream)}
                send(Transcript(listOf(committed,recognizer.getResult(stream).text).filter {it.isNotBlank()}.joinToString(" "),true,1f))
            } finally {stream.release();recognizer.release()}
        }
    }.flowOn(Dispatchers.Default)
    override suspend fun clone(model: PhoneModel, text: String, reference: LocalAudio, referenceText: String, progress: (Float)->Unit): LocalAudio = execute(model,"clone",progress) {dir,job ->
        require(text.isNotBlank() && text.length<=20000 && reference.sampleRate==24000 && reference.samples.size in 1..720000 && reference.samples.all {it.isFinite()} && referenceText.isNotBlank())
        NativeBridge.load()
        val codec=model.role(dir,"codec").ifEmpty {model.role(dir,"audio-tokenizer").ifEmpty {model.role(dir,"tokenizer")}}
        if(codec.isBlank()) throw ClientFailure("mobile.local.model_incompatible")
        val result=NativeBridge.cloneAudio(model.role(dir,"model"),codec,text,referenceText,reference.samples,NativeControl(job,progress) {models.licence.accepted(model)})
        job.ensureActive();models.licence.requireAccepted(model)
        LocalAudio(result ?: throw ClientFailure("mobile.local.inference_failed"),24000)
    }
    override suspend fun translate(model: PhoneModel, text: String, targetLanguage: String, progress: (Float)->Unit): String = execute(model,"translate",progress) {dir,job ->
        require(text.isNotBlank() && targetLanguage.length in 2..120 && targetLanguage.none {it.isISOControl()})
        NativeBridge.load()
        val template=model.option("prompt_template","Translate the following text into {{target_language}}. Return only the translation.\n{{text}}")
        val prompt=template.replace("\\n","\n").replace("{{target_language}}",targetLanguage).replace("{target_language}",targetLanguage).replace("{source_language}","auto").replace("{{text}}",text).replace("{text}",text)
        val result=NativeBridge.translateText(model.role(dir,"model"),prompt,model.option("prompt_template").contains("<|im_start|>"),NativeControl(job,progress) {models.licence.accepted(model)})
        job.ensureActive();models.licence.requireAccepted(model)
        result?.toString(Charsets.UTF_8) ?: throw ClientFailure("mobile.local.inference_failed")
    }
    /**
     * Two threads keep phones cool; big cores get up to four, never more than half.
     * While a large model runs in the other lane, two threads leave it the fast cores.
     */
    private fun inferenceThreads(model: PhoneModel): Int {
        val wanted=model.option("num_threads","2").toIntOrNull()?.coerceIn(1,4)?.coerceAtLeast((Runtime.getRuntime().availableProcessors()/2).coerceIn(1,4)) ?: 2
        return if(heavyLease.isLocked) minOf(wanted,2) else wanted
    }
    private fun dataDirectory(model: PhoneModel, directory: File): String {
        val path=model.option("data_dir").ifEmpty {model.files.firstOrNull {"espeak-ng-data/" in it.path}?.path?.substringBefore("espeak-ng-data/")?.plus("espeak-ng-data").orEmpty()}
        if(path.isBlank()) return ""
        val result=File(directory,path).canonicalFile
        if(!result.path.startsWith(directory.canonicalPath+File.separator)) throw ClientFailure("mobile.local.model_incompatible")
        return result.path
    }
}
/** Whisper phrase windows: short enough to time a dub line, long enough for context. */
const val SEGMENT_SECONDS=10

/**
 * First and last sample of the loud part of samples[start, limit) (30 ms frames above
 * -50 dBFS and a tenth of the window's peak), or null when the window is silence.
 */
fun voicedSpan(samples: FloatArray, start: Int, limit: Int, rate: Int): Pair<Int,Int>? {
    val frame=(rate*3/100).coerceAtLeast(1)
    val levels=(start until limit step frame).map {from ->
        val to=minOf(from+frame,limit);var sum=0.0
        for(i in from until to) sum+=samples[i]*samples[i]
        from to kotlin.math.sqrt(sum/(to-from).coerceAtLeast(1))
    }
    val peak=levels.maxOfOrNull {it.second} ?: return null
    val threshold=maxOf(.00316,peak*.1)
    val loud=levels.filter {it.second>=threshold}
    if(peak<.00316||loud.isEmpty()) return null
    return loud.first().first to minOf(loud.last().first+frame,limit)
}

/**
 * Text as sherpa's front ends can read it: NFC, typographic quotes and dashes as plain
 * punctuation, and no emoji or other characters outside the BMP (JNI's modified UTF-8
 * garbles them, and espeak/Kokoro choke on them).
 */
fun speakable(text: String): String {
    val normal=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFC)
    val out=StringBuilder(normal.length)
    var i=0
    while(i<normal.length) {
        val cp=normal.codePointAt(i);i+=Character.charCount(cp)
        when {
            cp in setOf(0x201C,0x201D,0x201E,0x201F,0x00AB,0x00BB,0x2033,0x301D,0x301E) -> out.append('"')
            cp in setOf(0x2018,0x2019,0x201A,0x201B,0x2039,0x203A,0x2032,0x00B4,0x0060) -> out.append('\'')
            cp in setOf(0x2013,0x2014,0x2015,0x2212) -> out.append(", ")
            cp in setOf(0x2010,0x2011,0x2012) -> out.append('-')
            cp==0x2026 -> out.append("...")
            cp in setOf(0x00A0,0x2007,0x202F,0x2009,0x200A,0x3000) -> out.append(' ')
            cp in setOf(0x00B9,0x00B2,0x00B3) -> out.append(when(cp) {0x00B9 -> '1';0x00B2 -> '2';else -> '3'})
            cp>0xFFFF||cp in 0xFE00..0xFE0F||cp in setOf(0x200B,0x200C,0x200D,0x2060,0xFEFF) -> {}
            cp=='\n'.code||cp=='\t'.code -> out.appendCodePoint(cp)
            Character.isISOControl(cp)||Character.getType(cp)==Character.OTHER_SYMBOL.toInt()||Character.getType(cp)==Character.PRIVATE_USE.toInt()||Character.getType(cp)==Character.SURROGATE.toInt() -> out.append(' ')
            else -> out.appendCodePoint(cp)
        }
    }
    return out.toString().replace(Regex("[ ]{2,}")," ").trim()
}

/** Catalogue language codes as espeak-ng voice names (Kokoro passes them to espeak). */
fun espeakVoice(language: String): String = when(language.lowercase()) {"en-gb" -> "en-gb-x-rp";else -> language}

/**
 * Whisper windows of at most [maxSeconds]. Each cut moves back to the quietest 30 ms
 * frame in the window's last [searchSeconds], so words are rarely split in half.
 */
fun speechWindows(samples: FloatArray, rate: Int, maxSeconds: Int = 20, searchSeconds: Int = 4): List<Pair<Int,Int>> {
    require(rate>0 && maxSeconds>searchSeconds && searchSeconds>0)
    val result=mutableListOf<Pair<Int,Int>>()
    val window=rate*maxSeconds;val frame=(rate*3/100).coerceAtLeast(1)
    var start=0
    while(start<samples.size) {
        var end=minOf(start+window,samples.size)
        if(end<samples.size) {
            var best=end;var quietest=Double.MAX_VALUE
            var position=end-rate*searchSeconds
            while(position+frame<=end) {
                var energy=0.0
                for(i in position until position+frame) energy+=samples[i]*samples[i]
                if(energy<quietest) {quietest=energy;best=position+frame/2}
                position+=frame
            }
            end=best
        }
        result+=start to end;start=end
    }
    return result
}

/** Every local synthetic export passes this marker (RIFF INFO provenance). */
fun markSynthetic(audio: LocalAudio, file: File) {
    require(audio.sampleRate>0 && audio.samples.isNotEmpty())
    val marker="VoiceStudio synthetic audio\u0000".toByteArray(Charsets.US_ASCII)
    val padded=marker.size+(marker.size%2)
    val dataBytes=audio.samples.size*2
    val buffer=ByteBuffer.allocate(12+24+8+dataBytes+8+4+8+padded).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(value: String) {buffer.put(value.toByteArray(Charsets.US_ASCII))}
    tag("RIFF");buffer.putInt(buffer.capacity()-8);tag("WAVE");tag("fmt ");buffer.putInt(16);buffer.putShort(1);buffer.putShort(1)
    buffer.putInt(audio.sampleRate);buffer.putInt(audio.sampleRate*2);buffer.putShort(2);buffer.putShort(16)
    tag("data");buffer.putInt(dataBytes);audio.samples.forEach {buffer.putShort((it.coerceIn(-1f,1f)*32767).toInt().toShort())}
    tag("LIST");buffer.putInt(4+8+padded);tag("INFO");tag("ICMT");buffer.putInt(marker.size);buffer.put(marker);if(marker.size%2!=0) buffer.put(0)
    file.parentFile!!.mkdirs();FileOutputStream(file).use {it.write(buffer.array());it.fd.sync()}
}

/** CPU feature flags every core reports (ARM "Features", x86 "flags" in /proc/cpuinfo). */
internal fun cpuFeatures(cpuinfo: String = runCatching {File("/proc/cpuinfo").readText()}.getOrDefault("")): Set<String> =
    cpuinfo.lineSequence().filter {it.startsWith("Features")||it.startsWith("flags")}
        .map {it.substringAfter(':').trim().split(Regex("\\s+")).toSet()}
        .reduceOrNull {all,next -> all intersect next}.orEmpty()

/**
 * Native library suffixes to try, fastest first; must match build.sh and verify.py.
 * Each ARM variant also needs what its -march implies (LSE atomics, RCpc, BF16), so
 * a CPU missing any of them keeps a slower but safe build.
 */
internal fun cpuVariants(features: Set<String>): List<String> = buildList {
    val dotprod=listOf("asimddp","asimdhp","fphp","atomics")
    if(features.containsAll(dotprod+listOf("lrcpc","i8mm","bf16"))) add("_i8mm")
    if(features.containsAll(dotprod)) add("_dotprod")
    if(features.containsAll(listOf("avx","avx2","fma","f16c","bmi2"))) add("_avx2")
    add("")
}

/** The CPU build of OmniVoice/llama.cpp in use, or the one that will load: "i8mm", "dotprod", "avx2" or "baseline". */
fun nativeCpuVariant(): String = (NativeBridge.variant ?: cpuVariants(cpuFeatures()).first()).removePrefix("_").ifEmpty {"baseline"}

/** Model-free device test: all native dependencies must resolve on ART. */
suspend fun verifyNativeRuntimes(): Map<String,Int> = withContext(Dispatchers.IO) {
    System.loadLibrary("sherpa-onnx-jni")
    NativeBridge.load()
    mapOf("sherpa-onnx" to 1,"omnivoice.cpp" to NativeBridge.omniAbi(),"llama.cpp" to NativeBridge.llamaDevices())
}

/**
 * sherpa-onnx's JNI looks the callback's method up by its exact signature,
 * `invoke([F)Ljava/lang/Integer;`. A Kotlin lambda (compiled via invokedynamic and
 * desugared by D8) exposes only the erased `invoke(Object)`, so the lookup throws
 * NoSuchMethodError and the next JNI call aborts the process. A named class keeps
 * the typed method. Pass every native callback through a class like this.
 */
internal class NativeTtsCallback(private val onChunk: () -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onChunk()
}
