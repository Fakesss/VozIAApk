package com.voicestudio.mobile.local

import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LocalPolicyTest {
    @get:Rule val temporary=TemporaryFolder()
    private val payload="pinned model data".toByteArray()
    private fun model(gated: Boolean=false, digest: String=hash(payload)): PhoneModel = ModelCatalog.decode("""
        {"schema_version":1,"unknown":"ignored","models":[{"id":"fixture","name":"Fixture","capability":"tts","runtime":"sherpa-onnx",
        "repo":"org/model","revision":"${"a".repeat(40)}","files":[{"path":"weights.bin","size":${payload.size},"sha256":"$digest","role":"model"}],
        "total_bytes":${payload.size},"languages":["en"],"min_ram_gb":2,
        "licence":{"id":"example","category":"${if(gated) "non-commercial" else "commercial-ok"}","requires_acceptance":$gated,"credit":"Credit","rights_holder":"Holder","terms_fingerprint":"${"b".repeat(64)}"},"config":{}}]}
    """).models.single()
    private fun hash(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun gate()=LicenceGate(File(temporary.root,"licences.json"),"1.2.3")
    private fun downloads(server: MockWebServer, licence: LicenceGate=gate(), wifi: ()->Boolean={true}, free: ()->Long={Long.MAX_VALUE}, endpoints: List<String> = listOf(server.url("/").toString())) =
        ModelDownloads(File(temporary.root,"models"),licence,8_000_000_000,wifi,free,OkHttpClient.Builder().followRedirects(false).build(),endpoints,true)
    private suspend fun failure(key: String, block: suspend ()->Unit) {
        try {block();fail("Expected $key")} catch(e:ClientFailure) {assertEquals(key,e.key)}
    }
    @Test fun contractAndRoutingFailClosed() {
        val m=model();assertEquals("fixture",m.id);assertTrue(m.fits(2_000_000_000));assertFalse(m.fits(1_999_999_999))
        assertEquals(RunLocation.PHONE,routeTask(null,true,true))
        assertEquals(RunLocation.COMPUTER,routeTask(null,false,true))
        assertEquals(RunLocation.DOWNLOAD,routeTask(null,false,false))
        assertEquals(RunLocation.PHONE,routeTask("computer",true,false))
        assertEquals(RunLocation.DOWNLOAD,routeTask("phone",false,true))
        assertEquals(RunLocation.COMPUTER,routeTask("computer",true,true))
    }
    @Test fun invalidCatalogueCannotChooseAnUnpinnedOrEscapingFile() {
        val json=kotlinx.serialization.json.Json
        val valid=json.encodeToString(ModelCatalog.serializer(),ModelCatalog(1,listOf(model())))
        for(invalid in listOf(valid.replace("\"name\":\"Fixture\"","\"name\":\"\""),valid.replace("weights.bin","../escape"),valid.replace("weights.bin",".verified"),valid.replace(hash(payload),"missing-checksum"),valid.replace("\"schema_version\":1","\"schema_version\":2"))) {
            try {ModelCatalog.decode(invalid);fail("Invalid catalogue accepted")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun unreadableReceiptsAndStaleDialogFingerprintsStayBlocked() {
        val m=model(true);val g=gate()
        try {g.record(m,true,"c".repeat(64));fail()} catch(_:ClientFailure) {}
        assertFalse(g.accepted(m))
        File(temporary.root,"licences.json").writeText("corrupted")
        try {g.requireAccepted(m);fail()} catch(e:ClientFailure) {assertEquals("mobile.local.licence_storage",e.key)}
    }
    @Test fun licencePersistsFingerprintVersionTimeAndWithdrawal() {
        val m=model(true);val gate=gate();assertFalse(gate.accepted(m))
        try {gate.requireAccepted(m);fail()} catch(_:ClientFailure) {}
        gate.record(m,true);assertTrue(gate.accepted(m));assertTrue(gate().accepted(m))
        val text=File(temporary.root,"licences.json").readText();assertTrue(text.contains("1.2.3"));assertTrue(text.contains("time"))
        assertFalse(gate.accepted(m.copy(licence=m.licence.copy(termsFingerprint="c".repeat(64)))))
        gate.record(m,false);assertFalse(gate().accepted(m))
    }
    @Test fun resumeUsesRangeThenChecksDigestBeforeActivation() = runTest {
        MockWebServer().use {server -> server.start()
            val part=File(temporary.root,"models/fixture/weights.bin.part").apply {parentFile!!.mkdirs();writeBytes(payload.copyOfRange(0,5))}
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 5-${payload.size-1}/${payload.size}").setBody(Buffer().write(payload.copyOfRange(5,payload.size))))
            val downloads=downloads(server);val m=model();downloads.download(m,false)
            assertEquals("bytes=5-",server.takeRequest().getHeader("Range"));assertFalse(part.exists());assertTrue(downloads.installed(m))
            assertArrayEquals(payload,downloads.withModel(m) {File(it,"weights.bin").readBytes()})
            File(temporary.root,"models/fixture/weights.bin").writeBytes(ByteArray(payload.size))
            failure("mobile.local.checksum_failed") {downloads.withModel(m) {fail()}}
            assertFalse(downloads.installed(m))
        }
    }
    @Test fun serverIgnoringRangeRestartsWithoutAppending() = runTest {
        MockWebServer().use {server -> server.start();server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            File(temporary.root,"models/fixture/weights.bin.part").apply {parentFile!!.mkdirs();writeBytes(payload.copyOfRange(0,5))}
            val d=downloads(server);d.download(model(),false);assertTrue(d.installed(model()))
        }
    }
    @Test fun checksumFailureNeverActivatesAndDeleteRemovesFragments() = runTest {
        MockWebServer().use {server -> server.start();server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(payload.size))))
            val d=downloads(server);failure("mobile.local.checksum_failed") {d.download(model(),false)}
            assertFalse(d.installed(model()));d.delete(model());assertEquals(0L,d.usedBytes())
        }
    }
    @Test fun wifiStorageRamAndLicenceBlockBeforeNetwork() = runTest {
        MockWebServer().use {server ->server.start()
            failure("mobile.local.wifi_required") {downloads(server,wifi={false}).download(model(),false)}
            failure("mobile.local.storage_required") {downloads(server,free={0}).download(model(),false)}
            failure("mobile.local.ram_required") {downloads(server).download(model().copy(minRamGb=32.0),false)}
            val m=model(true);val gate=gate();val d=downloads(server,gate)
            failure("mobile.local.licence_required") {d.download(m,false)}
            failure("mobile.local.licence_required") {d.withModel(m) {fail()}}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun fallbackAndMobileDataRequireAnExplicitDownload() = runTest {
        MockWebServer().use {primary ->MockWebServer().use {mirror ->
            primary.start();mirror.start();primary.enqueue(MockResponse().setResponseCode(503));mirror.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val d=downloads(primary,wifi={false},endpoints=listOf(primary.url("/").toString(),mirror.url("/").toString()))
            assertEquals(0,primary.requestCount);assertEquals(0,mirror.requestCount)
            d.download(model(),true);assertTrue(d.installed(model()));assertEquals(1,primary.requestCount);assertEquals(1,mirror.requestCount)
        }}
    }
    @Test fun cancellationRetainsResumeBytesWithoutActivating() = runTest {
        MockWebServer().use {server ->server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).throttleBody(2,1,TimeUnit.SECONDS))
            val d=downloads(server)
            val job=launch(Dispatchers.IO) {d.download(model(),false)}
            withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))}
            job.cancelAndJoin();assertFalse(d.installed(model()));assertFalse(d.progress.value[model().id]?.running==true)
            d.delete(model());assertEquals(0L,d.usedBytes())
        }
    }
    @Test fun withdrawalBlocksPreviouslyVerifiedWeights() = runTest {
        MockWebServer().use {server ->server.start();server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val m=model(true);val g=gate();g.record(m,true);val d=downloads(server,g);d.download(m,false)
            g.record(m,false);failure("mobile.local.licence_required") {d.withModel(m) {fail()}}
        }
    }
    @Test fun nativeControlHonoursCancellationAndAudioHasSyntheticMetadata() {
        val job=Job();val values=mutableListOf<Float>();val control=NativeControl(job,{values+=it}) {true}
        control.progress(.5f);assertEquals(listOf(.5f),values);job.cancel();assertTrue(control.cancelled());control.progress(1f);assertEquals(1,values.size)
        val output=File(temporary.root,"result.wav");markSynthetic(LocalAudio(floatArrayOf(.1f,.2f,-.5f),24000),output)
        val bytes=output.readBytes();assertEquals("RIFF",bytes.copyOfRange(0,4).toString(Charsets.US_ASCII));assertTrue(bytes.toString(Charsets.US_ASCII).contains("VoiceStudio synthetic audio"))
    }
}
