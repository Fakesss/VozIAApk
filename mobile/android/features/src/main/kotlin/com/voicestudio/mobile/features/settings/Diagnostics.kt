package com.voicestudio.mobile.features.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.voicestudio.mobile.data.library.TaskJson
import com.voicestudio.mobile.data.library.SpeechInput
import com.voicestudio.mobile.designsystem.*
import com.voicestudio.mobile.features.*
import com.voicestudio.mobile.local.nativeCpuVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** True when Android may pause this app's background work to save battery. */
fun batteryRestricted(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Asks Android to let long tasks keep running with the screen off; falls back to the settings list. */
@SuppressLint("BatteryLife")
fun requestUnrestrictedBattery(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/** Shown where long tasks run: Android's battery saver can pause them while the phone sleeps. */
@Composable fun BatteryHint() {
    val context = LocalContext.current
    var restricted by remember { mutableStateOf(batteryRestricted(context)) }
    LifecycleResumeEffect(Unit) { restricted = batteryRestricted(context); onPauseOrDispose {} }
    if (restricted) Group {
        Column(Modifier.padding(VSTheme.inset), verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
            Text(tr("vozia.battery.hint"), style = MaterialTheme.typography.bodySmall)
            Secondary("vozia.battery.allow") { requestUnrestrictedBattery(context) }
        }
    }
}

/** Battery, CPU acceleration and a copyable report of the device and recent tasks. */
@Composable fun PerformanceSettings(vm: StudioViewModel, version: String) {
    val context = LocalContext.current
    var restricted by remember { mutableStateOf(batteryRestricted(context)) }
    var copied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { restricted = batteryRestricted(context); onPauseOrDispose {} }
    Heading("vozia.performance.title")
    Group {
        Column(Modifier.padding(VSTheme.inset), verticalArrangement = Arrangement.spacedBy(VSTheme.gap)) {
            Text(tr(if (restricted) "vozia.battery.restricted" else "vozia.battery.unrestricted"))
            if (restricted) Secondary("vozia.battery.allow") { requestUnrestrictedBattery(context) }
            Text(tr("vozia.performance.cpu", mapOf("variant" to nativeCpuVariant(), "cores" to Runtime.getRuntime().availableProcessors())),
                style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
            Secondary("vozia.performance.copy_report") {
                vm.phone.launch {
                    val report = diagnosticReport(context, vm, version)
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoiceStudio VozIA", report))
                    copied = true
                }
            }
            if (copied) Text(tr("vozia.performance.copied"), style = MaterialTheme.typography.bodySmall, color = role("textMuted"))
        }
    }
}

/** Plain-text report for support: device, acceleration, models and the last tasks. No texts or audio are included. */
suspend fun diagnosticReport(context: Context, vm: StudioViewModel, version: String): String {
    val phone = vm.phone.state.value
    val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    val tasks = vm.phone.library.tasks(limit = 30)
    return buildString {
        appendLine("VoiceStudio VozIA $version")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}; cores: ${Runtime.getRuntime().availableProcessors()}; CPU build: ${nativeCpuVariant()}")
        appendLine("RAM: ${memory.totalMem / 1_000_000} MB total, ${memory.availMem / 1_000_000} MB free; low memory: ${memory.lowMemory}")
        appendLine("Battery restricted: ${batteryRestricted(context)}; storage free: ${context.filesDir.usableSpace / 1_000_000} MB")
        appendLine("Installed models: ${phone.installed.sorted().joinToString().ifEmpty { "-" }}")
        appendLine("Report time: ${time.format(Date())}")
        appendLine()
        appendLine("Recent tasks:")
        tasks.forEach { task ->
            val model = runCatching { TaskJson.decodeFromString(SpeechInput.serializer(), task.input).choice.let { it.model + if (it.voice != null) " (cloned voice)" else "" } }.getOrNull()
            val result = task.resultItem?.let { if (vm.phone.library.item(it) != null) " result=present" else " result=deleted" } ?: ""
            appendLine("- ${task.kind} ${task.state} ${(task.progress * 100).toInt()}% stage=${task.stage}${model?.let { " model=$it" } ?: ""}$result" +
                " created=${time.format(Date(task.createdAt))} updated=${time.format(Date(task.updatedAt))}" +
                " (${(task.updatedAt - task.createdAt) / 1000}s)${task.error?.let { " error=$it" } ?: ""}")
        }
        val dubs = phone.dubs
        if (dubs.isNotEmpty()) {
            appendLine()
            appendLine("Dubbing jobs:")
            dubs.forEach { appendLine("- ${it.checkpoint.stage} ${it.checkpoint.percent}% segments=${it.checkpoint.segments.size} done=${it.checkpoint.done} created=${time.format(Date(it.request.createdAt))}") }
        }
    }
}
