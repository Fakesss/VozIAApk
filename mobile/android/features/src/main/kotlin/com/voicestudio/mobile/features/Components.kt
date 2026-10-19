package com.voicestudio.mobile.features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import com.voicestudio.mobile.designsystem.*
import com.voicestudio.mobile.l10n.L10n
import java.util.Locale

val LocalL10n = staticCompositionLocalOf<L10n> { error("L10n provider required") }
@Composable fun tr(key: String, params: Map<String, Any> = emptyMap()) = LocalL10n.current.t(key, params)

/** Clear focus as well as hiding the IME so it does not reappear after an action. */
@Composable fun rememberKeyboardDismiss(): () -> Unit {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    return remember(focus,keyboard) {{focus.clearFocus();keyboard?.hide()}}
}

/** Unconsumed background taps dismiss; field selection and scrolling keep their gestures. */
fun Modifier.dismissKeyboardOnTap(): Modifier = composed {
    // Resolve at the modifier's owner: a Dialog has its own focus manager.
    val dismiss=rememberKeyboardDismiss()
    pointerInput(dismiss) {detectTapGestures(onTap={dismiss()})}
}

@Composable fun Screen(content: @Composable ColumnScope.() -> Unit) {
    // Insets shrink the viewport, not the scrollable content. Every form shares this path.
    Column(Modifier.fillMaxSize().imePadding().dismissKeyboardOnTap().verticalScroll(rememberScrollState()).padding(VSTheme.inset), verticalArrangement = Arrangement.spacedBy(VSTheme.gap), content = content)
}
@Composable fun ScreenTitle(key: String) { Text(tr(key), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }) }
@Composable fun Heading(key: String) { Text(tr(key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
@Composable fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color=role("surface"), shape=MaterialTheme.shapes.large, border=BorderStroke(VSTheme.borderWidth,role("border"))) {
        Column(content=content)
    }
}
@Composable fun Action(key: String, enabled: Boolean = true, icon: ImageVector = actionIcon(key), onClick: () -> Unit) {
    val dismiss=rememberKeyboardDismiss()
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    Button(interactionSource=interaction, colors=ButtonDefaults.buttonColors(containerColor=role(if(pressed) "messageActionHover" else "messageAction"),contentColor=role("messageActionForeground")), onClick = {dismiss();onClick()}, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = VSTheme.touchTarget*1.25f), shape = MaterialTheme.shapes.small) { Icon(icon,contentDescription=null); Spacer(Modifier.width(VSTheme.gap)); Text(tr(key)) }
}
@Composable fun Secondary(key: String, enabled: Boolean = true, onClick: () -> Unit) {
    val dismiss=rememberKeyboardDismiss()
    OutlinedButton(onClick={dismiss();onClick()}, enabled=enabled, modifier=Modifier.heightIn(min=VSTheme.touchTarget), shape=MaterialTheme.shapes.small,
        border=BorderStroke(VSTheme.borderWidth,role("border")), colors=ButtonDefaults.outlinedButtonColors(containerColor=role("surface"),contentColor=role("text"))) { Icon(actionIcon(key),contentDescription=null);Spacer(Modifier.width(VSTheme.gap));Text(tr(key)) }
}
@Composable fun IconAction(key: String, icon: ImageVector, enabled: Boolean = true, params: Map<String,Any> = emptyMap(), onClick: () -> Unit) {
    val label=tr(key,params)
    val dismiss=rememberKeyboardDismiss()
    // Surface keeps a token radius; Material's default icon button is circular.
    Surface(onClick={dismiss();onClick()}, enabled=enabled, shape=MaterialTheme.shapes.small, color=role("surface"),
        modifier=Modifier.sizeIn(minWidth=VSTheme.touchTarget,minHeight=VSTheme.touchTarget).semantics {contentDescription=label;role=Role.Button}) {
        Box(contentAlignment=Alignment.Center) {Icon(icon,contentDescription=null,tint=role(if(enabled) "text" else "textMuted"))}
    }
}
@Composable fun Field(key: String, value: String, change: (String) -> Unit, secret: Boolean = false, multiline: Boolean = false) {
    val dismiss=rememberKeyboardDismiss()
    var focused by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth()) {
    OutlinedTextField(value = value, onValueChange = change, label = if(multiline) null else {{ Text(tr(key)) }}, placeholder = if(multiline) {{ Text(tr(key)) }} else null, modifier = Modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused},
        singleLine = !multiline, minLines = if(multiline) 7 else 1,
        visualTransformation = if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        // Multiline keeps Return for newlines; its explicit toolbar supplies Done.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = !secret, keyboardType = if(secret) KeyboardType.Password else KeyboardType.Text,
            imeAction=if(multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={dismiss()}),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor=role("focus"), unfocusedBorderColor=role("border"), focusedContainerColor=role("input"), unfocusedContainerColor=role("input")))
    if(multiline && focused) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        TextButton(onClick=dismiss,shape=MaterialTheme.shapes.small,modifier=Modifier.heightIn(min=VSTheme.touchTarget)) {Text(tr("mobile.keyboard.done"))}
    }
    }
}
@Composable private fun PickerRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val dismiss=rememberKeyboardDismiss()
    val description=tr("mobile.android.selection",mapOf("label" to tr(label),"value" to value))
    Surface(onClick={dismiss();onClick()},enabled=enabled,color=role("surface"),shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget).semantics {contentDescription=description}) {
        Row(Modifier.padding(VSTheme.inset),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Icon(rowIcon(label),contentDescription=null,tint=role("textMuted"))
            Text(tr(label),style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(value,Modifier.weight(1f),color=role("textMuted"),style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.End,maxLines=1,overflow=TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,contentDescription=null,tint=role("textMuted"))
        }
    }
}
@Composable fun Picker(label: String, value: String, choices: List<Pair<String, String>>, change: (String) -> Unit) =
    ChoicePicker(label,value,choices.map {Choice(it.first,it.second)},change=change)

