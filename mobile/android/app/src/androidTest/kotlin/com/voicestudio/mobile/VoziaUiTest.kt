package com.voicestudio.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicestudio.mobile.data.SessionStore
import com.voicestudio.mobile.l10n.L10n
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real app UI in Portuguese, the way a person would: type text, Generate
 * with the Piper pt-BR voice installed by [VoziaDeviceTest], wait for the player,
 * Save to Downloads, and read the saved location. Screenshots go to the app's
 * external files folder for the CI report.
 */
@RunWith(AndroidJUnit4::class)
class VoziaUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val l = L10n.load(context, "pt")
    private fun t(key: String, params: Map<String, Any> = emptyMap()) = l.t(key, params)
    private fun shot(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun waitFor(timeoutMs: Long = 20_000, matcher: SemanticsMatcher) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun speakGenerateSaveToDownloadsInPortuguese() {
        val runtime = com.voicestudio.mobile.local.PhoneRuntime.get(context)
        assumeTrue("run VoziaDeviceTest first to install the voice", runtime.downloads.installed(runtime.model("piper-pt-br-faber")))
        runBlocking {
            SessionStore(context).update { it.copy(consent = false, locale = "pt", phoneModels = it.phoneModels + ("speak" to "piper-pt-br-faber"), runLocations = it.runLocations + ("speak" to "phone")) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(matcher = hasTestTag("home.speak"))
            shot("01-inicio")
            compose.onNodeWithTag("home.speak").performScrollTo().performClick()
            waitFor(matcher = hasText(t("mobile.speak.placeholder")))
            compose.onNodeWithText(t("mobile.speak.placeholder")).performTextInput("Bom dia! Este áudio foi gerado neste celular, sem internet, e salvo em Downloads.")
            compose.onNodeWithText(t("vozia.speak.title")).performScrollTo().performTextInput("Áudio de teste da interface")
            shot("02-falar-texto")
            compose.onNodeWithText(t("mobile.speak.generate")).performScrollTo().performClick()
            shot("03-gerando")
            waitFor(240_000, hasText(t("vozia.export.downloads")))
            compose.onNodeWithText(t("vozia.export.downloads")).performScrollTo()
            shot("04-audio-gerado")
            compose.onNodeWithText(t("vozia.export.downloads")).performClick()
            waitFor(60_000, hasTestTag("export.location"))
            compose.onNodeWithTag("export.location").performScrollTo()
            val saved = compose.onNodeWithTag("export.location").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
            report("ui_saved_location", saved)
            assert(saved.contains("Download/VoiceStudio/")) { saved }
            shot("05-salvo-em-downloads")
        }
        // Other screens, for the visual report.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(matcher = hasTestTag("home.library"))
            for ((tag, name) in listOf("home.library" to "06-projetos", "home.audiobooks" to "07-audiolivros", "home.transcribe" to "08-transcrever", "home.tasks" to "09-tarefas", "home.models" to "10-modelos")) {
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                compose.waitForIdle(); Thread.sleep(800); shot(name)
                compose.onNode(hasText(t("nav.home")) and hasClickAction()).performClick()
                waitFor(matcher = hasTestTag(tag))
            }
            compose.onNode(hasText(t("mobile.tabs.settings")) and hasClickAction()).performClick()
            compose.waitForIdle(); Thread.sleep(500); shot("11-configuracoes")
        }
    }
}
