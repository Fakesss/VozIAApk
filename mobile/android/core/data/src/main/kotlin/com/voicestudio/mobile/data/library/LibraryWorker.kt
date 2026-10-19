package com.voicestudio.mobile.data.library

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.voicestudio.mobile.data.SessionStore
import com.voicestudio.mobile.l10n.L10n
import com.voicestudio.mobile.local.EngineWait
import com.voicestudio.mobile.local.PhoneRuntime
import com.voicestudio.mobile.local.PhoneVoiceStore
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Runs one library task (speech, audiobook, transcription) as foreground work with
 * a progress notification and a Cancel action. The task row is the source of
 * truth: Cancel marks it cancelled before stopping the work; any other stop
 * (process death, system limits) leaves it queued so WorkManager resumes it from
 * its checkpoints.
 */
class LibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var progress = 0f
    private var stage = "vozia.task.running"
    private var title = ""

    private suspend fun l10n(): L10n {
        val prefs = runCatching { SessionStore(applicationContext).preferences.first() }.getOrNull()
        return L10n.load(applicationContext, prefs?.locale?.takeUnless { it == "system" } ?: java.util.Locale.getDefault().toLanguageTag())
    }
    private suspend fun notification(finished: Boolean = false, failed: Boolean = false): ForegroundInfo {
        val l = l10n()
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, l.t("vozia.task.channel"), NotificationManager.IMPORTANCE_LOW))
        // Tapping the notification opens Tasks.
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra(ROUTE_EXTRA, "tasks")?.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent = launch?.let { PendingIntent.getActivity(applicationContext, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val text = l.t(if (failed) "vozia.task.failed" else if (finished) "vozia.task.done" else stage)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifEmpty { l.t("vozia.task.channel") }).setContentText(text).setContentIntent(intent)
            .setOngoing(!finished).setAutoCancel(finished).setOnlyAlertOnce(true)
            .setProgress(if (finished) 0 else 100, (progress * 100).toInt(), false)
        if (!finished) builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, l.t("common.cancel"), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(id.hashCode(), builder.build(), type)
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = notification()
    /** Android may refuse foreground promotion (background start limits); the work still runs. */
    private suspend fun promote() { try { setForeground(notification()) } catch (e: CancellationException) { throw e } catch (_: Exception) {} }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK) ?: return Result.failure()
        val library = Library.get(applicationContext)
        val task = library.task(taskId) ?: return Result.failure()
        if (!task.active) return Result.success()
        title = task.title
        // Each start counts, including restarts after the process died; a task that keeps
        // killing the app (a native crash, memory) fails with a message instead of looping.
        if (runAttemptCount >= MAX_ATTEMPTS) {
            library.updateTask(taskId, state = TaskState.FAILED, stage = "vozia.task.failed", error = "vozia.task.too_many_attempts")
            notifyFinished(failed = true)
            return Result.failure(workDataOf(KEY_TASK to taskId, "error" to "vozia.task.too_many_attempts"))
        }
        promote()
        library.updateTask(taskId, state = TaskState.RUNNING, stage = "vozia.task.running", clearError = true)
        val runtime = PhoneRuntime.get(applicationContext)
        val jobs = LibraryJobs(library, RuntimeJobEngine(runtime, PhoneVoiceStore(File(applicationContext.noBackupFilesDir, "voices"))))
        var lastReport = 0L
        // While another job holds the model lane, the task says so instead of sitting at its last stage.
        val wait = EngineWait { waiting ->
            val key = if (waiting) "vozia.task.waiting_engine" else stage
            library.updateTask(taskId, stage = key)
            val shown = stage; stage = key
            promote()
            stage = shown
        }
        return try {
            val result = withContext(wait) { jobs.run(task) { value, key ->
                progress = value; stage = key
                val now = System.currentTimeMillis()
                if (now - lastReport > 700 || value >= 1f) {
                    lastReport = now
                    library.updateTask(taskId, progress = value, stage = key)
                    setProgress(workDataOf("progress" to value, "stage" to key))
                    promote()
                }
            } }
            library.updateTask(taskId, state = TaskState.SUCCEEDED, progress = 1f, stage = "vozia.task.done", resultItem = result.itemId)
            notifyFinished(failed = false)
            Result.success(workDataOf(KEY_TASK to taskId))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // The notification's Cancel action stops the work directly; record it as a cancel, not a pause.
                val reason = if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED
                when {
                    library.task(taskId)?.state == TaskState.CANCELLED -> {}
                    reason == WorkInfo.STOP_REASON_CANCELLED_BY_APP -> library.updateTask(taskId, state = TaskState.CANCELLED, stage = "vozia.task.cancelled")
                    reason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> library.updateTask(taskId, state = TaskState.QUEUED, stage = "vozia.task.paused_by_android")
                    else -> library.updateTask(taskId, state = TaskState.QUEUED, stage = "vozia.task.interrupted")
                }
            }
            throw e
        } catch (e: Exception) {
            val failure = e as? ClientFailure ?: ClientFailure(if (com.voicestudio.mobile.local.isNoSpace(e)) "mobile.local.storage_required" else "vozia.task.failed")
            withContext(NonCancellable) {
                library.updateTask(taskId, state = TaskState.FAILED, stage = "vozia.task.failed", error = failure.key,
                    errorParams = Json.encodeToString(failure.params.mapValues { it.value.toString() }))
                notifyFinished(failed = true)
            }
            Result.failure(workDataOf(KEY_TASK to taskId, "error" to failure.key))
        }
    }

    private suspend fun notifyFinished(failed: Boolean) {
        val prefs = runCatching { SessionStore(applicationContext).preferences.first() }.getOrNull() ?: return
        if (prefs.notifications && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
            runCatching { NotificationManagerCompat.from(applicationContext).notify(id.hashCode() + 1, notification(true, failed).notification) }
    }

    companion object {
        const val TAG = "voicestudio.library"
        const val KEY_TASK = "task"
        /** Launch-intent extra naming the screen to open (MainActivity reads it). */
        const val ROUTE_EXTRA = "vozia.route"
        private const val CHANNEL = "library-tasks"
        private const val MAX_ATTEMPTS = 6
        private fun unique(taskId: String) = "library:$taskId"

        /** One task runs at a time; native inference is serialized anyway and this bounds memory. */
        fun enqueue(context: Context, taskId: String) {
            val request = OneTimeWorkRequestBuilder<LibraryWorker>().setInputData(workDataOf(KEY_TASK to taskId))
                .addTag(TAG).addTag("task:$taskId").build()
            WorkManager.getInstance(context).enqueueUniqueWork(unique(taskId), ExistingWorkPolicy.KEEP, request)
        }
        suspend fun cancel(context: Context, library: Library, taskId: String) {
            library.updateTask(taskId, state = TaskState.CANCELLED, stage = "vozia.task.cancelled")
            WorkManager.getInstance(context).cancelUniqueWork(unique(taskId))
        }
        suspend fun retry(context: Context, library: Library, taskId: String) {
            library.updateTask(taskId, state = TaskState.QUEUED, stage = "vozia.task.queued", progress = 0f, clearError = true)
            enqueue(context, taskId)
        }
        /** After process start: finish interrupted writes and re-enqueue every waiting task. */
        suspend fun resumeAll(context: Context, library: Library) {
            library.recover()
            resumeQueued(context, library)
        }
        /** Gives every waiting task a work request again; tasks that still have one keep it. */
        suspend fun resumeQueued(context: Context, library: Library) {
            library.tasks().filter { it.state == TaskState.QUEUED }.forEach { enqueue(context, it.id) }
        }
    }
}
