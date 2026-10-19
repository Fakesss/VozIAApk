package com.voicestudio.mobile.features.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.voicestudio.mobile.data.library.LibraryItem
import com.voicestudio.mobile.designsystem.VSTheme
import com.voicestudio.mobile.designsystem.role
import com.voicestudio.mobile.features.*
import kotlinx.coroutines.launch

const val EXPORT_TREE = "export_tree"
const val EXPORT_FORMAT = "export_format"

/**
 * Every result can leave the app: public Downloads/VoiceStudio/<project>, the folder
 * chosen in Settings, or any place picked with "Save as". The saved location stays
 * visible and the copy can be opened in another app straight away.
 */
@Composable fun ExportActions(vm: StudioViewModel, item: LibraryItem, folder: String?) {
    val phone by vm.phone.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = vm.phone.library
    val exporter = vm.phone.exporter
    var busy by remember(item.id) { mutableStateOf(false) }
    var location by remember(item.id) { mutableStateOf<String?>(null) }
    var lastUri by remember(item.id) { mutableStateOf<Uri?>(null) }
    var tree by remember { mutableStateOf<Uri?>(null) }
    var format by rememberSaveable { mutableStateOf("wav") }
    val audio = item.mime == "audio/wav"
    LaunchedEffect(item.id, phone.revision) {
        tree = library.setting(EXPORT_TREE)?.let(Uri::parse)?.takeIf(exporter::folderGranted)
        library.setting(EXPORT_FORMAT)?.takeIf { it in setOf("wav", "m4a") }?.let { if (format == "wav") format = it }
        library.exports(item.id).firstOrNull()?.let { location = it.location; lastUri = Uri.parse(it.uri) }
    }
    fun run(block: suspend () -> com.voicestudio.mobile.data.library.ExportResult) {
        if (busy) return
        busy = true
        scope.launch {
            try { val result = block(); location = result.location; lastUri = result.uri }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { vm.fail(e) }
            finally { busy = false }
        }
    }
    val effective = if (audio) format else null
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run {
            exporter.persistFolder(uri); library.putSetting(EXPORT_TREE, uri.toString()); tree = uri
            exporter.toFolder(item, uri, effective)
        }
    }
    val suggestion = exporter.suggestion(item, effective)
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(suggestion.second)) { uri ->
        if (uri != null) run { exporter.toDocument(item, uri, effective) }
    }
    Group {
        Column(Modifier.fillMaxWidth().padding(VSTheme.inset), verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
            Heading("vozia.export.title")
            if (audio) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("wav" to "vozia.export.wav", "m4a" to "vozia.export.m4a").forEachIndexed { index, (id, key) ->
                    SegmentedButton(selected = format == id, onClick = { format = id; scope.launch { library.putSetting(EXPORT_FORMAT, id) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(tr(key)) }
                }
            }
            Action("vozia.export.downloads", !busy) { run { exporter.toDownloads(item, folder, effective) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                Box(Modifier.weight(1f)) { Secondary("vozia.export.folder", !busy) { val current = tree; if (current != null) run { exporter.toFolder(item, current, effective) } else pickFolder.launch(null) } }
                Box(Modifier.weight(1f)) { Secondary("vozia.export.save_as", !busy) { saveAs.launch(suggestion.first) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
                Box(Modifier.weight(1f)) { Secondary("mobile.speak.share", !busy) {
                    scope.launch { try { library.file(item)?.let { shareLocalFile(context, it, item.mime ?: "application/octet-stream") } } catch (e: Exception) { vm.fail(e) } }
                } }
                lastUri?.let { uri -> Box(Modifier.weight(1f)) { Secondary("vozia.export.open", !busy) {
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: item.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    } catch (e: Exception) { vm.fail(com.voicestudio.mobile.network.ClientFailure("vozia.export.no_viewer")) }
                } } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            location?.let {
                Text(tr("vozia.export.saved_at", mapOf("location" to it)), style = MaterialTheme.typography.bodySmall, color = role("textMuted"),
                    modifier = Modifier.testTag("export.location").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
