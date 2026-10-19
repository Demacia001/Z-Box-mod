package com.github.tvbox.osc.player.engine.iso

import android.util.SparseArray
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader
import com.github.tvbox.osc.util.LOG
import java.util.concurrent.atomic.AtomicBoolean

class BdTsPayloadReaderFactory(
    flags: Int = DEFAULT_FLAGS,
    private val lpcmEnabled: () -> Boolean = { true },
    private val trueHdCoreEnabled: () -> Boolean = { true },
    private val pgsEnabled: () -> Boolean = { true },
) : TsPayloadReader.Factory {

    private val defaults = DefaultTsPayloadReaderFactory(flags)
    private val trueHdLogged = AtomicBoolean(false)

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        defaults.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        when (streamType) {
            STREAM_TYPE_LPCM -> if (lpcmEnabled()) PesReader(LpcmReader(esInfo.language)) else null
            STREAM_TYPE_TRUEHD -> if (trueHdCoreEnabled()) {
                if (trueHdLogged.compareAndSet(false, true)) {
                    LOG.i("echo-iso-truehd-core: lang=${esInfo.language}")
                }
                PesReader(Ac3CoreReader(esInfo.language))
            } else {
                null
            }
            STREAM_TYPE_PGS -> if (pgsEnabled()) PesReader(PgsReader(esInfo.language)) else null
            else -> defaults.createPayloadReader(streamType, esInfo)
        }

    companion object {
        const val STREAM_TYPE_LPCM = 0x80
        const val STREAM_TYPE_TRUEHD = 0x83
        const val STREAM_TYPE_PGS = 0x90

        val DEFAULT_FLAGS: Int =
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
    }
}
