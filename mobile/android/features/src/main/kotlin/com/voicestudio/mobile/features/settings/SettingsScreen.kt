package com.voicestudio.mobile.features.settings

import android.Manifest
import androidx.compose.ui.Modifier
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.voicestudio.mobile.features.*
import com.voicestudio.mobile.designsystem.*
import com.voicestudio.mobile.l10n.L10n

@Composable fun SettingsScreen(vm:StudioViewModel,version:String,onConnect:()->Unit = {},onModels:()->Unit = {},onLibrary:()->Unit = {},onTasks:()->Unit = {}) {
    val prefs by vm.preferences.collectAsState()
    val state by vm.state.collectAsState()
    val context=LocalContext.current
    val l=LocalL10n.current
    var editUrl by remember {mutableStateOf<String?>(null)}
    var editName by remember {mutableStateOf("")}
    var licenses by remember {mutableStateOf(false)}
    val notification=permissionAction(Manifest.permission.POST_NOTIFICATIONS,"mobile.settings.notifications_help",vm::fail) {vm.preference {it.copy(notifications=true)}}
    Screen {
        ScreenTitle("mobile.tabs.settings")
        SettingsLink("mobile.tabs.connect",onClick=onConnect)
        SettingsLink("mobile.local.models",onClick=onModels)
        SettingsLink("vozia.library.title",onClick=onLibrary)
        SettingsLink("vozia.task.title",onClick=onTasks)
        StorageSettings(vm)
        if(state.connected) {
            Heading("mobile.settings.connection")
            SettingsLink("mobile.settings.connection",prefs.activeUrl.orEmpty(),onConnect)
            SettingsLink("mobile.connect.disconnect",onClick=vm::disconnect)
            prefs.hosts.forEach {host ->
                SettingsLink("mobile.android.host_label",host.label) {editUrl=host.url;editName=host.label}
                if(host.url!=prefs.activeUrl) {
                    Secondary("mobile.connect.connect",!state.busy) {vm.reconnect(host)}
                    Secondary("mobile.connect.forget",!state.busy) {vm.forget(host.url)}
                }
            }
        }
            Heading("mobile.settings.appearance")
            val palettes=paletteChoices(l)
            Group {
            Picker("mobile.settings.mode",prefs.mode,listOf("dark","light","system").map {it to l.t("mobile.settings.mode_$it")}) {value -> vm.preference {it.copy(mode=value)}}
            ChoicePicker("mobile.settings.light_palette",prefs.lightPalette,palettes.map {Choice(it.first,it.second,swatch=VSTheme.roles(it.first,false).getValue("messageAction"))}) {value -> vm.preference {it.copy(lightPalette=value)}}
            ChoicePicker("mobile.settings.dark_palette",prefs.darkPalette,palettes.map {Choice(it.first,it.second,swatch=VSTheme.roles(it.first,true).getValue("messageAction"))}) {value -> vm.preference {it.copy(darkPalette=value)}}
            }

            Heading("mobile.settings.language")
            Picker("mobile.settings.language",prefs.locale,listOf("system" to l.t("mobile.settings.mode_system"))+L10n.languages.map {it to languageName(it,it)}) {value -> vm.preference {it.copy(locale=value)}}
            Heading("mobile.settings.notifications")
            Group {Toggle("mobile.settings.notifications",prefs.notifications) {enabled -> if(enabled&&Build.VERSION.SDK_INT>=33) notification() else vm.preference {it.copy(notifications=enabled)}}}
        Heading("mobile.settings.privacy")
        Info("mobile.simple.privacy","mobile.local.privacy",row=true)
        Group {Toggle("mobile.simple.analytics",prefs.consent==true) {vm.consent(it)}}
        Heading("mobile.settings.about")
        SettingsLink("mobile.android.app_name",version) {licenses=true}
        Text(tr("vozia.about.fork"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
        SettingsLink("mobile.settings.licenses") {licenses=true}
        if(state.connected) SettingsLink("mobile.android.model_licenses") {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(vm.api!!.address.baseUrl.toString())))}
    }
    if(editUrl!=null) AlertDialog(onDismissRequest={editUrl=null},text={Field("mobile.android.host_label",editName,{editName=it})},confirmButton={TextButton(onClick={val url=editUrl;vm.preference {it.copy(hosts=it.hosts.map {h -> if(h.url==url) h.copy(label=editName) else h})};editUrl=null}) {Text(tr("common.save"))}},dismissButton={TextButton(onClick={editUrl=null}) {Text(tr("common.cancel"))}})
    if(licenses) AlertDialog(shape=MaterialTheme.shapes.large,onDismissRequest={licenses=false},title={Text(tr("mobile.settings.licenses"))},text={
        val content=remember {context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n") {name -> context.assets.open("licenses/$name").bufferedReader().use {it.readText()}}}
        Text(content,Modifier.verticalScroll(rememberScrollState()))
    },confirmButton={TextButton(shape=MaterialTheme.shapes.small,onClick={licenses=false}) {Text(tr("common.close"))}})
}

/** Private storage use, the export folder grant, and temporary-file cleanup. */
@Composable private fun StorageSettings(vm: StudioViewModel) {
    val phone by vm.phone.state.collectAsState()
    val context=LocalContext.current
    val library=vm.phone.library
    var tree by remember {mutableStateOf<Uri?>(null)}
    var cleaned by remember {mutableStateOf<Long?>(null)}
    LaunchedEffect(phone.revision) {tree=library.setting(com.voicestudio.mobile.features.library.EXPORT_TREE)?.let(Uri::parse)?.takeIf(vm.phone.exporter::folderGranted)}
    val pick=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) {uri ->
        if(uri!=null) vm.phone.launch {vm.phone.exporter.persistFolder(uri);library.putSetting(com.voicestudio.mobile.features.library.EXPORT_TREE,uri.toString())}
    }
    Heading("vozia.settings.storage")
    Group {
        Column(Modifier.padding(VSTheme.inset),verticalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Text(tr("vozia.settings.usage",mapOf("library" to android.text.format.Formatter.formatFileSize(context,phone.libraryBytes),"models" to android.text.format.Formatter.formatFileSize(context,phone.used))))
            Text(tr("vozia.settings.private_note"),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))
            SettingsLink("vozia.settings.export_folder",tree?.let(vm.phone.exporter::folderLabel) ?: tr("vozia.settings.export_folder_none")) {pick.launch(null)}
            if(tree!=null) Secondary("vozia.settings.export_folder_clear") {
                val current=tree
                vm.phone.launch {
                    current?.let {runCatching {context.contentResolver.releasePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}}
                    library.putSetting(com.voicestudio.mobile.features.library.EXPORT_TREE,null)
                }
            }
            Secondary("vozia.settings.clean_temp") {vm.phone.launch {cleaned=library.cleanTemporary()}}
            cleaned?.let {Text(tr("vozia.settings.cleaned",mapOf("size" to android.text.format.Formatter.formatFileSize(context,it))),style=MaterialTheme.typography.bodySmall,color=role("textMuted"))}
        }
    }
}
