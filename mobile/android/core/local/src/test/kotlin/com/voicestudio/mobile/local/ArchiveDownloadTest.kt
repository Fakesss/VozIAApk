package com.voicestudio.mobile.local

import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ArchiveDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = "voice model weights".toByteArray()
    private val tokens = "a 0\nb 1\n".toByteArray()
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { (name, bytes) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() }); tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
    private val goodArchive = archive(listOf("voice/model.onnx" to model, "voice/tokens.txt" to tokens, "voice/unused.bin" to ByteArray(10),
        "../escape.txt" to "x".toByteArray(), "voice/../../evil.txt" to "y".toByteArray()))
    private fun phoneModel(server: MockWebServer, bytes: ByteArray = goodArchive, archiveHash: String = hash(bytes)) = PhoneModel(
        id = "archive-fixture", capability = "tts", runtime = "sherpa-onnx", repo = "k2-fsa/sherpa-onnx", revision = "a".repeat(40),
        files = listOf(ModelFile("model.onnx", model.size.toLong(), hash(model), "model"), ModelFile("tokens.txt", tokens.size.toLong(), hash(tokens), "tokens")),
        totalBytes = (model.size + tokens.size).toLong(), languages = listOf("pt-BR"), minRamGb = 1.0,
        licence = ModelLicence("cc0", "commercial-ok", false, "Credit", "Holder", "b".repeat(64)),
        archive = ModelArchive(server.url("/k2-fsa/sherpa-onnx/releases/download/tts-models/voice.tar.bz2").toString(), archiveHash, bytes.size.toLong(), "voice"), name = "Fixture")
    private fun downloads(free: () -> Long = { Long.MAX_VALUE }) = ModelDownloads(File(temporary.root, "models"), LicenceGate(File(temporary.root, "licences.json"), "1"),
        8_000_000_000, { true }, free, OkHttpClient.Builder().followRedirects(false).build(), emptyList(), true)
    private fun serve(bytes: ByteArray, failFirstAfter: Int? = null): MockWebServer {
        var first = true
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    if (range != null) return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $range-${bytes.size - 1}/${bytes.size}")
                        .setBody(Buffer().write(bytes.copyOfRange(range, bytes.size)))
                    if (failFirstAfter != null && first) {
                        first = false
                        // Advertise the full length but stop early: the client keeps the partial file.
                        return MockResponse().setBody(Buffer().write(bytes.copyOfRange(0, failFirstAfter))).setHeader("Content-Length", bytes.size)
                            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                    return MockResponse().setBody(Buffer().write(bytes))
                }
            }
            start()
        }
    }

    @Test fun archiveIsVerifiedExtractedAndUnsafeEntriesIgnored() = runTest {
        val server = serve(goodArchive)
        try {
            val m = phoneModel(server); val d = downloads()
            d.download(m, true)
            assertTrue(d.installed(m))
            val dir = File(temporary.root, "models/archive-fixture")
            assertArrayEquals(model, File(dir, "model.onnx").readBytes())
            assertArrayEquals(tokens, File(dir, "tokens.txt").readBytes())
            assertFalse("unselected members are not extracted", File(dir, "unused.bin").exists())
            assertFalse(File(temporary.root, "escape.txt").exists()); assertFalse(File(temporary.root, "evil.txt").exists())
            assertFalse("the archive is removed after extraction", File(dir, ".archive.part").exists())
            var used = 0; d.withModel(m) { used++ }; assertEquals(1, used)
        } finally { server.shutdown() }
    }

    @Test fun wrongArchiveChecksumIsRejectedAndNothingInstalled() = runTest {
        val server = serve(goodArchive)
        try {
            val m = phoneModel(server, archiveHash = "c".repeat(64)); val d = downloads()
            try { d.download(m, true); fail() } catch (e: ClientFailure) { assertEquals("mobile.local.checksum_failed", e.key) }
            assertFalse(d.installed(m))
        } finally { server.shutdown() }
    }

    @Test fun tamperedMemberFailsEvenWithMatchingArchiveHash() = runTest {
        val tampered = archive(listOf("voice/model.onnx" to "other weights!!!!!!".toByteArray(), "voice/tokens.txt" to tokens))
        val server = serve(tampered)
        try {
            val m = phoneModel(server, tampered); val d = downloads()
            try { d.download(m, true); fail() } catch (e: ClientFailure) { assertEquals("mobile.local.checksum_failed", e.key) }
            assertFalse(d.installed(m))
        } finally { server.shutdown() }
    }

    @Test fun interruptedDownloadResumesWithRange() = runTest {
        val server = serve(goodArchive, failFirstAfter = goodArchive.size / 2)
        try {
            val m = phoneModel(server); val d = downloads()
            try { d.download(m, true) } catch (_: ClientFailure) {}
            if (!d.installed(m)) d.download(m, true)
            assertTrue(d.installed(m))
            val ranged = (0 until server.requestCount).map { server.takeRequest() }.count { it.getHeader("Range") != null }
            assertTrue("second attempt continued from the partial file", ranged >= 1)
        } finally { server.shutdown() }
    }

    @Test fun insufficientSpaceIsReportedBeforeDownloading() = runTest {
        val server = serve(goodArchive)
        try {
            val m = phoneModel(server); val d = downloads(free = { 1024 })
            try { d.download(m, true); fail() } catch (e: ClientFailure) { assertEquals("mobile.local.storage_required", e.key) }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun bundledCatalogueArchiveEntriesAreValid() {
        val catalogue = ModelCatalog.decode(File(System.getProperty("user.dir"), "../../../contract/models.json").readText())
        val archived = catalogue.models.filter { it.archive != null }
        assertEquals(setOf("piper-pt-br-faber", "supertonic-3-int8", "kokoro-multi-v1-0-int8"), archived.map { it.id }.toSet())
        archived.forEach { assertTrue(it.archive!!.url.startsWith(ARCHIVE_PREFIX)); assertTrue(it.languages.isNotEmpty()) }
        assertTrue(archived.first { it.id == "supertonic-3-int8" }.languages.contains("pt"))
        assertEquals(10, archived.first { it.id == "supertonic-3-int8" }.speakers.size)
    }

    @Test fun speechWindowsCutAtSilenceAndCoverEverySample() {
        val rate = 1000
        val samples = FloatArray(rate * 45) { i -> if (i in 18_500 until 18_800) 0f else .5f }
        val windows = speechWindows(samples, rate)
        assertEquals(0, windows.first().first); assertEquals(samples.size, windows.last().second)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
        assertTrue("first cut moves into the quiet gap", windows.first().second in 18_500..18_800)
        assertTrue(windows.all { it.second - it.first <= rate * 20 })
    }

    @Test fun splitForSynthesisRespectsLimitAndKeepsText() {
        val text = (1..60).joinToString(" ") { "Frase número $it tem algumas palavras." } + "\n\n" + "x".repeat(2500)
        val chunks = splitForSynthesis(text, 400)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(text.replace(Regex("\\s+"), ""), chunks.joinToString("").replace(Regex("\\s+"), ""))
    }
}
