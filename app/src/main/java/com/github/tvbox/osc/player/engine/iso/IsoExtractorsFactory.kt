package com.github.tvbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

class IsoExtractorsFactory(
    private val readerFactory: TsPayloadReader.Factory = DEFAULT_READER_FACTORY,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = arrayOf(newExtractor())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = arrayOf(newExtractor())

    private fun newExtractor(): Extractor = TsExtractor(
        TsExtractor.MODE_MULTI_PMT,
        0,
        DefaultSubtitleParserFactory(),
        TimestampAdjuster(TIMESTAMP_SHARED),
        readerFactory,
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
    )

    private companion object {
        const val TIMESTAMP_SHARED = 0L

        val DEFAULT_READER_FACTORY: TsPayloadReader.Factory =
            BdTsPayloadReaderFactory(
                lpcmEnabled = { IsoSettings.lpcmEnabled() },
                trueHdCoreEnabled = { IsoSettings.trueHdCoreEnabled() },
                pgsEnabled = { IsoSettings.pgsEnabled() },
            )
    }
}
