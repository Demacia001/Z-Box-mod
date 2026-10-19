package com.github.tvbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.github.tvbox.osc.player.engine.MediaSources
import com.github.tvbox.osc.player.engine.OkHttpDataSource
import com.github.tvbox.osc.util.LOG
import java.io.IOException
import java.util.HashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class IsoDataSource internal constructor(
    private val upstreamFactory: DataSource.Factory,
    private val resolver: IsoResolver,
) : BaseDataSource(false) {

    constructor(upstreamFactory: DataSource.Factory) : this(upstreamFactory, IsoResolver { LOG.i(it) })

    private val closeGuard = IsoCloseGuard()
    private var upstream: DataSource? = null
    private var bytesRemaining = 0L
    private var dataUri: Uri? = null
    private var targetValue: IsoPlayTarget? = null

    fun target(): IsoPlayTarget? = targetValue

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataUri = dataSpec.uri
        val probe = resolver.probe(dataSpec.uri, upstreamFactory)
        val info = resolver.resolve(
            probe,
            DataSourceRangeReader(upstreamFactory, dataSpec.uri, resolver.logger),
        )
        targetValue = info.target
        IsoThroughput.setTarget(info.target.m2tsLength)
        val window = try {
            isoReadWindow(info.target, dataSpec.position, dataSpec.length)
        } catch (e: IsoFormatException) {
            resolver.logger("echo-iso-error:${e.message}")
            throw e
        }
        if (window.length == 0L) {
            closeGuard.activate()
            transferStarted(dataSpec)
            return 0
        }
        val source = upstreamFactory.createDataSource()
        val upstreamSpec = dataSpec.buildUpon()
            .setPosition(window.isoOffset)
            .setLength(window.length)
            .build()
        val openedLength = try {
            source.open(upstreamSpec)
        } catch (e: IOException) {
            runCatching { source.close() }
            logAuthFailure(e, resolver.logger)
            throw e
        }
        upstream = source
        bytesRemaining = if (openedLength == C.LENGTH_UNSET.toLong()) {
            window.length
        } else {
            minOf(openedLength, window.length)
        }
        closeGuard.activate()
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = requireNotNull(upstream) { "not open" }
        val max = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        return try {
            val read = source.read(buffer, offset, max)
            if (read > 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                bytesTransferred(read)
            }
            read
        } catch (e: IOException) {
            logAuthFailure(e, resolver.logger)
            throw e
        }
    }

    override fun close() {
        closeGuard.close {
            val source = upstream
            upstream = null
            bytesRemaining = 0
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

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val logger: (String) -> Unit = { LOG.i(it) },
    ) : DataSource.Factory {

        internal val resolver = IsoResolver(logger)

        override fun createDataSource(): DataSource = IsoDataSource(upstreamFactory, resolver)

        fun resolve(uri: Uri): IsoPlayTarget {
            val probe = resolver.probe(uri, upstreamFactory)
            return resolver.resolve(
                probe,
                DataSourceRangeReader(upstreamFactory, uri, logger),
            ).target
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun factory(headers: Map<String, String>?): Factory = factory(headers, defaultClient)

        fun factory(headers: Map<String, String>?, client: OkHttpClient): Factory {
            val requestHeaders = MediaSources.toRequestHeaders(headers)
            var userAgent: String? = null
            val defaultHeaders = HashMap<String, String>()
            for ((key, value) in requestHeaders) {
                if (key.equals("User-Agent", ignoreCase = true)) {
                    userAgent = value
                } else {
                    defaultHeaders[key] = value
                }
            }
            val httpFactory = OkHttpDataSource.Factory(client)
            httpFactory.setUserAgent(userAgent)
            httpFactory.setDefaultRequestProperties(defaultHeaders)
            return Factory(httpFactory)
        }
    }
}

internal data class IsoReadWindow(val isoOffset: Long, val length: Long)

internal fun isoReadWindow(target: IsoPlayTarget, position: Long, requestedLength: Long): IsoReadWindow {
    if (position < 0 || position > target.m2tsLength) {
        throw IsoFormatException("position out of range: $position/${target.m2tsLength}")
    }
    val available = target.m2tsLength - position
    val length = if (requestedLength == C.LENGTH_UNSET.toLong()) {
        available
    } else {
        minOf(requestedLength, available)
    }
    return IsoReadWindow(target.m2tsOffset + position, length)
}

internal class IsoCloseGuard {
    private var active = false

    fun activate() {
        active = true
    }

    fun close(action: () -> Unit) {
        if (!active) return
        active = false
        action()
    }
}
