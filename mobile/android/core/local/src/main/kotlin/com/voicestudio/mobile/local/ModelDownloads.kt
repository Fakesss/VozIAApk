package com.voicestudio.mobile.local

import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** ENOSPC surfaces as a plain IOException; report it as missing storage, not a network fault. */
fun isNoSpace(error: Throwable): Boolean = generateSequence(error) {it.cause}.any {
    it is android.system.ErrnoException && it.errno==android.system.OsConstants.ENOSPC || it.message?.contains("ENOSPC")==true || it.message?.contains("No space left",ignoreCase=true)==true
}
internal fun sha256(file: File, check: ()->Unit = {}): String = file.inputStream().use { input ->
    val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(262144)
    while(true) {check();val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}
    digest.digest().joinToString("") {"%02x".format(it)}
}
data class DownloadProgress(val received: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String? = null, val extracting: Boolean = false)
/** Dedicated public-download client never carries paired-host PINs or sessions. */
class ModelDownloads internal constructor(
    private val root: File, val licence: LicenceGate, private val ramBytes: Long,
    private val wifi: () -> Boolean, private val freeBytes: () -> Long,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
    private val endpoints: List<String> = listOf("https://huggingface.co", "https://hf-mirror.com"),
    private val allowTestHttp: Boolean = false,
    private val digest: (File, ()->Unit)->String = ::sha256
) {
    constructor(root: File, licence: LicenceGate, ramBytes: Long, wifi: () -> Boolean) : this(root,licence,ramBytes,wifi,{root.apply {mkdirs()}.usableSpace})
    private data class VerifiedStamp(val size: Long, val modified: Long, val catalogue: String)
    private val verified=ConcurrentHashMap<File,VerifiedStamp>()
    private fun stamp(file: File, catalogue: String)=VerifiedStamp(file.length(),file.lastModified(),catalogue)
    private fun verifiedFile(file: File, expected: ModelFile, catalogue: String, check: ()->Unit): Boolean {
        check()
        if(!file.isFile||file.length()!=expected.size) {verified.remove(file);return false}
        val before=stamp(file,catalogue)
        if(verified[file]==before) return true
        verified.remove(file)
        if(digest(file,check)!=expected.sha256||stamp(file,catalogue)!=before) return false
        verified[file]=before
        return true
    }
    private fun invalidate(model: PhoneModel) {model.files.forEach {verified.remove(File(dir(model),it.path))}}
    private val locks=ConcurrentHashMap<String,Mutex>()
    private fun lock(model: PhoneModel)=locks.getOrPut(model.id) {Mutex()}
    private val mutable=MutableStateFlow<Map<String,DownloadProgress>>(emptyMap())
    val progress=mutable.asStateFlow()
    private fun dir(model: PhoneModel)=File(root,model.id)
    private fun identity(model: PhoneModel)=model.revision+model.files.joinToString {it.path+it.sha256+it.size}
    fun installed(model: PhoneModel): Boolean = model.system || runCatching {
        val directory=dir(model)
        File(directory,".verified").readText()==identity(model) && model.files.all {File(directory,it.path).length()==it.size}
    }.getOrDefault(false)
    fun usedBytes(model: PhoneModel): Long = dir(model).walkTopDown().filter {it.isFile}.sumOf {it.length()}
    fun usedBytes(): Long = root.walkTopDown().filter {it.isFile}.sumOf {it.length()}
    private fun networkAllowed(allowMobile: Boolean) {if(!allowMobile&&!wifi()) throw ClientFailure("mobile.local.wifi_required")}
    private fun fit(model: PhoneModel) {if(!model.fits(ramBytes)) throw ClientFailure("mobile.local.ram_required",mapOf("ram" to model.minRamGb.toString()))}
    suspend fun download(model: PhoneModel, allowMobile: Boolean) = withContext(Dispatchers.IO) {
        lock(model).withLock {
            fit(model);licence.requireAccepted(model);networkAllowed(allowMobile)
            val job=currentCoroutineContext().job
            val directory=dir(model).apply {mkdirs()}
            val catalogue=modelPin(model).identity
            val completedFiles=model.files.filter {verifiedFile(File(directory,it.path),it,catalogue) {job.ensureActive()}}.toSet()
            val needed=if(model.archive!=null) archiveBytesNeeded(model,directory,completedFiles) else model.files.filterNot {it in completedFiles}.sumOf { f ->
                val partial=File(directory,f.path+".part").length().takeIf {it<=f.size} ?: 0L
                f.size-partial
            }
            if(freeBytes()<needed+16*1024*1024) throw ClientFailure("mobile.local.storage_required")
            mutable.update {it+(model.id to DownloadProgress(total=model.archive?.size ?: model.totalBytes,running=true))}
            try {
                if(model.archive!=null) downloadArchive(model,allowMobile,directory,catalogue,completedFiles)
                var completed=0L
                if(model.archive==null) model.files.forEach { f ->
                    currentCoroutineContext().ensureActive();licence.requireAccepted(model)
                    val target=File(directory,f.path).apply {parentFile!!.mkdirs()}
                    if(f in completedFiles) {completed+=f.size;return@forEach}
                    verified.remove(target);target.delete()
                    val part=File(directory,f.path+".part")
                    if(part.length()>f.size) part.delete()
                    var last: IOException?=null
                    for(endpoint in endpoints) {
                        try {
                            transfer(endpoint,model,f,part,allowMobile) { received -> mutable.update {it+(model.id to DownloadProgress(completed+received,model.totalBytes,true))} }
                            last=null;break
                        } catch(e:IOException) {last=e;currentCoroutineContext().ensureActive()}
                    }
                    if(last!=null) throw ClientFailure(if(isNoSpace(last)) "mobile.local.storage_required" else "mobile.local.download_failed")
                    currentCoroutineContext().ensureActive()
                    if(part.length()!=f.size||digest(part) {job.ensureActive()}!=f.sha256) {part.delete();throw ClientFailure("mobile.local.checksum_failed")}
                    licence.requireAccepted(model)
                    if(!part.renameTo(target)) throw ClientFailure("mobile.local.storage_required")
                    verified[target]=stamp(target,catalogue)
                    completed+=f.size
                }
                licence.requireAccepted(model)
                val marker=File(directory,".verified.tmp");marker.writeText(identity(model))
                if(!marker.renameTo(File(directory,".verified"))) throw ClientFailure("mobile.local.storage_required")
                mutable.update {it+(model.id to DownloadProgress(model.archive?.size ?: model.totalBytes,model.archive?.size ?: model.totalBytes))}
            } catch(e:CancellationException) {mutable.update {it-model.id};throw e}
            catch(e:Exception) {
                val failure=e as? ClientFailure ?: ClientFailure(if(isNoSpace(e)) "mobile.local.storage_required" else "mobile.local.download_failed")
                mutable.update {it+(model.id to DownloadProgress(total=model.totalBytes,error=failure.key))};throw failure
            }
        }
    }
    private fun allowed(url: HttpUrl): Boolean = allowTestHttp || (url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty() &&
        (url.host in setOf("huggingface.co","hf-mirror.com","hf.co") || url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co")))
    /** GitHub release downloads redirect once to its asset CDN; nothing else is followed. */
    private fun archiveAllowed(url: HttpUrl): Boolean = allowTestHttp || (url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty() &&
        ((url.host=="github.com" && url.encodedPath.startsWith("/k2-fsa/sherpa-onnx/releases/download/")) ||
            url.host in setOf("objects.githubusercontent.com","release-assets.githubusercontent.com")))
    private fun archivePart(directory: File)=File(directory,".archive.part")
    private fun archiveBytesNeeded(model: PhoneModel, directory: File, completed: Set<ModelFile>): Long {
        val missing=model.files.filterNot {it in completed}
        if(missing.isEmpty()) return 0
        val archive=requireNotNull(model.archive)
        val partial=archivePart(directory).length().takeIf {it<=archive.size} ?: 0L
        // The archive and every extracted file coexist until extraction finishes.
        return archive.size-partial+missing.sumOf {it.size}
    }
    /** Resumable archive transfer, archive SHA-256, then member-by-member verified extraction. */
    private suspend fun downloadArchive(model: PhoneModel, allowMobile: Boolean, directory: File, catalogue: String, completed: Set<ModelFile>) {
        val archive=requireNotNull(model.archive)
        val missing=model.files.filterNot {it in completed}
        if(missing.isEmpty()) return
        val job=currentCoroutineContext().job
        val part=archivePart(directory)
        if(part.length()>archive.size) part.delete()
        try {
            transferUrl(archive.url.toHttpUrl(),archive.size,model,part,allowMobile,::archiveAllowed) {received ->
                mutable.update {it+(model.id to DownloadProgress(received,archive.size,true))}
            }
        } catch(e:ClientFailure) {throw e}
        catch(e:IOException) {currentCoroutineContext().ensureActive();throw ClientFailure(if(isNoSpace(e)) "mobile.local.storage_required" else "mobile.local.download_failed")}
        currentCoroutineContext().ensureActive()
        if(part.length()!=archive.size||digest(part) {job.ensureActive()}!=archive.sha256) {part.delete();throw ClientFailure("mobile.local.checksum_failed")}
        licence.requireAccepted(model)
        mutable.update {it+(model.id to DownloadProgress(archive.size,archive.size,true,extracting=true))}
        val wanted=missing.associateBy {it.path}
        val root=directory.canonicalFile
        val extracted=mutableSetOf<String>()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(java.io.BufferedInputStream(part.inputStream(),1 shl 16))).use {tar ->
            while(true) {
                currentCoroutineContext().ensureActive()
                val entry=tar.nextEntry ?: break
                if(!entry.isFile) continue
                val relative=entry.name.removePrefix("./").takeIf {it.startsWith(archive.root+"/")}?.removePrefix(archive.root+"/") ?: continue
                val expected=wanted[relative] ?: continue
                val target=File(directory,expected.path).canonicalFile
                if(!target.path.startsWith(root.path+File.separator)) throw ClientFailure("mobile.local.model_incompatible")
                target.parentFile!!.mkdirs()
                val pending=File(target.path+".part")
                val sha=MessageDigest.getInstance("SHA-256")
                var count=0L
                java.io.FileOutputStream(pending).use {out ->
                    val bytes=ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=tar.read(bytes);if(n<0) break
                        count+=n
                        if(count>expected.size) {pending.delete();throw ClientFailure("mobile.local.checksum_failed")}
                        sha.update(bytes,0,n);out.write(bytes,0,n)
                    }
                    out.fd.sync()
                }
                if(count!=expected.size||sha.digest().joinToString("") {"%02x".format(it)}!=expected.sha256) {pending.delete();throw ClientFailure("mobile.local.checksum_failed")}
                verified.remove(target);target.delete()
                if(!pending.renameTo(target)) throw ClientFailure("mobile.local.storage_required")
                verified[target]=stamp(target,catalogue)
                extracted+=expected.path
            }
        }
        if(!extracted.containsAll(wanted.keys)) throw ClientFailure("mobile.local.checksum_failed")
        part.delete()
    }
    private suspend fun transfer(endpoint: String, model: PhoneModel, file: ModelFile, partial: File, allowMobile: Boolean, report: (Long)->Unit) =
        transferUrl(endpoint.toHttpUrl().newBuilder().addPathSegments(model.repo).addPathSegment("resolve").addPathSegment(model.revision).addPathSegments(file.path).build(),
            file.size,model,partial,allowMobile,::allowed,report)
    private suspend fun transferUrl(initial: HttpUrl, expectedSize: Long, model: PhoneModel, partial: File, allowMobile: Boolean, permitted: (HttpUrl)->Boolean, report: (Long)->Unit) {
        if(partial.length()==expectedSize) return
        var url=initial
        repeat(10) {
            networkAllowed(allowMobile);licence.requireAccepted(model);currentCoroutineContext().ensureActive()
            if(!permitted(url)) throw ClientFailure("mobile.local.download_failed")
            val offset=partial.length()
            val request=Request.Builder().url(url).header("Accept-Encoding","identity").apply {if(offset>0) header("Range","bytes=$offset-")}.build()
            val call=client.newCall(request)
            coroutineScope {
                val cancellation=launch {try {awaitCancellation()} finally {call.cancel()}}
                try {
                    call.execute().use { response ->
                        if(response.code in listOf(301,302,303,307,308)) {
                            url=url.resolve(response.header("Location") ?: throw IOException()) ?: throw IOException()
                        } else {
                            if(response.code !in listOf(200,206)) throw IOException("Download HTTP ${response.code}")
                            val start=if(response.code==206) offset else 0L
                            if(response.code==206 && response.header("Content-Range")?.startsWith("bytes $offset-")!=true) throw IOException("Invalid range")
                            if(response.code==206 && response.header("Content-Range")?.substringAfter('/')!=expectedSize.toString()) throw IOException("Invalid total")
                            val body=response.body ?: throw IOException()
                            RandomAccessFile(partial,"rw").use { output ->
                                if(start==0L) output.setLength(0)
                                output.seek(start);var count=start
                                body.byteStream().use { input ->
                                    val bytes=ByteArray(65536)
                                    while(true) {
                                        currentCoroutineContext().ensureActive();networkAllowed(allowMobile);licence.requireAccepted(model)
                                        val n=input.read(bytes);if(n<0) break
                                        if(count+n>expectedSize) {partial.delete();throw ClientFailure("mobile.local.checksum_failed")}
                                        output.write(bytes,0,n);count+=n;report(count)
                                    }
                                }
                                output.fd.sync()
                                if(count!=expectedSize) throw IOException("Incomplete model file")
                            }
                            return@coroutineScope
                        }
                    }
                } finally {cancellation.cancelAndJoin()}
            }
            if(partial.length()==expectedSize) return
        }
        throw IOException("Too many download redirects")
    }
    suspend fun delete(model: PhoneModel) = withContext(Dispatchers.IO) {lock(model).withLock {
        invalidate(model)
        if(dir(model).exists()&&!dir(model).deleteRecursively()) throw ClientFailure("mobile.local.storage_required")
        mutable.update {it-model.id}
    }}
    /** Deletion and inference share a lease. Rehash only changed files or after a failed native use. */
    suspend fun <T> withModel(model: PhoneModel, use: suspend (File)->T): T = withContext(Dispatchers.IO) {lock(model).withLock {
        if(model.system) return@withLock use(dir(model))
        fit(model);licence.requireAccepted(model)
        if(!installed(model)) throw ClientFailure("mobile.local.model_required")
        val job=currentCoroutineContext().job
        val catalogue=modelPin(model).identity
        model.files.forEach { f ->
            currentCoroutineContext().ensureActive()
            if(!verifiedFile(File(dir(model),f.path),f,catalogue) {job.ensureActive()}) {
                invalidate(model);File(dir(model),".verified").delete();throw ClientFailure("mobile.local.checksum_failed")
            }
        }
        licence.requireAccepted(model)
        try {use(dir(model))}
        catch(e:CancellationException) {throw e}
        catch(e:Throwable) {invalidate(model);throw e}
    }}
}
