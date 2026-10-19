package com.voicestudio.mobile.features.library

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.voicestudio.mobile.data.library.*
import com.voicestudio.mobile.designsystem.VSTheme
import com.voicestudio.mobile.designsystem.role
import com.voicestudio.mobile.features.*
import kotlinx.coroutines.launch

fun projectKindKey(kind: String) = when (kind) {
    ProjectKind.AUDIOBOOK -> "vozia.project.kind_audiobook"; ProjectKind.TRANSCRIPTION -> "vozia.project.kind_transcription"
    ProjectKind.DUB -> "vozia.project.kind_dub"; else -> "vozia.project.kind_general"
}
fun itemKindKey(kind: String) = "vozia.item.$kind"
fun taskStateKey(state: String) = "vozia.task.state_$state"

/** Every project with its kind, size and last change; new general projects start here. */
@Composable fun LibraryScreen(vm: StudioViewModel, open: (Project) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    val context = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val counts by produceState(emptyMap<String, Int>(), phone.revision) {
        value = vm.phone.library.items().groupingBy { it.projectId }.eachCount()
    }
    Screen {
        ScreenTitle("vozia.library.title")
        Text(tr("vozia.library.storage", mapOf("size" to Formatter.formatFileSize(context, phone.libraryBytes))),
            style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        Text(tr("vozia.library.where"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        Secondary("vozia.project.new") { creating = true }
        if (phone.projects.isEmpty()) EmptyState("vozia.library.empty")
        phone.projects.forEach { project ->
            Surface(onClick = { open(project) }, shape = MaterialTheme.shapes.large, color = role("surface"), modifier = Modifier.fillMaxWidth().testTag("library.project.${project.name}")) {
                Row(Modifier.padding(VSTheme.inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                    Icon(when (project.kind) { ProjectKind.AUDIOBOOK -> Icons.Default.Menu; ProjectKind.TRANSCRIPTION -> Icons.Default.Edit; else -> VSIcons.Folder }, null, tint = role("messageAction"))
                    Column(Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr(projectKindKey(project.kind)) + " · " + tr("vozia.library.items", mapOf("count" to (counts[project.id] ?: 0))) + " · " +
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(project.updatedAt)),
                            style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text(tr("vozia.project.new")) },
        text = { Field("vozia.project.name", name, { name = it }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            creating = false
            vm.phone.launch { open(vm.phone.library.createProject(name, ProjectKind.GENERAL)); name = "" }
        }) { Text(tr("common.save")) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("common.cancel")) } })
}

/** A project's media, transcripts and tasks; deleting explains exactly what goes away. */
@Composable fun ProjectScreen(vm: StudioViewModel, projectId: String, deleted: () -> Unit, openAudiobook: (String) -> Unit, openTranscription: (String) -> Unit) {
    val phone by vm.phone.state.collectAsState()
    val project = phone.projects.firstOrNull { it.id == projectId }
    val items by produceState(emptyList<LibraryItem>(), phone.revision, projectId) { value = vm.phone.library.items(projectId) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(project?.name) { mutableStateOf(project?.name.orEmpty()) }
    var confirm by remember { mutableStateOf<DeletionReport?>(null) }
    val context = LocalContext.current
    if (project == null) { Screen { InlineLoading() }; return }
    Screen {
        Text(project.name, style = MaterialTheme.typography.headlineMedium)
        Text(tr(projectKindKey(project.kind)), color = role("textMuted"))
        Row(horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
            if (project.kind == ProjectKind.AUDIOBOOK) Secondary("vozia.audiobook.open") { openAudiobook(project.id) }
            if (project.kind == ProjectKind.TRANSCRIPTION) Secondary("vozia.transcribe.open") { openTranscription(project.id) }
            Secondary("vozia.project.rename") { renaming = true }
            Secondary("common.delete") { vm.phone.launch { confirm = vm.phone.library.deletionPreview(project.id) } }
        }
        phone.tasks.filter { it.projectId == project.id }.take(5).forEach { TaskCard(vm, it) }
        if (items.isEmpty()) EmptyState("vozia.project.empty")
        items.sortedWith(compareBy<LibraryItem> { it.kind != ItemKind.AUDIOBOOK }.thenBy { it.position }.thenByDescending { it.createdAt }).forEach { ItemCard(vm, it, project.name) }
    }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text(tr("vozia.project.rename")) },
        text = { Field("vozia.project.name", name, { name = it }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; vm.phone.launch { vm.phone.library.renameProject(project.id, name) } }) { Text(tr("common.save")) } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("common.cancel")) } })
    confirm?.let { report ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(tr("vozia.project.delete_title", mapOf("name" to project.name))) },
            text = { Text(tr("vozia.project.delete_body", mapOf("count" to report.items, "size" to Formatter.formatFileSize(context, report.bytes), "exports" to report.exportedCopies))) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.phone.launch { vm.phone.library.deleteProject(project.id); deleted() } }) { Text(tr("common.delete"), color = role("error")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("common.cancel")) } })
    }
}