@Composable fun ChoicePicker(label: String, value: String, choices: List<Choice>, compact: Boolean = false, change: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    if(compact) {
        val valueLabel=choices.firstOrNull {it.id==value}?.title ?: tr(label)
        val description=tr("mobile.android.selection",mapOf("label" to tr(label),"value" to valueLabel))
        AssistChip(onClick={open=true},enabled=choices.isNotEmpty(),label={Text(valueLabel,Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis)},modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget).semantics {contentDescription=description})
    } else PickerRow(label,choices.firstOrNull {it.id==value}?.title ?: tr("mobile.android.choose"),choices.isNotEmpty()) {open=true}
    if(open) AlertDialog(modifier=Modifier.dismissKeyboardOnTap(),shape=MaterialTheme.shapes.large,onDismissRequest={open=false}, title={Text(tr(label))}, containerColor=role("surfaceOverlay"),
        text={ Column(verticalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Field("mobile.android.search", query, {query=it})
            val matches=choices.filter {it.title.contains(query,true)}
            if(matches.isEmpty()) SearchEmpty(query)
            LazyColumn(Modifier.heightIn(max=(VSTokens.SPACING_GAP*20).dp)) {
                items(matches,key={it.id}) { choice ->
                    TextButton(shape=MaterialTheme.shapes.small,onClick={change(choice.id);open=false;query=""},enabled=choice.enabled,
                        colors=ButtonDefaults.textButtonColors(contentColor=role("text"),disabledContentColor=role("textMuted")),
                        modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget).semantics { selected = choice.id==value }) {
                        choice.swatch?.let {color ->
                            Box(Modifier.padding(end=VSTheme.gap).size(24.dp).background(Color(color),CircleShape).testTag("palette-swatch"))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(choice.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                            choice.supporting?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=role("textMuted"))}
                        }
                        if(choice.id==value) Icon(Icons.Default.Check,contentDescription=null,modifier=Modifier.padding(start=VSTheme.gap).testTag("choice-check"))
                    }
                }
            }
        }}, confirmButton={TextButton(shape=MaterialTheme.shapes.small,onClick={open=false}) {Text(tr("common.close"))}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun MultiPicker(label: String, selected: List<String>, choices: List<Pair<String,String>>, change: (List<String>) -> Unit) {
    var open by rememberSaveable {mutableStateOf(false)}
    var query by rememberSaveable {mutableStateOf("")}
    val dismiss=rememberKeyboardDismiss()
    Group {PickerRow(label,selected.mapNotNull {id -> choices.firstOrNull {it.first==id}?.second}.joinToString().ifBlank {tr("mobile.android.choose")},choices.isNotEmpty()) {open=true}}
    FlowRow(horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
        choices.filter {it.first in selected}.forEach {(id,name) ->
            val remove=tr("mobile.android.remove_selection",mapOf("name" to name))
            InputChip(shape=MaterialTheme.shapes.small,selected=true,onClick={dismiss();change(selected-id)}, label={Text(name)},
                trailingIcon={Icon(Icons.Default.Close,contentDescription=null)},modifier=Modifier.heightIn(min=VSTheme.touchTarget).semantics {contentDescription=remove})
        }
    }
    if(open) AlertDialog(modifier=Modifier.dismissKeyboardOnTap(),shape=MaterialTheme.shapes.large,onDismissRequest={open=false},containerColor=role("surfaceOverlay"),title={Text(tr(label))},text={
        Column(verticalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            val dismissSearch=rememberKeyboardDismiss()
            Field("mobile.android.search",query,{query=it})
            val matches=choices.filter {it.second.contains(query,true)}
            if(matches.isEmpty()) SearchEmpty(query)
            LazyColumn(Modifier.heightIn(max=(VSTokens.SPACING_GAP*20).dp)) {
                items(matches,key={it.first}) {(id,name) ->
                    Surface(onClick={dismissSearch();change(if(id in selected) selected-id else selected+id)},color=role("surfaceOverlay"),shape=MaterialTheme.shapes.small,
                        modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget).semantics {this.selected=id in selected}) {
                        Row(Modifier.padding(VSTheme.inset),verticalAlignment=Alignment.CenterVertically) {
                            Text(name,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                            Checkbox(checked=id in selected,onCheckedChange=null)
                        }
                    }
                }
            }
        }
    },confirmButton={TextButton(shape=MaterialTheme.shapes.small,onClick={open=false}) {Text(tr("common.close"))}})
}
@Composable fun Toggle(key: String, value: Boolean, change: (Boolean) -> Unit) {
    val label = tr(key)
    val dismiss=rememberKeyboardDismiss()
    Row(Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget).padding(horizontal=VSTheme.inset),verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
        Icon(rowIcon(key),contentDescription=null,tint=role("textMuted"))
        Text(label, Modifier.weight(1f))
        Switch(checked=value, onCheckedChange={dismiss();change(it)}, modifier=Modifier.semantics { contentDescription=label })
    }
}
fun languageName(code: String, locale: String): String = Locale.forLanguageTag(code).getDisplayName(Locale.forLanguageTag(locale))

