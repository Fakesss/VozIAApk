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
    // espeak-ng reads its voice list once per process, so the shared folder must hold every
    // language before the first espeak voice speaks: the app bundles the catalogue's espeak
    // data (the same files the models ship; checked by AssetsTest) and copies it on first use.
    private val engineSettings=app.getSharedPreferences("vozia-engine",Context.MODE_PRIVATE)
    /** Fast cloning: OmniVoice decodes in 16 steps instead of 32 (about twice as fast, slightly less faithful). */
    var fastClone: Boolean
        get()=engineSettings.getBoolean("fast_clone",false)
        set(value) {engineSettings.edit().putBoolean("fast_clone",value).apply()}
    val engine: LocalEngine=PhoneLocalEngine(downloads,speech,{models()},{directory -> copyBundledEspeak(directory)}) {if(fastClone) 16 else 0}
    private fun copyBundledEspeak(directory: File) {
        val stamp=File(directory,".bundled")
        val version=app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime.toString()
        if(stamp.takeIf {it.exists()}?.readText()==version) return
        fun copy(asset: String, target: File) {
            val children=app.assets.list(asset).orEmpty()
            if(children.isNotEmpty()) {children.forEach {copy("$asset/$it",File(target,it))};return}
            target.parentFile!!.mkdirs()
            val pending=File(target.path+".tmp")
            app.assets.open(asset).use {input -> pending.outputStream().use {input.copyTo(it)}}
            if(!pending.renameTo(target)) pending.delete()
        }
        copy("espeak-ng-data",directory)
        stamp.writeText(version)
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