/** One stored result: play or read it, see where its copies went, export, rename or delete. */
@Composable fun ItemCard(vm: StudioViewModel, item: LibraryItem, folder: String?) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val phone by vm.phone.state.collectAsState()
    val exports by produceState(emptyList<ExportRecord>(), phone.revision, item.id) { value = vm.phone.library.exports(item.id) }
    val context = LocalContext.current
    Group {
        Column(Modifier.fillMaxWidth().padding(VSTheme.inset), verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(tr(itemKindKey(item.kind)), item.durationMs.takeIf { it > 0 }?.let(::formatDuration), Formatter.formatFileSize(context, item.size),
                        item.voice, item.language).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
                }
                if (item.mime?.startsWith("audio/") == true || item.mime?.startsWith("video/") == true) IconAction("player.play", Icons.Default.PlayArrow) { vm.phone.play(item); expanded = true }
                IconAction(if (expanded) "vozia.item.collapse" else "vozia.item.expand", if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown) { expanded = !expanded }
            }
            if (expanded) {
                if (phone.audioItem?.id == item.id) phone.audio?.let { LocalAudioPlayer(it, vm::fail) }
                if (item.kind == ItemKind.TRANSCRIPT || item.kind == ItemKind.SUBTITLE) {
                    val content by produceState("", item.id) { value = runCatching { vm.phone.library.file(item)?.readText().orEmpty() }.getOrDefault("") }
                    SelectionContainer { Text(content.take(20_000), style = MaterialTheme.typography.bodyMedium) }
                    Secondary("vozia.item.copy") { (context.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(android.content.ClipData.newPlainText(item.title, content)) }
                } else if (item.text.isNotBlank()) Text(item.text.take(600), style = MaterialTheme.typography.bodySmall, color = role("textMuted"), maxLines = 6, overflow = TextOverflow.Ellipsis)
                ExportActions(vm, item, folder)
                exports.forEach { Text(tr("vozia.export.history", mapOf("location" to it.location, "time" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.exportedAt)))),
                    style = MaterialTheme.typography.bodySmall, color = role("textMuted")) }
                Secondary("common.delete") { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(tr("vozia.item.delete_title")) },
        text = { Text(tr("vozia.item.delete_body", mapOf("exports" to exports.size))) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.phone.deleteGeneration(item) }) { Text(tr("common.delete"), color = role("error")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("common.cancel")) } })
}

@Composable fun TaskCard(vm: StudioViewModel, task: TaskRecord) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Group {
        Column(Modifier.fillMaxWidth().padding(VSTheme.inset).testTag("task.${task.id}"), verticalArrangement = Arrangement.spacedBy(VSTheme.gap / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("vozia.task.kind_${task.kind}") + " · " + tr(taskStateKey(task.state)), style = MaterialTheme.typography.bodySmall,
                        color = role(if (task.state == TaskState.FAILED) "error" else "textMuted"))
                }
                when {
                    task.active -> IconAction("common.cancel", Icons.Default.Close) { scope.launch { LibraryWorker.cancel(context, vm.phone.library, task.id) } }
                    task.state == TaskState.FAILED || task.state == TaskState.CANCELLED -> IconAction("vozia.task.retry", Icons.Default.Refresh) { scope.launch { LibraryWorker.retry(context, vm.phone.library, task.id) } }
                }
                if (!task.active) IconAction("common.delete", Icons.Default.Delete) { vm.phone.launch { vm.phone.library.deleteTask(task.id) } }
            }
            if (task.active) {
                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                Text(tr(task.stage) + " · " + (task.progress * 100).toInt() + "%", style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
            }
            task.error?.let { ErrorMessage(it) }
        }
    }
}

@Composable fun TasksScreen(vm: StudioViewModel) {
    val phone by vm.phone.state.collectAsState()
    Screen {
        ScreenTitle("vozia.task.title")
        Text(tr("vozia.task.hint"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        if (phone.tasks.isEmpty()) EmptyState("vozia.task.empty")
        phone.tasks.forEach { TaskCard(vm, it) }
    }
}
