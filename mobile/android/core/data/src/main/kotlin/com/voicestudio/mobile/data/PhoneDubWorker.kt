package com.voicestudio.mobile.data

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
import com.voicestudio.mobile.l10n.L10n
import com.voicestudio.mobile.local.*
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import java.io.File

/** Durable foreground work has no connectivity constraint and never calls a host. */
class PhoneDubWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    private val jobs=PhoneDubStore(File(context.noBackupFilesDir,"dubs"))
    private var progress=DubProgress("mobile.android.stage_prepare",0)
    private suspend fun notification(finished: Boolean=false): ForegroundInfo {
        val prefs=SessionStore(applicationContext).preferences.first()
        val l=L10n.load(applicationContext,prefs.locale.takeUnless {it=="system"} ?: java.util.Locale.getDefault().toLanguageTag())
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,l.t("mobile.tabs.dub"),NotificationManager.IMPORTANCE_LOW))
        val launch=applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra(com.voicestudio.mobile.data.library.LibraryWorker.ROUTE_EXTRA,"dub")?.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent=launch?.let {PendingIntent.getActivity(applicationContext,2,it,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)}
        val notification=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(l.t("mobile.tabs.dub")).setContentText(l.t(if(finished) "mobile.android.stage_done" else progress.stage))
            .setContentIntent(intent).setOngoing(!finished).setAutoCancel(finished)
            .setProgress(if(finished) 0 else 100,progress.percent,false)
        if(!finished) notification.addAction(android.R.drawable.ic_menu_close_clear_cancel,l.t("common.cancel"),WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        val type=if(Build.VERSION.SDK_INT>=35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(id.hashCode(),notification.build(),type)
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = notification()
    /** Android may refuse foreground promotion when the work restarts in the background; the dub still runs. */
    private suspend fun promote() {try {setForeground(notification())} catch(e:CancellationException) {throw e} catch(_:Exception) {}}
    override suspend fun doWork(): Result {
        val job=inputData.getString("job") ?: return Result.failure()
        try {
            promote()
            val runtime=PhoneRuntime.get(applicationContext)
            val request=jobs.request(job)
            val catalog=withContext(Dispatchers.IO) {applicationContext.assets.open("models.json").bufferedReader().use {ModelCatalog.decode(it.readText())}}
            val models=request.models.map {pin ->catalog.models.firstOrNull {it.id==pin.id} ?: throw ClientFailure("mobile.local.model_required")}
            val authorize={models.forEach {model ->
                runtime.licence.requireAccepted(model)
                if(!model.fits(runtime.ramBytes)) throw ClientFailure("mobile.local.ram_required",mapOf("ram" to model.minRamGb.toString()))
                if(!runtime.downloads.installed(model)) throw ClientFailure("mobile.local.model_required")
            }}
            coroutineScope {
                val updates=Channel<DubProgress>(Channel.CONFLATED)
                val reporter=launch {
                    for(value in updates) {
                        progress=value
                        setProgress(workDataOf("stage" to value.stage,"percent" to value.percent,"job" to job))
                        promote()
                    }
                }
                var last=progress
                // Shows when the dub waits for another job's model instead of looking frozen.
                val wait=EngineWait {waiting ->updates.trySend(if(waiting) last.copy(stage="mobile.android.stage_waiting") else last)}
                try {withContext(wait) {PhoneDubPipeline(jobs,runtime.engine,authorize=authorize).run(job,models) {last=it;updates.trySend(it)}}}
                finally {updates.close()}
                reporter.join()
            }
            val prefs=SessionStore(applicationContext).preferences.first()
            if(prefs.notifications&&(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED))
                NotificationManagerCompat.from(applicationContext).notify(id.hashCode()+1,notification(true).notification)
            return Result.success(workDataOf("job" to job,"stage" to "mobile.android.stage_done"))
        } catch(e:CancellationException) {
            // Only a user's cancel reads as cancelled; Android's stops are resumed by WorkManager.
            val cancelled=Build.VERSION.SDK_INT<31||stopReason==WorkInfo.STOP_REASON_CANCELLED_BY_APP
            withContext(NonCancellable) {runCatching {val checkpoint=jobs.checkpoint(job);if(!checkpoint.done&&cancelled) jobs.save(job,checkpoint.copy(stage="mobile.simple.cancelled"))}}
            throw e
        } catch(e:Exception) {
            val key=(e as? ClientFailure)?.key ?: "mobile.simple.dub_failed"
            val params=(e as? ClientFailure)?.params.orEmpty().mapValues {it.value.toString()}
            withContext(NonCancellable) {runCatching {val checkpoint=jobs.checkpoint(job);jobs.save(job,checkpoint.copy(stage=key,errorParams=params))}}
            return Result.failure(workDataOf("job" to job,"error" to key,"error_params" to kotlinx.serialization.json.Json.encodeToString(params)))
        }
    }
    companion object {
        const val TAG="voicestudio.phone-dub"
        private const val CHANNEL="phone-dub"
        fun enqueue(context: Context,job: String) {
            val request=OneTimeWorkRequestBuilder<PhoneDubWorker>().setInputData(workDataOf("job" to job))
                .addTag(TAG).addTag("phone-dub:$job").build()
            WorkManager.getInstance(context).enqueueUniqueWork("phone-dub:$job",ExistingWorkPolicy.KEEP,request)
        }
    }
}
