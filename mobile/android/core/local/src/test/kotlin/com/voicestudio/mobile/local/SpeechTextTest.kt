package com.voicestudio.mobile.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechTextTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun speakableTextKeepsWordsAndDropsWhatCrashesTheFrontEnds() {
        // Typographic punctuation from pasted or e-book text: espeak's Portuguese voice
        // fell back to English for these, which crashed without the English dictionary.
        assertEquals("Ele disse \"olá\" , 'tudo bem'... \"teste\" 1 2", speakable("Ele disse “olá” — ‘tudo bem’… «teste» ¹ ²"))
        // Emoji and other characters outside the BMP are removed (JNI modified UTF-8 garbles them).
        assertEquals("Bom dia! Até logo.", speakable("Bom dia! 😀👍🏽 Até logo.‍️"))
        // Accents, cedilla, ordinals and currency stay.
        assertEquals("Ação, coração, 1º lugar, 2ª vez, R$ 10,50 e 5%.", speakable("Ação, coração, 1º lugar, 2ª vez, R$ 10,50 e 5%."))
        // Decomposed accents are composed; symbols such as © become a space.
        assertEquals("é © fim".replace("©", "").replace("  ", " "), speakable("é © fim"))
        assertEquals("", speakable("😀 🎉"))
        assertEquals("linha 1\nlinha 2", speakable("linha 1\nlinha 2"))
        // Degrees, the numero sign and the minus sign are read by espeak: keep them.
        assertEquals("Hoje faz -5 °C, 30°C e 86°F; № 3", speakable("Hoje faz −5 °C, 30℃ e 86℉; № 3"))
    }

    @Test fun kokoroLanguagesMapToEspeakVoices() {
        assertEquals("en-gb-x-rp", espeakVoice("en-gb"))
        for (code in listOf("pt-br", "en-us", "es", "fr", "it", "hi", "zh")) assertEquals(code, espeakVoice(code))
    }

    @Test fun voicedSpanFindsSpeechAndSkipsSilence() {
        val rate = 16000
        val samples = FloatArray(rate * 4)
        // Speech from 1.0 s to 2.5 s.
        for (i in rate until rate * 5 / 2) samples[i] = (0.2 * kotlin.math.sin(i * 0.05)).toFloat()
        val span = voicedSpan(samples, 0, samples.size, rate)!!
        assertEquals(1.0, span.first.toDouble() / rate, 0.04)
        assertEquals(2.5, span.second.toDouble() / rate, 0.04)
        assertNull("silence", voicedSpan(FloatArray(rate * 2), 0, rate * 2, rate))
        assertNull("hiss below -50 dBFS", voicedSpan(FloatArray(rate) { if (it % 2 == 0) .001f else -.001f }, 0, rate, rate))
    }

    @Test fun everyEspeakVoiceSharesOneFolderWithTheUnionOfInstalledData() {
        val root = File(temporary.root, "models")
        fun model(id: String, files: Map<String, String>): PhoneModel {
            val entries = files.entries.joinToString(",") { (path, content) ->
                """{"path":"$path","size":${content.length},"sha256":"${"0".repeat(64)}","role":"espeak-data"}"""
            }
            val model = ModelCatalog.decode("""
                {"schema_version":1,"models":[{"id":"$id","name":"$id","capability":"tts","runtime":"sherpa-onnx","repo":"org/m","revision":"${"a".repeat(40)}",
                "files":[$entries],"total_bytes":${files.values.sumOf { it.length }},"languages":["pt"],"min_ram_gb":1,
                "licence":{"id":"x","category":"commercial-ok","requires_acceptance":false,"credit":"","rights_holder":"","terms_fingerprint":"${"b".repeat(64)}"},"config":{}}]}
            """).models.single()
            val directory = File(root, id)
            files.forEach { (path, content) -> File(directory, path).apply { parentFile!!.mkdirs() }.writeText(content) }
            // What a finished download records (ModelDownloads.identity).
            File(directory, ".verified").writeText(model.revision + model.files.joinToString { it.path + it.sha256 + it.size })
            return model
        }
        val piper = model("piper", mapOf("espeak-ng-data/phondata" to "PHON", "espeak-ng-data/pt_dict" to "PT", "espeak-ng-data/lang/roa/pt" to "pt"))
        val kitten = model("kitten", mapOf("espeak-ng-data/phondata" to "PHON", "espeak-ng-data/en_dict" to "EN"))
        val absent = ModelCatalog.decode("""{"schema_version":1,"models":[{"id":"absent","name":"Absent","capability":"tts","runtime":"sherpa-onnx","repo":"org/m","revision":"${"a".repeat(40)}",
            "files":[{"path":"espeak-ng-data/fr_dict","size":2,"sha256":"${"0".repeat(64)}","role":"espeak-data"}],"total_bytes":2,"languages":["fr"],"min_ram_gb":1,
            "licence":{"id":"x","category":"commercial-ok","requires_acceptance":false,"credit":"","rights_holder":"","terms_fingerprint":"${"b".repeat(64)}"}}]}""").models.single()
        val downloads = ModelDownloads(root, LicenceGate(File(temporary.root, "licences.json"), "1"), 8_000_000_000) { true }
        val shared = downloads.sharedEspeak(listOf(piper, kitten, absent)) { File(it, "seeded").writeText("S") }
        assertEquals(File(root, ESPEAK_DIRECTORY).canonicalFile, shared)
        assertEquals("PT", File(shared, "pt_dict").readText())
        assertEquals("EN", File(shared, "en_dict").readText())
        assertEquals("pt", File(shared, "lang/roa/pt").readText())
        assertEquals("PHON", File(shared, "phondata").readText())
        assertFalse("models that are not installed contribute nothing", File(shared, "fr_dict").exists())
        assertTrue(File(shared, "seeded").exists())
    }
}
