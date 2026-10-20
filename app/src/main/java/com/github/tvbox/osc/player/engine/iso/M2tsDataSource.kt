package com.github.tvbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.github.tvbox.osc.util.LOG
import java.io.IOException

class M2tsDataSource internal constructor(
    private val upstreamFactory: DataSource.Factory,
    private val targetProvider: (Uri) -> IsoPlayTarget,
) : BaseDataSource(false) {

    constructor(factory: IsoDataSource.Factory) : this(factory, factory::resolve)

    private val closeGuard = IsoCloseGuard()
    private var upstream: DataSource? = null
    private var stream: M2tsStream? = null
    private var dataUri: Uri? = null
    private var targetValue: IsoPlayTarget? = null

    fun target(): IsoPlayTarget? = targetValue

    fun position(): Long = stream?.position() ?: 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataUri = dataSpec.uri
        val target = targetProvider(dataSpec.uri)
        targetValue = target
        val logicalLength = m2tsLogicalLength(target.availableLength)
        val length = m2tsReadLength(dataSpec.position, dataSpec.length, logicalLength)
        if (dataSpec.position > 0) LOG.i("echo-iso-seek:${dataSpec.position}:${length}")
        closeGuard.activate()
        if (length == 0L) {
            transferStarted(dataSpec)
            return 0
        }
        val span = m2tsSpan(dataSpec.position, length)
        val source = upstreamFactory.createDataSource()
        val upstreamSpec = dataSpec.buildUpon()
            .setPosition(span.offset)
            .setLength(span.length)
            .build()
        val opened = try {
            source.open(upstreamSpec)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        upstream = source
        stream = M2tsStream(
            source,
            dataSpec.position,
            length,
            if (opened == C.LENGTH_UNSET.toLong()) span.length else minOf(opened, span.length),
        )
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val active = stream ?: return C.RESULT_END_OF_INPUT
        val read = active.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun close() {
        closeGuard.close {
            val source = upstream
            upstream = null
            stream = null
            try {
                source?.close()
            } finally {
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: dataUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    class Factory(private val isoFactory: IsoDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = M2tsDataSource(isoFactory)
    }
}

internal const val M2TS_TS_SIZE = 188
internal const val M2TS_PACKET_SIZE = 192
internal const val M2TS_ATS_SIZE = 4

internal data class M2tsSpan(val offset: Long, val length: Long)

internal fun m2tsOffset(logical: Long): Long =
    (logical / M2TS_TS_SIZE) * M2TS_PACKET_SIZE + M2TS_ATS_SIZE + logical % M2TS_TS_SIZE

internal fun m2tsSpan(position: Long, length: Long): M2tsSpan {
    require(position >= 0) { "position must not be negative" }
    require(length > 0) { "length must be positive" }
    val offset = m2tsOffset(position)
    val end = m2tsOffset(position + length - 1) + 1
    return M2tsSpan(offset, end - offset)
}

internal fun m2tsLogicalLength(m2tsLength: Long): Long =
    if (m2tsLength <= 0) 0 else m2tsLength / M2TS_PACKET_SIZE * M2TS_TS_SIZE

internal fun m2tsReadLength(position: Long, requestedLength: Long, m2tsLength: Long): Long {
    if (position < 0 || position > m2tsLength) {
        throw IsoFormatException("position out of range: $position/$m2tsLength")
    }
    val available = m2tsLength - position
    return if (requestedLength == C.LENGTH_UNSET.toLong()) available else minOf(requestedLength, available)
}

internal class M2tsStream(
    private val upstream: DataSource,
    start: Long,
    private var bytesRemaining: Long,
    private var upstreamRemaining: Long,
) {
    private var position = start
    private var skipRemaining = 0
    private val skipBuffer = ByteArray(M2TS_ATS_SIZE)

    fun position(): Long = position

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (!skipAts()) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        val inBlock = (position % M2TS_TS_SIZE).toInt()
        val max = minOf(length.toLong(), bytesRemaining, (M2TS_TS_SIZE - inBlock).toLong(), upstreamRemaining)
        if (max <= 0L) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        val read = upstream.read(buffer, offset, max.toInt())
        if (read <= 0) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        upstreamRemaining -= read
        position += read
        bytesRemaining -= read
        IsoThroughput.onRead(read)
        if (bytesRemaining > 0L && position % M2TS_TS_SIZE == 0L) {
            skipRemaining = M2TS_ATS_SIZE
        }
        return read
    }

    private fun skipAts(): Boolean {
        while (skipRemaining > 0) {
            val want = minOf(skipRemaining.toLong(), upstreamRemaining).toInt()
            if (want <= 0) return false
            val read = upstream.read(skipBuffer, 0, want)
            if (read <= 0) return false
            skipRemaining -= read
            upstreamRemaining -= read
        }
        return true
    }
}