/** Shared empty state for every searchable list. */
@Composable fun SearchEmpty(query: String) {
    Text(tr("projects.no_matches",mapOf("query" to query)),color=role("textMuted"),modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
}
@Composable fun InlineLoading() {
    Text(tr("common.loading"),color=role("textMuted"))
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable fun StepHeading(key: String, icon: ImageVector) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
        Icon(icon,contentDescription=null); Heading(key)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Info(label: String, body: String, row: Boolean = false, params: Map<String,Any> = emptyMap()) {
    var open by remember {mutableStateOf(false)}
    if(row) SettingsLink(label) {open=true} else IconAction(label,Icons.Default.Info) {open=true}
    if(open) ModalBottomSheet(onDismissRequest={open=false},containerColor=role("surfaceOverlay")) {
        Text(tr(body,params),Modifier.padding(VSTheme.inset).verticalScroll(rememberScrollState()))
    }
}
@Composable fun SettingsLink(label: String, value: String = "", onClick: ()->Unit) {
    Surface(onClick=onClick,color=role("surface"),shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget)) {
        Row(Modifier.padding(VSTheme.inset),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
            Icon(rowIcon(label),contentDescription=null)
            Text(tr(label),Modifier.weight(1f));if(value.isNotBlank()) Text(value,Modifier.weight(1f),color=role("textMuted"),maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.End)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,contentDescription=null)
        }
    }
}
@Composable fun MicControl(active: Boolean, ready: Boolean, tap: ()->Unit, hold: ()->Unit, release: ()->Unit) {
    val label=tr(if(active) "common.stop" else "mobile.simple.microphone")
    val holdLabel=tr("mobile.dictate.hold_to_talk")
    val currentTap by rememberUpdatedState(tap)
    val currentHold by rememberUpdatedState(hold)
    val currentRelease by rememberUpdatedState(release)
    val usable by rememberUpdatedState(ready||active)
    Box(Modifier.fillMaxWidth().padding(vertical=VSTheme.inset),contentAlignment=Alignment.Center) {
        Surface(color=role("messageAction"),contentColor=role("messageActionForeground"),shape=androidx.compose.foundation.shape.CircleShape,
            modifier=Modifier.size(112.dp).semantics {
                contentDescription=label;role=Role.Button
                if(!ready&&!active) disabled()
                onClick {if(ready||active) tap();true}
                onLongClick(label=holdLabel) {if(ready) hold();true}
            }.pointerInput(ready) {
                detectTapGestures(onTap={if(usable) currentTap()},onLongPress={if(ready) currentHold()},onPress={tryAwaitRelease();currentRelease()})
            }) {Box(contentAlignment=Alignment.Center) {Icon(if(active) Icons.Default.Close else VSIcons.Mic,contentDescription=null,modifier=Modifier.size(48.dp))}}
    }
}

