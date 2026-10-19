package com.voicestudio.mobile.features.dictate

import org.junit.Assert.*
import org.junit.Test

class PhraseSegmenterTest {
    private fun chunks(seconds: Double, level: Float, rate: Int = 16000) = (0 until (seconds * 10).toInt()).map { FloatArray(rate / 10) { i -> if (i % 2 == 0) level else -level } }

    @Test fun phrasesAreCutAtPausesAndTheTailIsFlushed() {
        val segmenter = PhraseSegmenter(16000)
        val leadingSilence = chunks(1.0, 0f).mapNotNull(segmenter::accept)
        assertTrue("silence before speech is dropped", leadingSilence.isEmpty())
        val first = (chunks(1.2, .2f) + chunks(1.0, 0f)).mapNotNull(segmenter::accept)
        assertEquals(1, first.size)
        assertTrue(first.single().size in 16000 * 19 / 10..16000 * 2)
        assertTrue((chunks(.8, .2f)).mapNotNull(segmenter::accept).isEmpty())
        assertEquals(16000 * 8 / 10, segmenter.flush()!!.size)
        assertNull("nothing left", segmenter.flush())
    }

    @Test fun continuousSpeechIsCutEveryFifteenSeconds() {
        val segmenter = PhraseSegmenter(16000)
        val phrases = chunks(31.0, .2f).mapNotNull(segmenter::accept)
        assertEquals(2, phrases.size)
        assertTrue(phrases.all { it.size == 16000 * 15 })
    }
}
