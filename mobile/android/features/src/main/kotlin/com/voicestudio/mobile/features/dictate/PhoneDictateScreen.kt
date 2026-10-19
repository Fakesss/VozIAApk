package com.voicestudio.mobile.features.dictate

import androidx.compose.material.icons.filled.*
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicestudio.mobile.designsystem.*
import com.voicestudio.mobile.features.*
import com.voicestudio.mobile.features.models.MissingModelsCard
import com.voicestudio.mobile.local.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class PhoneDictation(private val engine: LocalEngine, private val scope: CoroutineScope, private val failure: (Throwable)->Unit,
    private val microphone: DictationAudioSource = MicrophoneSource()) : AutoCloseable {
    private val mutableText=MutableStateFlow("")
    val text=mutableText.asStateFlow()
    private val mutableActive=MutableStateFlow(false)
    val active=mutableActive.asStateFlow()
    @Volatile private var recording=false
    private var capture: Job?=null
    fun start(model: PhoneModel, language: String? = null) {
        if(mutableActive.value) return
        mutableActive.value=true;mutableText.value="";recording=true
        if(model.capability=="asr") {capture=scope.launch {whisper(model,language)};return}
        capture=scope.launch {
            try {
                val samples=channelFlow {
                    microphone.record({recording}) {buffer,n ->
                        val pcm=ByteBuffer.wrap(buffer,0,n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val floats=FloatArray(n/2) {pcm.get().toFloat()/32768f}
                        if(!trySend(LocalAudio(floats,16000)).isSuccess) throw ClientFailure("mobile.local.capture_slow")
                    }
                }.buffer(64,BufferOverflow.SUSPEND)
                engine.streamTranscribe(model,samples).collect {mutableText.value=it.text}
            } catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
            finally {recording=false;mutableActive.value=false}
        }
    }
    /**
     * Whisper is not a streaming model, so speech is cut at pauses (or every 15 s) and
     * each phrase is transcribed on the phone while recording continues. This is what
     * makes dictation work in Portuguese and Whisper's other languages.
     */
    private suspend fun whisper(model: PhoneModel, language: String?) = coroutineScope {
        val phrases=kotlinx.coroutines.channels.Channel<FloatArray>(8)
        val decoder=launch {
            for(phrase in phrases) {
                val text=engine.transcribeSegments(model,LocalAudio(phrase,16000),language).joinToString(" ") {it.text.trim()}.trim()
                if(text.isNotEmpty()) mutableText.value=listOf(mutableText.value,text).filter {it.isNotBlank()}.joinToString(" ")
            }
        }
        val segmenter=PhraseSegmenter(16000)
        try {
            microphone.record({recording}) {buffer,n ->
                val pcm=ByteBuffer.wrap(buffer,0,n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                segmenter.accept(FloatArray(n/2) {pcm.get().toFloat()/32768f})?.let {if(!phrases.trySend(it).isSuccess) throw ClientFailure("mobile.local.capture_slow")}
            }
            segmenter.flush()?.let {phrases.send(it)}
            phrases.close();decoder.join()
        } catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
        finally {phrases.close();recording=false;mutableActive.value=false}
    }
    fun finish() {recording=false}
    override fun close() {recording=false;capture?.cancel()}
}

/** Energy-based pauses split speech into phrases of at least 0.6 s and at most 15 s. */
internal class PhraseSegmenter(private val rate: Int, private val pause: Double = .7, private val longest: Double = 15.0, private val threshold: Float = .012f) {
    private val buffer=ArrayList<Float>(rate*16)
    private var silent=0;private var voiced=0
    fun accept(samples: FloatArray): FloatArray? {
        val energy=kotlin.math.sqrt(samples.fold(0.0) {a,s -> a+s*s}/samples.size.coerceAtLeast(1)).toFloat()
        if(energy>=threshold) {voiced+=samples.size;silent=0} else silent+=samples.size
        if(voiced==0&&energy<threshold) return null
        samples.forEach(buffer::add)
        val cut=(silent>=pause*rate&&voiced>=.6*rate)||buffer.size>=longest*rate
        return if(cut) take() else null
    }
    fun flush(): FloatArray? = if(voiced>=.3*rate) take() else null
    private fun take(): FloatArray {val result=buffer.toFloatArray();buffer.clear();silent=0;voiced=0;return result}
}

@Composable internal fun PhoneDictateScreen(vm: StudioViewModel, model: PhoneModel?, ready: Boolean) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val controller=remember(model?.id) {PhoneDictation(vm.phone.engine,scope,vm::fail)}
    val text by controller.text.collectAsState()
    val active by controller.active.collectAsState()
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle,controller) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP) controller.close()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);controller.close()}
    }
    var holding by remember {mutableStateOf(false)}
    // Whisper takes the app language ("pt", "en", ...); the streaming Zipformer model is English only.
    val language=LocalL10n.current.locale.substringBefore('-').lowercase().takeIf {model?.capability=="asr"&&it.matches(Regex("[a-z]{2}"))}
    val start=permissionAction(Manifest.permission.RECORD_AUDIO,"mobile.permissions.microphone",vm::fail) {if(ready&&model!=null) controller.start(model,language)}
    val holdStart=permissionAction(Manifest.permission.RECORD_AUDIO,"mobile.permissions.microphone",vm::fail) {if(holding&&ready&&model!=null) controller.start(model,language)}
    DictationLayout(content={
        ScreenTitle("mobile.tabs.dictate")
        com.voicestudio.mobile.features.models.TaskModelPicker(vm,"dictate",setOf("asr-stream","asr"),model)
        if(model?.capability=="asr-stream") Text(tr("vozia.dictate.english_only"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
        if(!ready) model?.let {MissingModelsCard(vm,"mobile.tabs.dictate",listOf(it))}
        Text(text,modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget*3).semantics {liveRegion=LiveRegionMode.Polite})
    }, controls={
        MicControl(active,ready,tap={holding=false;if(active) controller.finish() else start()},hold={holding=true;holdStart()},release={if(holding) {holding=false;controller.finish()}})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(VSTheme.gap,androidx.compose.ui.Alignment.CenterHorizontally)) {
            IconAction("mobile.dictate.copy",VSIcons.Copy,text.isNotBlank()) {context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null,text))}
            IconAction("mobile.speak.share",androidx.compose.material.icons.Icons.Default.Share,text.isNotBlank()) {shareText(context,text)}
        }
    })
}
