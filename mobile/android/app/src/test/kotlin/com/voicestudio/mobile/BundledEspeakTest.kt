package com.voicestudio.mobile

import com.voicestudio.mobile.local.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * espeak-ng reads its voice list once per process, so the app bundles every espeak file
 * any catalogue voice uses and copies them before the first espeak voice speaks
 * (PhoneRuntime). They must be byte-identical to what the models ship.
 */
class BundledEspeakTest {
    @Test fun everyCatalogueEspeakFileIsBundledUnchanged() {
        val catalogue = ModelCatalog.decode(File("../../contract/models.json").readText())
        val bundled = File("src/main/assets/espeak-ng-data")
        val wanted = catalogue.models.flatMap { it.files }.filter { "espeak-ng-data/" in it.path }
            .associateBy { it.path.substringAfter("espeak-ng-data/") }
        assertTrue(wanted.isNotEmpty())
        for ((relative, file) in wanted) {
            val copy = File(bundled, relative)
            assertTrue("missing $relative", copy.isFile)
            val sha = MessageDigest.getInstance("SHA-256").digest(copy.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(relative, file.sha256, sha)
        }
        val extra = bundled.walkTopDown().filter { it.isFile }.map { it.relativeTo(bundled).invariantSeparatorsPath }.toSet() - wanted.keys
        assertEquals("bundled files no catalogue model uses", emptySet<String>(), extra)
    }
}
