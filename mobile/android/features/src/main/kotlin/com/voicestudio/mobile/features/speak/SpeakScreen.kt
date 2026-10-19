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
        else {
            val phone by vm.phone.state.collectAsState()
            val context=LocalContext.current
            val scope=rememberCoroutineScope()
            var text by rememberSaveable {mutableStateOf("")}
            var speaker by rememberSaveable(initialPhoneVoiceID) {mutableStateOf(initialPhoneVoiceID?.let {"phone:"+it} ?: "0")}
            val voice=phone.voices.firstOrNull {"phone:"+it.id==speaker}
            val capability=if(voice==null) "tts" else "clone"
            val selected=model?.takeIf {it.capability==capability} ?: phone.models.firstOrNull {it.capability==capability&&it.id in phone.installed} ?: phone.models.firstOrNull {it.capability==capability}
            LaunchedEffect(phone.voices) {if(speaker.startsWith("phone:")&&voice==null) speaker="0"}
            val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {uri ->
                val source=phone.audio
                if(uri!=null&&source!=null) scope.launch {
                    try {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)!!.use {out -> source.inputStream().use {it.copyTo(out)}}}}
                    catch(e:Exception) {vm.fail(e)}
                }
            }
            DisposableEffect(Unit) {onDispose {vm.phone.cancelInference()}}
            Screen {
                ScreenTitle("mobile.tabs.speak")
                Field("mobile.speak.placeholder",text,{text=it},multiline=true)
                val speakers=selected?.option("num_speakers",selected.option("speaker_count","1"))?.toIntOrNull()?.coerceIn(1,128) ?: 1
                val validSpeaker=voice!=null||speaker.toIntOrNull()?.let {it in 0 until speakers}==true
                LaunchedEffect(selected?.id,speakers) {if(voice==null&&!validSpeaker) speaker="0"}
                var language by rememberSaveable {mutableStateOf("en")}
                LaunchedEffect(selected?.id,voice?.id) {language=if(voice!=null) "auto" else language.takeIf {it in selected?.languages.orEmpty()} ?: selected?.languages?.firstOrNull().orEmpty()}
                val languageChoices=if(voice!=null) listOf(Choice("auto",tr("languagePicker.auto"))) else selected?.languages.orEmpty().map {Choice(it,languageName(it,LocalL10n.current.locale))}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
                    Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.voice",speaker,(0 until speakers).map {Choice(it.toString(),tr("mobile.local.preset_voice",mapOf("number" to it+1)))}+phone.voices.map {Choice("phone:"+it.id,it.name)},compact=true) {speaker=it}}
                    Box(Modifier.weight(1f)) {com.voicestudio.mobile.features.models.TaskModelPicker(vm,"speak",setOf(capability),selected,compact=true)}
                    Box(Modifier.weight(1f)) {ChoicePicker("mobile.speak.language",language,languageChoices,compact=true) {language=it}}
                }
                if(selected?.let {!phone.canRun(it,vm.phone.ramBytes)}!=false) selected?.let {com.voicestudio.mobile.features.models.MissingModelsCard(vm,"mobile.tabs.speak",listOf(it))}
                else Action("mobile.speak.generate",text.isNotBlank()&&selected!=null&&validSpeaker&&phone.progress==null) {vm.phone.speak(selected!!,text,speaker.toIntOrNull() ?: 0,voice,language.takeUnless {it=="auto"})}
                phone.progress?.let {p ->
                    LinearProgressIndicator(progress={p},modifier=Modifier.fillMaxWidth())
                    Secondary("common.cancel") {vm.phone.cancelInference()}
                }
                phone.audio?.let {file ->
                    LocalAudioPlayer(file,vm::fail)
                    Secondary("mobile.speak.share") {scope.launch {try {shareLocalFile(context,file,"audio/wav")} catch(e:Exception) {vm.fail(e)}}}
                    Secondary("common.save") {save.launch(file.name)}
                }
                Heading("mobile.speak.recent")
                if(phone.recent.isEmpty()) EmptyState("mobile.simple.empty_recent")
                phone.recent.forEach {item ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(item.text,Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        IconAction("player.play",Icons.Default.PlayArrow) {vm.phone.play(item)}
                        IconAction("common.delete",Icons.Default.Delete) {vm.phone.deleteGeneration(item)}
                    }
                }

            }
        }
    }
}
