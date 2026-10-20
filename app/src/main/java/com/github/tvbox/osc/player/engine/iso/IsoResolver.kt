package com.github.tvbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal data class IsoProbe(val totalLength: Long, val firstBytes: ByteArray)

internal data class IsoVolumeInfo(
    val totalLength: Long,
    val selection: IsoSelection,
    val locator: IsoLocator? = null,
)

internal class IsoResolver(
    internal val logger: (String) -> Unit = {},
) {
    private val probes = ConcurrentHashMap<String, IsoProbe>()

    fun probe(uri: Uri, factory: DataSource.Factory): IsoProbe {
        val key = uri.toString()
        probes[key]?.let { return it }
        val value = readProbe(uri, factory)
        if (probes.size >= MAX_PROBES) probes.clear()
        val existing = probes.putIfAbsent(key, value)
        return existing ?: value
    }

    fun resolve(probe: IsoProbe, reader: RangeBlockReader, seekMs: Long = 0L): IsoVolumeInfo {
        val key = isoFingerprint(probe.totalLength, probe.firstBytes)
        val info = try {
            IsoVolumeCache.load(key) { loadVolume(key, probe.totalLength, reader) }
        } catch (e: IsoFormatException) {
            logger("echo-iso-error:${e.message}")
            throw e
        }
        if (seekMs <= 0L) return info
        val aligned = info.locator?.locateSeek(info.selection, seekMs)
        if (aligned == null) {
            logger("echo-iso-seek-rap-miss:$seekMs:${info.selection.segments.size}")
            return info
        }
        logger(
            "echo-iso-seek-rap:${seekMs}:${aligned.baseMs}:" +
                "${aligned.primary.name}:${aligned.primary.startOffset}:" +
                "segments=${aligned.segments.size}",
        )
        return info.copy(selection = aligned)
    }

    private fun loadVolume(key: String, totalLength: Long, reader: RangeBlockReader): IsoVolumeInfo {
        val cached = IsoDiskCache.load(key)
        if (cached != null) {
            try {
                val locator = IsoLocator(CachedVolume(cached.directories, reader))
                val selection = locator.locateSelection()
                logger("echo-iso-cache-hit:${cached.directories.size}:${selection.segments.size}")
                IsoPrepare.schedule(key, locator, selection, null)
                return IsoVolumeInfo(totalLength, selection, locator)
            } catch (e: IsoFormatException) {
                logger("echo-iso-cache-stale:${e.message}")
                IsoDiskCache.evict(key)
            }
        }
        return parse(key, totalLength, reader)
    }

    private fun parse(key: String, totalLength: Long, reader: RangeBlockReader): IsoVolumeInfo {
        val volume = UdfVolume.open(reader, totalLength) { logger("echo-iso-udf-$it") }
        val locator = IsoLocator(volume)
        val selection = locator.locateSelection()
        val target = selection.primary
        logger("echo-iso-udf-ok:${target.m2tsOffset}:${target.m2tsLength}")
        logger("echo-iso-target:${target.name}:${target.m2tsOffset}:${target.m2tsLength}")
        val cached = buildSnapshot(volume)
        IsoPrepare.schedule(key, locator, selection, cached)
        return IsoVolumeInfo(totalLength, selection, locator)
    }

    private fun buildSnapshot(volume: UdfVolume): ByteArray? {
        val started = System.currentTimeMillis()
        val snapshot = try {
            volume.snapshot(logger)
        } catch (e: Exception) {
            logger("echo-iso-cache-fail:${e.javaClass.simpleName}:${e.message}")
            return null
        } ?: run {
            logger("echo-iso-cache-incomplete")
            return null
        }
        val bytes = snapshot.encode()
        logger("echo-iso-cache-build:${bytes.size}:${System.currentTimeMillis() - started}")
        return bytes
    }

    private fun readProbe(uri: Uri, factory: DataSource.Factory): IsoProbe {
        val source = factory.createDataSource()
        try {
            val total = source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build(),
            )
            if (total == C.LENGTH_UNSET.toLong() || total <= 0) {
                throw IsoFormatException("unknown length")
            }
            val size = minOf(total, PROBE_SIZE.toLong()).toInt()
            val bytes = ByteArray(size)
            readFully(source, bytes)
            return IsoProbe(total, bytes)
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            throw e
        } finally {
            source.close()
        }
    }

    private fun readFully(source: DataSource, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val count = source.read(target, read, target.size - read)
            if (count == C.RESULT_END_OF_INPUT) break
            read += count
        }
        if (read != target.size) {
            throw IsoFormatException("probe short read: $read/${target.size}")
        }
    }

    private companion object {
        const val PROBE_SIZE = 4096
        const val MAX_PROBES = 8
    }
}

internal class DataSourceRangeReader(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val logger: (String) -> Unit,
) : RangeBlockReader {

    override fun read(start: Long, length: Int): ByteArray {
        return try {
            readOnce(start, length)
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            if (e is HttpDataSource.InvalidResponseCodeException) throw e
            readOnce(start, length)
        }
    }

    private fun readOnce(start: Long, length: Int): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(start)
                    .setLength(length.toLong())
                    .build(),
            )
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = source.read(bytes, read, length - read)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            if (read != length) throw IsoFormatException("short read: $read/$length")
            return bytes
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            throw e
        } finally {
            source.close()
        }
    }
}

internal object IsoVolumeCache {

    private const val MAX_ENTRIES = 4
    private const val TTL_MS = 600_000L

    private class Cached(val info: IsoVolumeInfo, val at: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Cached>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > MAX_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, FutureTask<IsoVolumeInfo>>()

    fun get(key: String): IsoVolumeInfo? {
        val cached = synchronized(lock) { entries[key] } ?: return null
        if (System.currentTimeMillis() - cached.at > TTL_MS) {
            synchronized(lock) { entries.remove(key) }
            return null
        }
        return cached.info
    }

    fun put(key: String, info: IsoVolumeInfo) {
        synchronized(lock) { entries[key] = Cached(info, System.currentTimeMillis()) }
    }

    fun load(key: String, parse: () -> IsoVolumeInfo): IsoVolumeInfo {
        get(key)?.let { return it }
        val task = FutureTask<IsoVolumeInfo>(Callable { parse() })
        val running = inFlight.putIfAbsent(key, task) ?: task
        if (running === task) task.run()
        val info = try {
            running.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? IsoFormatException)
                ?: IsoFormatException("volume parse failed: ${e.cause?.message}")
        } finally {
            inFlight.remove(key, running)
        }
        put(key, info)
        return info
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        inFlight.clear()
    }
}

internal fun isoFingerprint(totalLength: Long, firstBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(firstBytes)
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        if (value < 0x10) hex.append('0')
        hex.append(value.toString(16))
    }
    return "$totalLength:$hex"
}

internal fun logAuthFailure(e: IOException, logger: (String) -> Unit) {
    val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return
    if (code == 403 || code == 410) logger("echo-iso-auth-expired:$code")
}