fun actionIcon(key: String): ImageVector = when {
    key.contains("scan_qr") -> VSIcons.Qr
    key.startsWith("vozia.") && (key.contains("folder") || key.endsWith(".files") || key.endsWith(".open") && key.contains("audiobook")) -> VSIcons.Folder
    key.startsWith("vozia.") && (key.contains("clean")) -> Icons.Default.Delete
    key.startsWith("vozia.") && (key.endsWith(".new") || key.contains("create") || key.contains("import") || key.endsWith(".split")) -> Icons.Default.Add
    key.startsWith("vozia.") && (key.contains("rename") || key.contains("save_as") || key.endsWith(".copy")) -> Icons.Default.Edit
    key.startsWith("vozia.") && key.contains("export.open") -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    key.startsWith("vozia.") && key.contains("regenerate") -> Icons.Default.Refresh
    key.contains("clone")||key.contains("pick") -> Icons.Default.Add
    key.contains("cancel")||key.contains("abort")||key.contains("stop") -> Icons.Default.Close
    key.contains("record") -> VSIcons.Mic
    key.contains("delete") -> Icons.Default.Delete
    key.contains("share") -> Icons.Default.Share
    key.contains("download")||key.endsWith("save") -> VSIcons.Download
    key.contains("more")||key.contains("address") -> Icons.Default.KeyboardArrowDown
    key.contains("refresh")||key.contains("retry")||key.contains("resume") -> Icons.Default.Refresh
    else -> Icons.Default.PlayArrow
}
fun shortErrorKey(key: String): String = when {
    key.split('.').take(2)==listOf("mobile","simple") -> key
    key.contains("licence") -> "mobile.simple.licence_error"
    key.split('.').take(2)==listOf("mobile","local")||key.contains("model")||key.contains("licence") -> "mobile.simple.models_error"
    key.split('.').take(2)==listOf("mobile","connect")||key=="mobile.errors.offline" -> "mobile.simple.connection_error"
    else -> "mobile.simple.error"
}
/** Keep the compact title while preserving the localized cause and recovery step. */
@Composable fun ErrorMessage(key: String, params: Map<String,Any> = emptyMap(), modifier: Modifier = Modifier, color: Color = role("error")) {
    val l=LocalL10n.current
    val title=shortErrorKey(key)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(VSTheme.gap/2)) {
        Text(l.t(title),color=color,style=MaterialTheme.typography.titleSmall)
        if(key!=title&&l.has(key)) Text(l.t(key,params),color=color,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable fun EmptyState(key: String,icon: ImageVector = Icons.Default.Info) {
    Row(Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(VSTheme.gap)) {
        Icon(icon,contentDescription=null,tint=role("textMuted"));Text(tr(key),color=role("textMuted"))
    }
}

@Composable fun ConsentCheck(key: String, checked: Boolean, change: (Boolean)->Unit) {
    val label=tr(key)
    Row(Modifier.fillMaxWidth().heightIn(min=VSTheme.touchTarget),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange=change,modifier=Modifier.semantics {contentDescription=label})
        Text(label,Modifier.weight(1f))
    }
}
@Composable fun LocationBadge(phone: Boolean) {
    Surface(color=role("messageSurface"),shape=MaterialTheme.shapes.small) {
        Text(tr(if(phone) "mobile.simple.phone" else "mobile.simple.computer"),style=MaterialTheme.typography.labelSmall,
            color=role("textMuted"),modifier=Modifier.padding(horizontal=VSTheme.gap/2,vertical=VSTheme.gap/4))
    }
}

/** Semantic icons shared by settings and full-width picker rows. */
fun rowIcon(key: String): ImageVector = when {
    key.contains("library") || key.contains("export_folder") || key.contains("destination") -> VSIcons.Folder
    key.startsWith("vozia.task") -> Icons.Default.Refresh
    key.contains("connect") || key.contains("host") -> VSIcons.Link
    key.contains("model") -> VSIcons.Download
    key.endsWith("mode") -> VSIcons.Contrast
    key.contains("palette") -> VSIcons.Palette
    key.contains("language") -> VSIcons.Globe
    key.contains("notification") -> VSIcons.Bell
    key.contains("privacy") || key.contains("analytics") -> VSIcons.Shield
    key.contains("voice") -> Icons.Default.Person
    else -> Icons.Default.Info
}

/** Transcript scrolls independently; recording and sharing stay clear of the tab bar. */
@Composable fun DictationLayout(content: @Composable ColumnScope.()->Unit, controls: @Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().imePadding().dismissKeyboardOnTap()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(VSTheme.inset),
            verticalArrangement=Arrangement.spacedBy(VSTheme.gap),content=content)
        Column(Modifier.fillMaxWidth().padding(horizontal=VSTheme.inset).padding(bottom=VSTheme.inset),
            verticalArrangement=Arrangement.spacedBy(VSTheme.gap),content=controls)
    }
}
