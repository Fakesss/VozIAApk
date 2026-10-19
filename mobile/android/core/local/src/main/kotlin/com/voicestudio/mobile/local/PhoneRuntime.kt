package com.voicestudio.mobile.local

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/** UI and foreground jobs share model leases and licence withdrawal state. */
class PhoneRuntime private constructor(context: Context) {
    private val app=context.applicationContext
    val ramBytes=ActivityManager.MemoryInfo().also {app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}.totalMem
    @Suppress("DEPRECATION")
    val licence=LicenceGate(File(app.noBackupFilesDir,"model-licences.json"),app.packageManager.getPackageInfo(app.packageName,0).versionName.orEmpty())
    val downloads=ModelDownloads(File(app.noBackupFilesDir,"models"),licence,ramBytes) {
        val connectivity=app.getSystemService(ConnectivityManager::class.java)
        connectivity.activeNetwork?.let {connectivity.getNetworkCapabilities(it)}?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
    }
    val speech=SystemSpeech(app)
    // English dictionary espeak falls back to for foreign characters; the Piper pt-BR download lacks it.
    private val bundledEspeak=listOf("en_dict","lang/gmw/en","lang/gmw/en-US")
    val engine: LocalEngine=PhoneLocalEngine(downloads,speech,{models()}) {directory ->
        for(name in bundledEspeak) {
            val target=File(directory,name)
            if(target.exists()) continue
            target.parentFile!!.mkdirs()
            val pending=File(target.path+".tmp")
            app.assets.open("espeak-ng-data/$name").use {input -> pending.outputStream().use {input.copyTo(it)}}
            if(!pending.renameTo(target)) pending.delete()
        }
    }
    @Volatile private var catalogue: List<PhoneModel>?=null
    /** Bundled models.json plus the system voice; decoded once per process. */
    fun models(): List<PhoneModel> = catalogue ?: synchronized(this) {
        catalogue ?: (listOf(systemTtsModel(emptyList()))+app.assets.open("models.json").bufferedReader().use {ModelCatalog.decode(it.readText()).models}).also {catalogue=it}
    }
    fun model(id: String): PhoneModel = models().firstOrNull {it.id==id} ?: throw com.voicestudio.mobile.network.ClientFailure("mobile.local.model_required")
    companion object {
        // Only the application context survives; activities are never retained.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: PhoneRuntime?=null
        fun get(context: Context): PhoneRuntime {
            val application=context.applicationContext
            return instance?.takeIf {it.app===application} ?: synchronized(this) {
                instance?.takeIf {it.app===application} ?: PhoneRuntime(application).also {instance=it}
            }
        }
    }
}
