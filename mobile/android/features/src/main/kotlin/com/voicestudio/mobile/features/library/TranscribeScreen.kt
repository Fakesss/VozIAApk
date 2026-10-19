package com.voicestudio.mobile.features.library

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.designsystem.VSTheme
import com.voicestudio.mobile.designsystem.role
import com.voicestudio.mobile.features.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Whisper language codes offered for transcription; "" keeps automatic detection. */
private val WHISPER_LANGUAGES = listOf("", "pt", "en", "es", "fr", "de", "it", "nl", "pl", "ru", "uk", "tr", "ar", "hi", "ja", "ko", "zh", "vi", "id")

/**
 * Audio or video files (or a new recording) become text with timestamps on the
 * phone: the transcript is kept with TXT, SRT and VTT subtitle files.
 */
@Composable fun TranscribeScreen(vm: StudioViewModel, projectId: String?, open: (String) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    val context = LocalContext.current
    val library = vm.phone.library
    val l = LocalL10n.current
    var language by rememberSaveable { mutableStateOf(if (l.locale.startsWith("pt")) "pt" else "") }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { SampleRecorder(context) }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.discard() } }
    val recordingName = tr("vozia.transcribe.recording_name")
    suspend fun import(file: File, name: String, mime: String) {
        val project = projectId?.let { library.project(it) } ?: library.createProject(name.substringBeforeLast('.').ifBlank { name }, ProjectKind.TRANSCRIPTION)
        val extension = file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        library.addFile(project.id, if (mime.startsWith("audio/") && name == recordingName) ItemKind.RECORDING else ItemKind.IMPORT, name, "", file, mime, extension)
        withContext(Dispatchers.IO) { file.delete() }
        if (projectId == null) open(project.id)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.phone.launch {
            val name = mediaDisplayName(context, uri) ?: "audio"
            import(copyMedia(context, uri, "audio"), name, context.contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }
    val record = permissionAction(Manifest.permission.RECORD_AUDIO, "mobile.permissions.microphone", vm::fail) {
        try { recorder.start(); recording = true } catch (e: Exception) { vm.fail(e) }
    }
    Screen {
        ScreenTitle("vozia.transcribe.title")
        Text(tr("vozia.transcribe.intro"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        if (projectId == null) {
            Action("vozia.transcribe.pick", !recording) { picker.launch(arrayOf("audio/*", "video/*")) }
            if (!recording) Secondary("mobile.voices.record") { record() }
            else Secondary("common.stop") {
                recording = false
                vm.phone.launch { import(recorder.stop(), recordingName, "audio/mp4") }
            }
            val projects = phone.projects.filter { it.kind == ProjectKind.TRANSCRIPTION }
            Heading("vozia.transcribe.history")
            if (projects.isEmpty()) EmptyState("vozia.transcribe.empty")
            projects.forEach { project ->
                Surface(onClick = { open(project.id) }, shape = MaterialTheme.shapes.large, color = role("surface"), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(VSTheme.inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                        Icon(Icons.Default.Edit, null, tint = role("messageAction")); Text(project.name, Modifier.weight(1f))
                    }
                }
            }
            return@Screen
        }
        val project = phone.projects.firstOrNull { it.id == projectId }
        val items by produceState(emptyList<LibraryItem>(), phone.revision, projectId) { value = library.items(projectId) }
        val source = items.firstOrNull { it.kind == ItemKind.IMPORT || it.kind == ItemKind.RECORDING }
        Text(project?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
        if (source == null) { InlineLoading(); return@Screen }
        com.voicestudio.mobile.features.models.TaskLocation(vm, "transcribe", setOf("asr")) { _, model ->
            Column(verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                com.voicestudio.mobile.features.models.TaskModelPicker(vm, "transcribe", setOf("asr"), model)
                ChoicePicker("mobile.speak.language", language, WHISPER_LANGUAGES.map { Choice(it, if (it.isEmpty()) tr("languagePicker.auto") else languageName(it, l.locale)) }) { language = it }
                val task = phone.tasks.firstOrNull { it.projectId == projectId && it.kind == TaskKind.TRANSCRIBE && it.active }
                if (model != null && !phone.canRun(model, vm.phone.ramBytes)) com.voicestudio.mobile.features.models.MissingModelsCard(vm, "vozia.transcribe.title", listOf(model))
                else if (task != null) TaskCard(vm, task)
                else Action("vozia.transcribe.start", model != null) {
                    vm.phone.launch {
                        val input = TranscribeInput(source.id, model!!.id, language.ifEmpty { null })
                        LibraryWorker.enqueue(context, library.createTask(TaskKind.TRANSCRIBE, projectId, project?.name ?: source.title,
                            TaskJson.encodeToString(TranscribeInput.serializer(), input)).id)
                    }
                }
            }
        }
        phone.tasks.filter { it.projectId == projectId && !it.active && it.state == TaskState.FAILED }.take(1).forEach { TaskCard(vm, it) }
        items.sortedWith(compareBy<LibraryItem> { it.kind != ItemKind.TRANSCRIPT }.thenBy { it.kind != ItemKind.SUBTITLE }).forEach { ItemCard(vm, it, project?.name) }
    }
}
