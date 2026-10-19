package com.github.tvbox.osc.player.engine.iso

import androidx.media3.extractor.ts.TsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoExtractorsFactoryTest {

    @Test
    fun createsSingleTsExtractor() {
        val extractors = IsoExtractorsFactory().createExtractors()
        assertEquals(1, extractors.size)
        assertTrue(extractors[0] is TsExtractor)
    }

    @Test
    fun eachCallCreatesFreshExtractor() {
        val factory = IsoExtractorsFactory()
        val first = factory.createExtractors()[0]
        val second = factory.createExtractors()[0]
        assertNotSame(first, second)
    }

    @Test
    fun customPayloadReaderFactory_isAccepted() {
        val extractors = IsoExtractorsFactory(BdTsPayloadReaderFactory()).createExtractors()
        assertEquals(1, extractors.size)
    }
}
