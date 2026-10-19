package com.voicestudio.mobile.features

import android.content.Context
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.local.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class PhoneState(val models: List<PhoneModel> = emptyList(), val installed: Set<String> = emptySet(), val used: Long = 0,
    val storage: Map<String,Long> = emptyMap(), val receipts: Map<String,List<LicenceReceipt>> = emptyMap(), val loading: Boolean = true, val progress: Float? = null,
    val audio: File? = null, val audioItem: LibraryItem? = null, val voices: List<PhoneVoice> = emptyList(), val recent: List<LibraryItem> = emptyList(),
    val dubs: List<PhoneDubRecord> = emptyList(), val tasks: List<TaskRecord> = emptyList(), val projects: List<Project> = emptyList(),
    val speechTask: String? = null, val libraryBytes: Long = 0, val revision: Long = 0, val defaultProject: String? = null,
    /** Voice creation progress; separate from speech so a long speech task never blocks creating a voice. */
    val cloneProgress: Float? = null, val cloneWaiting: Boolean = false,
    /** True when [audio] was opened by a ▶ tap (play it now), false for a fresh generation result. */
    val autoPlay: Boolean = false, val submitting: Boolean = false) {
    fun canRun(model: PhoneModel, ramBytes: Long): Boolean = model.id in installed&&model.fits(ramBytes)&&accepted(model)
    fun accepted(model: PhoneModel): Boolean = !model.gated || receipts[model.id]?.lastOrNull()?.let {it.accepted&&it.fingerprint==model.licence.termsFingerprint}==true
    val speaking: TaskRecord? get() = tasks.firstOrNull {it.id==speechTask}
}
class PhoneStudio(context: Context, private val scope: CoroutineScope, private val failure: (Throwable)->Unit, private val retryFailure: (Throwable,()->Unit)->Unit = {error,_ ->failure(error)}) {
    private val application=context.applicationContext
    private val runtime=PhoneRuntime.get(application)
    val ramBytes=runtime.ramBytes
    val licence=runtime.licence
    val downloads=runtime.downloads
    val engine=runtime.engine
    val speech=runtime.speech
    val voices=PhoneVoiceStore(File(application.noBackupFilesDir,"voices"))
    val dubs=PhoneDubStore(File(application.noBackupFilesDir,"dubs"))
    val library=Library.get(application)
    val exporter=Exporter(application,library)
    private val mutable=MutableStateFlow(PhoneState())
    val state=mutable.asStateFlow()
    private val jobs=mutableMapOf<String,Job>()
    private var inference: Job?=null
    init {
        scope.launch {
            try {
                // Models for the device language come first, so the suggested download speaks it.
                val language=java.util.Locale.getDefault().language
                val models=withContext(Dispatchers.IO) {runtime.models()}.filterNot {it.system}
                    .sortedByDescending {model -> model.languages.any {java.util.Locale.forLanguageTag(it).language==language}}
                mutable.update {it.copy(models=models,loading=false)};refresh()
                // The system voice is offered only when the device has an offline engine voice.
                val languages=speech.languages()
                if(languages.isNotEmpty()) {
                    mutable.update {state -> state.copy(models=listOf(systemTtsModel(languages))+state.models)}
                    refresh()
                }
            } catch(e:CancellationException) {throw e} catch(e:Exception) {mutable.update {it.copy(loading=false)};failure(ClientFailure("mobile.local.catalogue_failed"))}
        }
        scope.launch {
            @OptIn(FlowPreview::class)
            library.revision.debounce(150).collect {revision ->
                try {refreshLibrary(revision)} catch(e:CancellationException) {throw e} catch(_:Exception) {}
            }
        }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val installed=mutable.value.models.filter(downloads::installed).map {it.id}.toSet()
        val used=downloads.usedBytes()
        val storage=mutable.value.models.associate {it.id to downloads.usedBytes(it)}
        val receipts=mutable.value.models.associate {it.id to licence.history(it)}
        mutable.update {it.copy(installed=installed,used=used,storage=storage,receipts=receipts)}
        val phoneVoices=voices.list()
        val phoneDubs=dubs.list()
        mutable.update {it.copy(voices=phoneVoices,dubs=phoneDubs)}
        refreshLibrary(library.revision.value)
    }
    private suspend fun refreshLibrary(revision: Long) {
        val recent=library.items(kinds=listOf(ItemKind.SPEECH),limit=30).sortedByDescending {it.createdAt}
        val tasks=library.tasks(limit=100)
        val projects=library.projects()
        val bytes=library.usedBytes()
        val defaultProject=library.setting("default_project")
        mutable.update {it.copy(recent=recent,tasks=tasks,projects=projects,libraryBytes=bytes,revision=revision,defaultProject=defaultProject)}
        // The task Speak follows survives process death: it is kept in settings until it ends.
        if(mutable.value.speechTask==null) library.setting(SPEAK_TASK)?.let {id -> if(tasks.any {it.id==id&&it.active}) mutable.update {it.copy(speechTask=id)}}
        val tracked=mutable.value.speechTask ?: return
        val speaking=tasks.firstOrNull {it.id==tracked}
        if(speaking==null) {mutable.update {it.copy(speechTask=null,progress=null)};library.putSetting(SPEAK_TASK,null);return}
        when(speaking.state) {
            TaskState.SUCCEEDED -> {
                val item=speaking.resultItem?.let {library.item(it)}
                mutable.update {it.copy(speechTask=null,progress=null,audioItem=item,audio=item?.let(library::file),autoPlay=false)}
                library.putSetting(SPEAK_TASK,null)
            }
            TaskState.FAILED -> {
                mutable.update {it.copy(speechTask=null,progress=null)}
                library.putSetting(SPEAK_TASK,null)
                failure(ClientFailure(speaking.error ?: "vozia.task.failed",errorParams(speaking.errorParams)))
            }
            TaskState.CANCELLED -> {mutable.update {it.copy(speechTask=null,progress=null)};library.putSetting(SPEAK_TASK,null)}
            else -> mutable.update {it.copy(progress=speaking.progress)}
        }
    }
    fun download(model: PhoneModel, allowMobile: Boolean) {
        if(model.system||jobs[model.id]?.isActive==true) return
        jobs[model.id]=scope.launch {
            try {downloads.download(model,allowMobile)}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {retryFailure(e) {download(model,allowMobile)}}
            finally {withContext(NonCancellable) {refresh()}}
        }
    }
    fun cancelDownload(model: PhoneModel) {jobs[model.id]?.cancel()}
    fun delete(model: PhoneModel) {scope.launch {
        try {jobs[model.id]?.cancelAndJoin();cancelInference();downloads.delete(model);refresh()}
        catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
    }}
    fun accept(model: PhoneModel, accepted: Boolean, fingerprint: String, completed: (Boolean)->Unit = {}) {scope.launch {
        try {
            if(!accepted) {jobs[model.id]?.cancelAndJoin();cancelInference()}
            withContext(Dispatchers.IO) {licence.record(model,accepted,fingerprint)};refresh();completed(true)
        } catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e);completed(false)}
    }}
    /**
     * Speech runs as a persistent library task: it survives leaving the screen or the
     * app, appears in Tasks, and its WAV lands in [projectId] (or the default project).
     */
    fun speak(model: PhoneModel, text: String, speaker: Int, voice: PhoneVoice? = null, language: String? = null, speed: Float = 1f,
              projectId: String? = null, title: String = "", speakerName: String? = null, defaultProject: String = "My audio") {
        // A double tap must not create two tasks; a task still running elsewhere does not block a new one.
        if(mutable.value.submitting) return
        mutable.update {it.copy(submitting=true)}
        scope.launch {
            try {
                val project=projectId?.let {library.project(it)} ?: library.defaultProject(defaultProject)
                val name=title.trim().ifEmpty {text.trim().replace(Regex("\\s+")," ").take(48)}
                val choice=VoiceChoice(model.id,speaker,language,speed,voice?.id,voice?.name ?: speakerName)
                val task=library.createTask(TaskKind.SPEECH,project.id,name,TaskJson.encodeToString(SpeechInput.serializer(),SpeechInput(text,name,choice)))
                library.putSetting(SPEAK_TASK,task.id)
                mutable.update {it.copy(speechTask=task.id,progress=0f,audio=null,audioItem=null)}
                LibraryWorker.enqueue(application,task.id)
            } catch(e:CancellationException) {throw e} catch(e:Exception) {mutable.update {it.copy(speechTask=null,progress=null)};failure(e)}
            finally {mutable.update {it.copy(submitting=false)}}
        }
    }
    fun cancelSpeech() {
        val task=mutable.value.speechTask ?: return
        scope.launch {LibraryWorker.cancel(application,library,task);library.putSetting(SPEAK_TASK,null);mutable.update {it.copy(speechTask=null,progress=null)}}
    }
    fun play(item: LibraryItem) {mutable.update {it.copy(audio=library.file(item),audioItem=item,autoPlay=true)}}
    fun deleteGeneration(item: LibraryItem) {scope.launch {
        try {library.deleteItem(item.id);mutable.update {it.copy(audio=it.audio.takeUnless {_ -> it.audioItem?.id==item.id},audioItem=it.audioItem?.takeUnless {current -> current.id==item.id})}}
        catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
    }}
    fun clone(name: String, file: File, model: PhoneModel, consent: Boolean, created: ()->Unit = {}) {
        if(inference?.isActive==true) return
        inference=scope.launch {
            mutable.update {it.copy(cloneProgress=0f)}
            try {
                withContext(EngineWait {waiting -> mutable.update {it.copy(cloneWaiting=waiting)}}) {
                    voices.create(name,file,consent,model,engine,progress={p -> mutable.update {it.copy(cloneProgress=p)}})
                }
                refresh();withContext(Dispatchers.IO) {file.delete()};created()
            }
            catch(e:CancellationException) {throw e} catch(e:Exception) {retryFailure(e) {clone(name,file,model,consent,created)}}
            finally {mutable.update {it.copy(cloneProgress=null,cloneWaiting=false)}}
        }
    }
    fun renameVoice(voice: PhoneVoice, name: String) {scope.launch {try {voices.rename(voice,name);refresh()} catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}}}
    fun deleteVoice(voice: PhoneVoice) {scope.launch {try {voices.delete(voice);refresh()} catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}}}
    fun refreshDubs() {scope.launch {
        try {val records=dubs.list();mutable.update {it.copy(dubs=records)}}
        catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
    }}
    /** Cancels screen-bound inference (voice creation); library tasks keep running. */
    fun cancelInference() {inference?.cancel()}
    fun launch(block: suspend CoroutineScope.()->Unit) = scope.launch {
        try {block()} catch(e:CancellationException) {throw e} catch(e:Exception) {failure(e)}
    }
    companion object {private const val SPEAK_TASK="speak_task"}
}

/** A task's stored error parameters ({"ram":"6"}) for its message. */
fun errorParams(json: String?): Map<String,String> = runCatching {
    kotlinx.serialization.json.Json.decodeFromString<Map<String,String>>(json ?: "{}")
}.getOrDefault(emptyMap())
