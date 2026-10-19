package com.voicestudio.mobile.features.speak

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.voicestudio.mobile.designsystem.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.voicestudio.mobile.features.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable private fun ComputerSpeakScreen(vm:StudioViewModel, initialVoiceID: String? = null) {
    val state by vm.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var text by rememberSaveable {mutableStateOf("")}
    var voice by rememberSaveable(initialVoiceID) {mutableStateOf(initialVoiceID.orEmpty())}
    var engine by rememberSaveable {mutableStateOf("")}
    var language by rememberSaveable {mutableStateOf("Auto")}
    val catalog=rememberLanguageCatalog(vm::fail)
    val l=LocalL10n.current
    val profile=state.profiles.firstOrNull {it.id==voice}
    val engines=engineChoices(state.engines,profile) {l.t(it)}
    var savePath by rememberSaveable {mutableStateOf<String?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {uri ->
        val path=savePath;savePath=null
        if(uri!=null && path!=null) scope.launch {
            try {val file=sharedDownload(context,vm.api!!,path,"wav");withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)!!.use {out -> file.inputStream().use {it.copyTo(out)}}};file.delete()}
            catch(e:Exception) {vm.fail(e)}
        }
    }
    LaunchedEffect(state.profiles) {if(voice.isNotBlank() && profile==null) voice=""}
    LaunchedEffect(voice,profile?.language,catalog) {language=catalog?.preferred(profile) ?: "Auto"}
    LaunchedEffect(engines,state.engines.active) {
        if(engines.none {it.id==engine && it.enabled}) engine=initialEngine(state.engines,engines)
    }
    Screen {
        ScreenTitle("mobile.tabs.speak")
        Field("mobile.speak.placeholder",text,{text=it},multiline=true)
        if(catalog==null) InlineLoading()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.voice",voice,listOf(Choice("",tr("mobile.simple.default_voice")))+state.profiles.map {Choice(it.id,it.name)},compact=true) {voice=it}}
            Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.engine",engine,engines,compact=true) {engine=it}}
            Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.language",language,catalog?.names.orEmpty().map {Choice(it,it)},compact=true) {language=it}}
        }
        Action(if(state.busy) "mobile.speak.generating" else "mobile.speak.generate",text.isNotBlank()&&engines.any {it.id==engine&&it.enabled}&&!state.busy) {
            vm.generate(text,voice,engine,language)
        }
        val audio=state.audio;val api=vm.api
        if(audio!=null && api!=null) {
            HostPlayer(api,audio,vm::fail)
            Secondary("mobile.speak.share",!state.busy) {vm.run {shareFile(context,sharedDownload(context,api,audio,"wav"),"audio/wav")}}
            Secondary("common.save",!state.busy) {savePath=audio;save.launch("${java.util.UUID.randomUUID()}.wav")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {Heading("mobile.speak.recent")}
            IconAction("common.refresh",Icons.Default.Refresh,!state.busy,onClick=vm::refresh)
        }
        if(state.catalogLoading) InlineLoading()
        else if(state.history.isEmpty()) EmptyState("mobile.simple.empty_recent")
        if(state.history.isNotEmpty()) Group {
            state.history.forEach {item ->
                Row(Modifier.fillMaxWidth().padding(VSTheme.inset),verticalAlignment=Alignment.CenterVertically) {
                    Text(item.text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    IconAction("player.play",Icons.Default.PlayArrow) {vm.play(vm.api!!.audioPath(item.audioPath))}
                    IconAction(if(item.isStarred) "mobile.speak.unstar" else "mobile.speak.star",Icons.Default.Star,!state.busy) {vm.star(item)}
                    IconAction("common.delete",Icons.Default.Delete,!state.busy) {vm.deleteHistory(item)}
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable fun SpeakScreen(vm: StudioViewModel, initialPhoneVoiceID: String? = null, initialComputerVoiceID: String? = null) {
    com.voicestudio.mobile.features.models.TaskLocation(vm,"speak",setOf("tts","clone")) {location,model ->
        if(location==com.voicestudio.mobile.local.RunLocation.COMPUTER) ComputerSpeakScreen(vm,initialComputerVoiceID)
        else PhoneSpeakScreen(vm,model,initialPhoneVoiceID)
    }
}

/** Picks the device language when a model offers it, else the model's first language. */
fun preferredLanguage(languages: List<String>, device: String): String? {
    val tag=java.util.Locale.forLanguageTag(device)
    return languages.firstOrNull {it.equals(device,true)} ?: languages.firstOrNull {java.util.Locale.forLanguageTag(it).language==tag.language} ?: languages.firstOrNull()
}

@Composable private fun PhoneSpeakScreen(vm: StudioViewModel, model: com.voicestudio.mobile.local.PhoneModel?, initialPhoneVoiceID: String?) {
    val phone by vm.phone.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val l=LocalL10n.current
    var text by rememberSaveable {mutableStateOf("")}
    var title by rememberSaveable {mutableStateOf("")}
    var speed by rememberSaveable {mutableFloatStateOf(1f)}
    var projectId by rememberSaveable {mutableStateOf<String?>(null)}
    var speaker by rememberSaveable(initialPhoneVoiceID) {mutableStateOf(initialPhoneVoiceID?.let {"phone:"+it} ?: "0")}
    val voice=phone.voices.firstOrNull {"phone:"+it.id==speaker}
    val capability=if(voice==null) "tts" else "clone"
    val selected=model?.takeIf {it.capability==capability} ?: phone.models.firstOrNull {it.capability==capability&&it.id in phone.installed} ?: phone.models.firstOrNull {it.capability==capability}
    LaunchedEffect(phone.voices) {if(speaker.startsWith("phone:")&&voice==null) speaker="0"}
    DisposableEffect(Unit) {onDispose {vm.phone.cancelInference()}}
    var language by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(selected?.id,voice?.id,selected?.languages) {
        language=if(voice!=null) "auto" else language.takeIf {it in selected?.languages.orEmpty()} ?: preferredLanguage(selected?.languages.orEmpty(),l.locale).orEmpty()
    }
    // Named presets: the model's catalogue speakers for this language, or the device's offline system voices.
    val systemVoices by produceState(emptyList<String>(),selected?.id,language) {
        value=if(selected?.system==true) vm.phone.speech.voices(language.takeUnless {it.isBlank()||it=="auto"}) else emptyList()
    }
    val presets: List<Choice> = when {
        selected==null -> emptyList()
        selected.system -> systemVoices.mapIndexed {index,name -> Choice(index.toString(),tr("vozia.speak.system_voice",mapOf("number" to index+1)),supporting=name)}.ifEmpty {listOf(Choice("0",tr("mobile.local.preset_voice",mapOf("number" to 1))))}
        selected.speakers.isNotEmpty() -> selected.speakers.filter {it.language==null||language.isBlank()||it.language.equals(language,true)}.map {Choice(it.id.toString(),it.name,supporting=it.language?.let {code -> languageName(code,l.locale)})}
        else -> {
            val count=selected.option("num_speakers",selected.option("speaker_count","1")).toIntOrNull()?.coerceIn(1,128) ?: 1
            (0 until count).map {Choice(it.toString(),tr("mobile.local.preset_voice",mapOf("number" to it+1)))}
        }
    }
    val validSpeaker=voice!=null||presets.any {it.id==speaker}
    LaunchedEffect(presets) {if(voice==null&&!validSpeaker) speaker=presets.firstOrNull()?.id ?: "0"}
    val projects=phone.projects.filter {it.kind==com.voicestudio.mobile.data.library.ProjectKind.GENERAL}
    Screen {
        ScreenTitle("mobile.tabs.speak")
        Field("mobile.speak.placeholder",text,{text=it},multiline=true)
        Text(tr("vozia.speak.characters",mapOf("count" to text.length)),style=MaterialTheme.typography.labelSmall,color=role("textMuted"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.voice",speaker,presets+phone.voices.map {Choice("phone:"+it.id,it.name,supporting=tr("vozia.speak.cloned_voice"))},compact=true) {speaker=it}}
            Box(Modifier.weight(1f)) {com.voicestudio.mobile.features.models.TaskModelPicker(vm,"speak",setOf(capability),selected,compact=true)}
            Box(Modifier.weight(1f)) {
                val languageChoices=if(voice!=null) listOf(Choice("auto",tr("languagePicker.auto"))) else selected?.languages.orEmpty().map {Choice(it,languageName(it,l.locale))}
                ChoicePicker("mobile.speak.language",language,languageChoices,compact=true) {language=it}
            }
        }
        if(selected?.system==true) Text(tr("vozia.speak.system_hint"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
        Text(tr("vozia.speak.speed",mapOf("speed" to "%.2f".format(speed))),style=MaterialTheme.typography.labelLarge)
        Slider(value=speed,onValueChange={speed=(it*20).roundToInt()/20f},valueRange=.5f..2f,steps=29,enabled=voice==null,modifier=Modifier.fillMaxWidth())
        Field("vozia.speak.title",title,{title=it})
        ChoicePicker("vozia.project.destination",projectId ?: "",listOf(Choice("",tr("vozia.project.default_name")))+projects.filter {it.id!=phone.defaultProject}.map {Choice(it.id,it.name)}) {projectId=it.ifEmpty {null}}
        val speaking=phone.speaking
        val defaultName=tr("vozia.project.default_name")
        if(selected?.let {!phone.canRun(it,vm.phone.ramBytes)}!=false) selected?.let {com.voicestudio.mobile.features.models.MissingModelsCard(vm,"mobile.tabs.speak",listOf(it))}
        else Action(if(speaking!=null) "mobile.speak.generating" else "mobile.speak.generate",text.isNotBlank()&&text.length<=200_000&&selected!=null&&validSpeaker&&speaking==null&&phone.speechTask==null) {
            val name=presets.firstOrNull {it.id==speaker}?.let {it.supporting?.takeIf {_ -> selected!!.system} ?: it.title}
            vm.phone.speak(selected!!,text,speaker.toIntOrNull() ?: 0,voice,language.takeUnless {it=="auto"||it.isBlank()},speed,projectId,title,name,defaultName)
        }
        if(phone.speechTask!=null) {
            LinearProgressIndicator(progress={phone.progress ?: 0f},modifier=Modifier.fillMaxWidth())
            Text(tr(speaking?.stage ?: "vozia.task.queued"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
            Text(tr("vozia.speak.background_hint"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
            Secondary("common.cancel") {vm.phone.cancelSpeech()}
        }
        phone.audioItem?.let {item ->
            phone.audio?.let {file -> LocalAudioPlayer(file,vm::fail)}
            val folder=phone.projects.firstOrNull {it.id==item.projectId}?.name
            com.voicestudio.mobile.features.library.ExportActions(vm,item,folder)
        }
        Heading("mobile.speak.recent")
        if(phone.recent.isEmpty()) EmptyState("mobile.simple.empty_recent")
        phone.recent.forEach {item ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(formatDuration(item.durationMs),item.voice,phone.projects.firstOrNull {it.id==item.projectId}?.name).joinToString(" · "),
                        style=MaterialTheme.typography.bodySmall,color=role("textMuted"),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                IconAction("player.play",Icons.Default.PlayArrow) {vm.phone.play(item)}
                IconAction("common.delete",Icons.Default.Delete) {vm.phone.deleteGeneration(item)}
            }
        }
    }
}
