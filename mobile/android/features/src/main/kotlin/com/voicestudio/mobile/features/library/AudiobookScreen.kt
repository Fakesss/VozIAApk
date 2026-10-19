package com.voicestudio.mobile.features.library

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
import androidx.compose.ui.text.style.TextOverflow
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.designsystem.VSTheme
import com.voicestudio.mobile.designsystem.role
import com.voicestudio.mobile.features.*
import com.voicestudio.mobile.local.PhoneModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Model, preset or phone voice, language and speed, remembered per project. */
@Composable fun VoicePicker(vm: StudioViewModel, choice: VoiceChoice?, change: (VoiceChoice) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    val l = LocalL10n.current
    val models = phone.models.filter { it.capability == "tts" || it.capability == "clone" }
    val voice = phone.voices.firstOrNull { it.id == choice?.voice }
    val model: PhoneModel? = models.firstOrNull { it.id == choice?.model }?.takeIf { (voice != null) == (it.capability == "clone") }
        ?: models.firstOrNull { it.capability == (if (voice != null) "clone" else "tts") && it.id in phone.installed && !it.system }
        ?: models.firstOrNull { it.capability == (if (voice != null) "clone" else "tts") }
    val language = choice?.language?.takeIf { it in model?.languages.orEmpty() } ?: if (voice != null) null else com.voicestudio.mobile.features.speak.preferredLanguage(model?.languages.orEmpty(), l.locale)
    val systemVoices by produceState(emptyList<String>(), model?.id, language) { value = if (model?.system == true) vm.phone.speech.voices(language) else emptyList() }
    val presets = when {
        model == null || voice != null -> emptyList()
        model.system -> systemVoices.mapIndexed { i, name -> Choice(i.toString(), tr("vozia.speak.system_voice", mapOf("number" to i + 1)), supporting = name) }.ifEmpty { listOf(Choice("0", tr("mobile.local.preset_voice", mapOf("number" to 1)))) }
        model.speakers.isNotEmpty() -> model.speakers.filter { it.language == null || language == null || it.language.equals(language, true) }.map { Choice(it.id.toString(), it.name) }
        else -> (0 until (model.option("num_speakers", "1").toIntOrNull()?.coerceIn(1, 128) ?: 1)).map { Choice(it.toString(), tr("mobile.local.preset_voice", mapOf("number" to it + 1))) }
    }
    val current = if (voice != null) "phone:" + voice.id else choice?.speaker?.toString()?.takeIf { id -> presets.any { it.id == id } } ?: presets.firstOrNull()?.id ?: "0"
    LaunchedEffect(model?.id, language, current, presets) {
        if (model != null) {
            val updated = VoiceChoice(model.id, if (voice != null) 0 else current.toIntOrNull() ?: 0, language, choice?.speed ?: 1f, voice?.id,
                voice?.name ?: presets.firstOrNull { it.id == current }?.let { if (model.system) it.supporting else it.title })
            if (updated != choice) change(updated)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
        ChoicePicker("mobile.local.model", model?.id.orEmpty(), models.filter { (voice != null) == (it.capability == "clone") }.map {
            Choice(it.id, if (it.system) tr("vozia.engine.system") else it.name, supporting = if (it.id in phone.installed) tr("mobile.local.installed") else tr("mobile.local.download"))
        }) { id -> change(VoiceChoice(id, 0, null, choice?.speed ?: 1f, voice?.id)) }
        ChoicePicker("mobile.speak.voice", current, presets + phone.voices.map { Choice("phone:" + it.id, it.name, supporting = tr("vozia.speak.cloned_voice")) }) { id ->
            if (id.startsWith("phone:")) change(VoiceChoice(models.firstOrNull { it.capability == "clone" }?.id ?: return@ChoicePicker, 0, null, 1f, id.removePrefix("phone:")))
            else change((choice ?: VoiceChoice(model?.id ?: return@ChoicePicker)).copy(model = if (voice != null) models.firstOrNull { it.capability == "tts" }?.id ?: return@ChoicePicker else model?.id ?: return@ChoicePicker,
                speaker = id.toIntOrNull() ?: 0, voice = null))
        }
        if (voice == null) ChoicePicker("mobile.speak.language", language.orEmpty(), model?.languages.orEmpty().map { Choice(it, languageName(it, l.locale)) }) { change((choice ?: VoiceChoice(model!!.id)).copy(language = it, speaker = 0)) }
        val speed = choice?.speed ?: 1f
        Text(tr("vozia.speak.speed", mapOf("speed" to "%.2f".format(speed))), style = MaterialTheme.typography.labelLarge)
        Slider(value = speed, onValueChange = { v -> choice?.let { change(it.copy(speed = (v * 20).roundToInt() / 20f)) } }, valueRange = .5f..2f, steps = 29, enabled = voice == null)
        model?.let { if (!phone.canRun(it, vm.phone.ramBytes)) com.voicestudio.mobile.features.models.MissingModelsCard(vm, "vozia.audiobook.title", listOf(it)) }
    }
}

@Composable fun AudiobooksScreen(vm: StudioViewModel, open: (String) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    Screen {
        ScreenTitle("vozia.audiobook.title")
        Text(tr("vozia.audiobook.intro"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        Field("vozia.audiobook.name", name, { name = it })
        Action("vozia.audiobook.create", name.isNotBlank()) {
            vm.phone.launch { val project = vm.phone.library.createProject(name, ProjectKind.AUDIOBOOK); name = ""; open(project.id) }
        }
        val books = phone.projects.filter { it.kind == ProjectKind.AUDIOBOOK }
        if (books.isEmpty()) EmptyState("vozia.audiobook.empty")
        books.forEach { book ->
            Surface(onClick = { open(book.id) }, shape = MaterialTheme.shapes.large, color = role("surface"), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(VSTheme.inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                    Icon(Icons.Default.Menu, null, tint = role("messageAction"))
                    Text(book.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable fun AudiobookScreen(vm: StudioViewModel, projectId: String, openProject: (String) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    val context = LocalContext.current
    val library = vm.phone.library
    val project = phone.projects.firstOrNull { it.id == projectId }
    val chapters by produceState(emptyList<Chapter>(), phone.revision, projectId) { value = library.chapters(projectId) }
    val items by produceState(emptyList<LibraryItem>(), phone.revision, projectId) { value = library.items(projectId) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Chapter?>(null) }
    var merge by rememberSaveable { mutableStateOf(true) }
    var choice by remember(projectId) { mutableStateOf<VoiceChoice?>(null) }
    val partTitle = tr("vozia.audiobook.part_title")
    LaunchedEffect(project?.settings) {
        if (choice == null) choice = runCatching { TaskJson.decodeFromString(AudiobookInput.serializer(), project?.settings ?: "{}").choice }.getOrNull()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.phone.launch {
            val name = mediaDisplayName(context, uri) ?: "book.txt"
            val parts = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(1 shl 16); var total = 0
                    while (true) { val n = input.read(chunk); if (n < 0) break; total += n; if (total > TextImport.MAX_BYTES) throw com.voicestudio.mobile.network.ClientFailure("vozia.audiobook.too_large"); buffer.write(chunk, 0, n) }
                    buffer.toByteArray()
                } ?: throw com.voicestudio.mobile.network.ClientFailure("vozia.audiobook.unreadable")
                TextImport.chapters(bytes, name) { partTitle.replace("{{number}}", it.toString()) }
            }
            if (parts.isEmpty()) throw com.voicestudio.mobile.network.ClientFailure("vozia.audiobook.unreadable")
            library.replaceChapters(projectId, parts)
        }
    }
    val task = phone.tasks.firstOrNull { it.projectId == projectId && it.kind == TaskKind.AUDIOBOOK && it.active }
    if (project == null) { Screen { InlineLoading() }; return }
    Screen {
        Text(project.name, style = MaterialTheme.typography.headlineMedium)
        Secondary("vozia.audiobook.files") { openProject(projectId) }
        StepHeading("vozia.audiobook.step_text", Icons.Default.Edit)
        Secondary("vozia.audiobook.import") { importer.launch(arrayOf("text/*", "application/epub+zip", "application/octet-stream")) }
        Field("vozia.audiobook.paste", pasted, { pasted = it }, multiline = true)
        Secondary("vozia.audiobook.split", pasted.isNotBlank()) {
            vm.phone.launch { library.replaceChapters(projectId, TextImport.split(pasted) { partTitle.replace("{{number}}", it.toString()) }); pasted = "" }
        }
        if (chapters.isEmpty()) EmptyState("vozia.audiobook.no_chapters")
        else Text(tr("vozia.audiobook.summary", mapOf("count" to chapters.size, "characters" to chapters.sumOf { it.text.length })), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        chapters.forEach { chapter ->
            Surface(onClick = { if (chapter.itemId == null) editing = chapter }, shape = MaterialTheme.shapes.small, color = role("surface"), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(VSTheme.inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                    Text("${chapter.position + 1}.", color = role("textMuted"))
                    Column(Modifier.weight(1f)) {
                        Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("vozia.audiobook.chapter_state_${chapter.state}") + " · " + tr("vozia.speak.characters", mapOf("count" to chapter.text.length)),
                            style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
                    }
                    if (chapter.state == "done") Icon(Icons.Default.Check, null, tint = role("messageAction"))
                }
            }
        }
        StepHeading("vozia.audiobook.step_voice", Icons.Default.Person)
        VoicePicker(vm, choice) { choice = it }
        Toggle("vozia.audiobook.merge", merge) { merge = it }
        val ready = choice?.let { c -> phone.models.firstOrNull { it.id == c.model }?.let { phone.canRun(it, vm.phone.ramBytes) } } == true
        if (task == null) {
            Action("vozia.audiobook.generate", chapters.isNotEmpty() && ready && chapters.any { it.itemId == null || merge }) {
                val selected = choice ?: return@Action
                vm.phone.launch {
                    val input = AudiobookInput(selected, merge)
                    val json = TaskJson.encodeToString(AudiobookInput.serializer(), input)
                    library.updateProjectSettings(projectId, json)
                    LibraryWorker.enqueue(context, library.createTask(TaskKind.AUDIOBOOK, projectId, project.name, json).id)
                }
            }
            if (chapters.any { it.itemId != null }) Secondary("vozia.audiobook.regenerate") { vm.phone.launch { library.resetChapters(projectId) } }
        } else TaskCard(vm, task)
        Text(tr("vozia.speak.background_hint"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        val outputs = items.filter { it.kind == ItemKind.AUDIOBOOK || it.kind == ItemKind.CHAPTER }.sortedWith(compareBy<LibraryItem> { it.kind != ItemKind.AUDIOBOOK }.thenBy { it.position })
        if (outputs.isNotEmpty()) StepHeading("vozia.audiobook.step_listen", Icons.Default.PlayArrow)
        outputs.forEach { ItemCard(vm, it, project.name) }
    }
    editing?.let { chapter ->
        var title by remember(chapter.id) { mutableStateOf(chapter.title) }
        var text by remember(chapter.id) { mutableStateOf(chapter.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(tr("vozia.audiobook.edit_chapter")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) { Field("vozia.audiobook.chapter_title", title, { title = it }); Field("vozia.audiobook.chapter_text", text, { text = it }, multiline = true) } },
            confirmButton = { TextButton(enabled = title.isNotBlank() && text.isNotBlank(), onClick = { editing = null; vm.phone.launch { library.updateChapter(chapter.id, title = title, text = text) } }) { Text(tr("common.save")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("common.cancel")) } })
    }
}
