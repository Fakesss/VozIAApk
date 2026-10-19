package com.voicestudio.mobile.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import java.io.File

@Serializable data class ModelFile(val path: String, val size: Long, val sha256: String, val role: String)
@Serializable data class ModelLicence(val id: String, val category: String,
    @SerialName("requires_acceptance") val requiresAcceptance: Boolean,
    val credit: String, @SerialName("rights_holder") val rightsHolder: String,
    @SerialName("terms_fingerprint") val termsFingerprint: String, val terms: JsonObject = JsonObject(emptyMap()))
/** A sherpa-onnx GitHub release archive; [root] is its single top-level directory. */
@Serializable data class ModelArchive(val url: String, val sha256: String, val size: Long, val root: String)
/** A named preset voice. [language] limits it to one catalogue language when set. */
data class ModelSpeaker(val id: Int, val name: String, val language: String?)
@Serializable data class PhoneModel(val id: String, val capability: String, val runtime: String, val repo: String = "", val revision: String = "",
    val files: List<ModelFile>, @SerialName("total_bytes") val totalBytes: Long, val languages: List<String>,
    @SerialName("min_ram_gb") val minRamGb: Double, val licence: ModelLicence, val config: JsonObject = JsonObject(emptyMap()), val name: String = "",
    val archive: ModelArchive? = null) {
    val gated: Boolean get() = licence.category != "commercial-ok" || licence.requiresAcceptance
    /** The platform TextToSpeech engine: nothing to download, verify or accept. */
    val system: Boolean get() = runtime == SYSTEM_TTS_RUNTIME
    val speakers: List<ModelSpeaker> get() = (config["speakers"] as? JsonArray).orEmpty().mapNotNull { item ->
        val value = item as? JsonObject ?: return@mapNotNull null
        val id = value["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        ModelSpeaker(id, value["name"]?.jsonPrimitive?.contentOrNull ?: (id + 1).toString(), value["language"]?.jsonPrimitive?.contentOrNull)
    }
    fun fits(ramBytes: Long): Boolean = ramBytes.toDouble() >= minRamGb * 1_000_000_000
    fun option(name: String, fallback: String = ""): String = config[name]?.jsonPrimitive?.contentOrNull ?: fallback
    fun role(directory: File, role: String): String = files.firstOrNull { it.role == role }?.let { File(directory,it.path).absolutePath }.orEmpty()
}
@Serializable data class ModelCatalog(@SerialName("schema_version") val schemaVersion: Int, val models: List<PhoneModel>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(text: String): ModelCatalog = json.decodeFromString<ModelCatalog>(text).also { catalog ->
            require(catalog.schemaVersion == 1)
            require(catalog.models.map {it.id}.distinct().size == catalog.models.size)
            catalog.models.forEach { m ->
                require(m.name.isNotBlank())
                require(Regex("[a-zA-Z0-9_-]+").matches(m.id))
                val archive = m.archive
                if(archive == null) {
                    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(m.repo))
                    require(Regex("[0-9a-f]{40}").matches(m.revision))
                } else {
                    require(m.runtime == "sherpa-onnx" && archive.url.startsWith(ARCHIVE_PREFIX) && archive.url.endsWith(".tar.bz2"))
                    require(archive.sha256.matches(Regex("[0-9a-f]{64}")) && archive.size > 0)
                    require(Regex("[A-Za-z0-9_.-]+").matches(archive.root) && archive.root !in setOf(".", ".."))
                }
                require(m.capability in setOf("tts","asr","asr-stream","clone","translate"))
                require(m.runtime == when(m.capability) {"clone" -> "omnivoice.cpp";"translate" -> "llama.cpp";else -> "sherpa-onnx"})
                require(m.minRamGb > 0 && m.minRamGb.isFinite() && m.files.isNotEmpty())
                require(m.licence.termsFingerprint.matches(Regex("[0-9a-f]{64}")))
                require(m.files.map {it.path}.distinct().size == m.files.size)
                m.files.forEach { f ->
                    require(f.path.isNotBlank() && !f.path.startsWith('/') && f.path.split('/').none {it in setOf("..", ".", "")} && '\\' !in f.path && ':' !in f.path && f.path.none {it.code<32} && f.path.split('/').none {it.startsWith(".verified") || it.endsWith(".part")})
                    require(f.size > 0 && f.sha256.matches(Regex("[0-9a-f]{64}")))
                }
                require(m.totalBytes>0)
                require(m.totalBytes == m.files.sumOf {it.size})
            }
        }
    }
}

const val SYSTEM_TTS_RUNTIME = "android-tts"
const val SYSTEM_TTS_ID = "android-system-tts"
internal const val ARCHIVE_PREFIX = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
/** Listed first so Speak works before any download; it is not part of models.json. */
fun systemTtsModel(languages: List<String>): PhoneModel = PhoneModel(SYSTEM_TTS_ID, "tts", SYSTEM_TTS_RUNTIME,
    files = emptyList(), totalBytes = 0, languages = languages, minRamGb = 0.0, name = "Android",
    licence = ModelLicence("system", "commercial-ok", false, "Android TextToSpeech", "Device text-to-speech engine", "0".repeat(64)))

enum class RunLocation { PHONE, COMPUTER, DOWNLOAD }
fun routeTask(remembered: String?, installed: Boolean, paired: Boolean): RunLocation = when {
    remembered == "phone" -> if(installed) RunLocation.PHONE else RunLocation.DOWNLOAD
    remembered == "computer" && paired -> RunLocation.COMPUTER
    installed -> RunLocation.PHONE
    paired -> RunLocation.COMPUTER
    else -> RunLocation.DOWNLOAD
}
