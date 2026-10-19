package com.github.tvbox.osc.player.engine.iso

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdTsPayloadReaderFactoryTest {

    private val factory = BdTsPayloadReaderFactory()

    @Test
    fun defaultStreams_getReader() {
        for (type in intArrayOf(
            TsExtractor.TS_STREAM_TYPE_H264,
            TsExtractor.TS_STREAM_TYPE_H265,
            TsExtractor.TS_STREAM_TYPE_AAC_ADTS,
            TsExtractor.TS_STREAM_TYPE_AC3,
            TsExtractor.TS_STREAM_TYPE_E_AC3,
            TsExtractor.TS_STREAM_TYPE_DTS,
            TsExtractor.TS_STREAM_TYPE_HDMV_DTS,
            SCTE35_STREAM_TYPE,
        )) {
            assertNotNull("streamType=$type", factory.createPayloadReader(type, esInfo(type)))
        }
    }

    @Test
    fun hdmvDts_requiresFlag() {
        assertNull(
            BdTsPayloadReaderFactory(0)
                .createPayloadReader(TsExtractor.TS_STREAM_TYPE_HDMV_DTS, esInfo(TsExtractor.TS_STREAM_TYPE_HDMV_DTS)),
        )
        assertNotNull(
            factory.createPayloadReader(
                TsExtractor.TS_STREAM_TYPE_HDMV_DTS,
                esInfo(TsExtractor.TS_STREAM_TYPE_HDMV_DTS),
            ),
        )
    }

    @Test
    fun bluRayOnlyStreams_areRegisteredOrIgnored() {
        val trueHd = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
        )
        assertNotNull(trueHd)
        assertTrue(trueHd is PesReader)
        val pgs = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_PGS,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_PGS),
        )
        assertNotNull(pgs)
        assertTrue(pgs is PesReader)
        assertNotNull(
            factory.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
            ),
        )
    }

    @Test
    fun pgs_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(pgsEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_PGS,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_PGS),
            ),
        )
    }

    @Test
    fun trueHd_overridesDefaultFactory() {
        assertNull(
            DefaultTsPayloadReaderFactory(0).createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
        assertNotNull(
            factory.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
    }

    @Test
    fun trueHd_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(trueHdCoreEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_TRUEHD),
            ),
        )
    }

    @Test
    fun lpcm_matchesDc2H262AndIsIntercepted() {
        assertEquals(TsExtractor.TS_STREAM_TYPE_DC2_H262, BdTsPayloadReaderFactory.STREAM_TYPE_LPCM)
        val defaultReader = DefaultTsPayloadReaderFactory(0).createPayloadReader(
            TsExtractor.TS_STREAM_TYPE_DC2_H262,
            esInfo(TsExtractor.TS_STREAM_TYPE_DC2_H262),
        )
        val lpcmReader = factory.createPayloadReader(
            BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
            esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
        )
        assertNotNull(defaultReader)
        assertNotNull(lpcmReader)
        assertTrue(lpcmReader is PesReader)
    }

    @Test
    fun lpcm_switchOff_doesNotRegister() {
        val disabled = BdTsPayloadReaderFactory(lpcmEnabled = { false })
        assertNull(
            disabled.createPayloadReader(
                BdTsPayloadReaderFactory.STREAM_TYPE_LPCM,
                esInfo(BdTsPayloadReaderFactory.STREAM_TYPE_LPCM),
            ),
        )
    }

    private fun esInfo(streamType: Int) = TsPayloadReader.EsInfo(
        streamType,
        null,
        TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED,
        emptyList(),
        ByteArray(0),
    )

    private companion object {
        const val SCTE35_STREAM_TYPE = 0x86
    }
}
