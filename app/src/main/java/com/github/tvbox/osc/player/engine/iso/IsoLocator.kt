package com.github.tvbox.osc.player.engine.iso

import com.github.tvbox.osc.util.LOG
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class IsoPlayRun(val offset: Long, val length: Long)

data class IsoPlayTarget(
    val runs: List<IsoPlayRun>,
    val m2tsLength: Long,
    val name: String,
    val startOffset: Long = 0L,
    val durationMs: Long = 0L,
) {
    constructor(
        m2tsOffset: Long,
        m2tsLength: Long,
        name: String,
        startOffset: Long = 0L,
        durationMs: Long = 0L,
    ) : this(listOf(IsoPlayRun(m2tsOffset, m2tsLength)), m2tsLength, name, startOffset, durationMs)

    val m2tsOffset: Long get() = runs.firstOrNull()?.offset ?: 0L

    val availableLength: Long get() = (m2tsLength - startOffset).coerceAtLeast(0L)
}

internal fun coalesceRuns(extents: List<IsoExtent>): List<IsoPlayRun> {
    if (extents.isEmpty()) return emptyList()
    val runs = ArrayList<IsoPlayRun>(extents.size)
    var current: IsoPlayRun? = null
    for (extent in extents) {
        if (extent.length <= 0L) continue
        val open = current
        current = if (open != null && open.offset + open.length == extent.offset) {
            IsoPlayRun(open.offset, open.length + extent.length)
        } else {
            if (open != null) runs.add(open)
            IsoPlayRun(extent.offset, extent.length)
        }
    }
    current?.let { runs.add(it) }
    return runs
}

data class IsoSelection(
    val segments: List<IsoPlayTarget>,
    val playlistName: String?,
    val durationMs: Long,
    val baseMs: Long = 0L,
) {
    val primary: IsoPlayTarget get() = segments.first()

    val totalLength: Long get() = segments.sumOf { it.availableLength }

    val multiSegment: Boolean get() = segments.size > 1
}

internal fun matchPlaylistSegments(
    playlist: IsoPlaylist,
    targets: List<IsoPlayTarget>,
): List<IsoPlayTarget>? {
    if (playlist.segments.isEmpty()) return null
    val byName = HashMap<String, IsoPlayTarget>(targets.size)
    for (target in targets) byName[target.name.lowercase(Locale.ROOT)] = target
    val ordered = ArrayList<IsoPlayTarget>(playlist.segments.size)
    for (segment in playlist.segments) {
        val name = "${segment.clipId}.m2ts".lowercase(Locale.ROOT)
        val target = byName[name] ?: return null
        val duration = segment.durationMs
        ordered.add(if (duration > 0L) target.copy(durationMs = duration) else target)
    }
    return ordered
}

internal fun alignSelectionToAccessPoint(
    segments: List<IsoPlayTarget>,
    totalDurationMs: Long,
    playlistName: String?,
    seekMs: Long,
    accessPoints: (String) -> ClpiIndex?,
): IsoSelection? {
    if (segments.isEmpty() || seekMs <= 0L) return null
    var start = 0L
    for (index in segments.indices) {
        val segment = segments[index]
        val duration = segment.durationMs
        if (duration <= 0L) return null
        val end = start + duration
        if (seekMs < end || index == segments.lastIndex) {
            val clipId = segment.name.substringBeforeLast('.').lowercase(Locale.ROOT)
            val clip = accessPoints(clipId) ?: return null
            val first = clip.accessPoints.firstOrNull() ?: return null
            val local = (seekMs - start).coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
            val targetTicks = first.ptsTicks + local * CLPI_TICKS_PER_MILLI
            val point = clip.accessPointAt(targetTicks) ?: first
            val delta = ((point.ptsTicks - first.ptsTicks) / CLPI_TICKS_PER_MILLI).coerceAtLeast(0L)
            val head = segment.copy(
                startOffset = point.packetOffset,
                durationMs = (duration - delta).coerceAtLeast(0L),
            )
            return IsoSelection(
                listOf(head) + segments.drop(index + 1),
                playlistName,
                totalDurationMs,
                start + delta,
            )
        }
        start = end
    }
    return null
}

class IsoLocator(private val volume: IsoFileSource) {

    private val clipIndexes = ConcurrentHashMap<String, ClpiIndex>()

    private val clipLocks = ConcurrentHashMap<String, Any>()

    fun locate(): IsoPlayTarget = locateSelection().primary

    fun locateSelection(): IsoSelection {
        val bdmv = volume.find(volume.root(), BDMV)
            ?: throw IsoFormatException("BDMV directory missing")
        if (!bdmv.directory) throw IsoFormatException("BDMV is not a directory")
        val stream = volume.find(bdmv, STREAM)
            ?: throw IsoFormatException("BDMV/$STREAM directory missing")
        if (!stream.directory) throw IsoFormatException("$STREAM is not a directory")
        val candidates = volume.list(stream).filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(M2TS) }
        if (candidates.isEmpty()) throw IsoFormatException("no m2ts file found")
        val largest = candidates.maxByOrNull { it.length }
            ?: throw IsoFormatException("no m2ts file found")
        LOG.i(
            "echo-iso-m2ts:count=${candidates.size}:chosen=${largest.name}:${largest.length}:" +
                "top=${candidates.sortedByDescending { it.length }.take(TOP_N).joinToString(",") { "${it.name}:${it.length}" }}",
        )
        val targets = ArrayList<IsoPlayTarget>(candidates.size)
        for (candidate in candidates) {
            val target = toTarget(candidate) ?: continue
            targets.add(target)
        }
        val fallback = targets.firstOrNull { it.name == largest.name }
            ?: toTarget(largest)
            ?: throw IsoFormatException("m2ts has no extents: ${largest.name}")
        val playlists = probePlaylists(bdmv)
        val chosen = pickLongestPlaylist(playlists)
        if (chosen != null) {
            val segments = matchPlaylistSegments(chosen, targets)
            if (segments == null) {
                LOG.i("echo-iso-playlist-skip:${chosen.name}:reason=segment-missing")
            } else {
                val selection = IsoSelection(segments, chosen.name, chosen.durationMs)
                LOG.i(
                    "echo-iso-playlist:${chosen.name}:segments=${segments.size}:" +
                        "duration=${chosen.durationMs}:unique=${uniqueDurationMs(chosen)}:" +
                        "total=${selection.totalLength}:first=${segments.first().name}",
                )
                IsoPlaylistState.publishStreams(chosen.streams)
                IsoPlaylistState.publishVideoPid(chosen.videoPid)
                if (chosen.streams.isNotEmpty()) {
                    LOG.i(
                        "echo-iso-streams:" + chosen.streams.joinToString(",") { "${it.pid}:${it.language}" },
                    )
                }
                return selection
            }
        }
        IsoPlaylistState.publishVideoPid(null)
        return IsoSelection(listOf(fallback), null, 0L)
    }

    fun locateSeek(selection: IsoSelection, seekMs: Long): IsoSelection? =
        alignSelectionToAccessPoint(
            selection.segments,
            selection.durationMs,
            selection.playlistName,
            seekMs,
        ) { clipId -> accessPoints(clipId) }

    internal fun accessPoints(clipId: String): ClpiIndex? {
        val key = clipId.lowercase(Locale.ROOT)
        clipIndexes[key]?.let { return it }
        val fresh = Any()
        val lock = clipLocks.putIfAbsent(key, fresh) ?: fresh
        return synchronized(lock) {
            clipIndexes[key]?.let { return@synchronized it }
            val index = loadClipIndex(key) ?: return@synchronized null
            clipIndexes[key] = index
            index
        }
    }

    private fun loadClipIndex(key: String): ClpiIndex? {
        val bdmv = volume.find(volume.root(), BDMV) ?: return null
        val dir = volume.find(bdmv, CLIPINF) ?: return null
        if (!dir.directory) return null
        val want = "$key$CLPI"
        val entry = volume.list(dir).firstOrNull { it.name.lowercase(Locale.ROOT) == want } ?: return null
        val data = try {
            volume.readEntry(entry)
        } catch (e: IsoFormatException) {
            LOG.i("echo-iso-clpi-read:${entry.name}:${e.message}")
            return null
        }
        val index = parseClpi(entry.name, data)
        if (index == null) {
            LOG.i("echo-iso-clpi-parse:${entry.name}:${data.size}")
            return null
        }
        val first = index.accessPoints.first()
        LOG.i("echo-iso-clpi:${entry.name}:rap=${index.accessPoints.size}:first=${first.ptsTicks}:spn=${index.sourcePackets}")
        return index
    }

    private fun toTarget(entry: IsoEntry): IsoPlayTarget? {
        if (entry.length <= 0) {
            LOG.i("echo-iso-target-skip:${entry.name}:reason=empty")
            return null
        }
        val runs = coalesceRuns(entry.extents)
        if (runs.isEmpty()) {
            LOG.i("echo-iso-target-skip:${entry.name}:reason=no-extents")
            return null
        }
        var total = 0L
        for (run in runs) total += run.length
        if (total < entry.length) {
            LOG.i("echo-iso-target-skip:${entry.name}:reason=short:$total/${entry.length}")
            return null
        }
        if (runs.size > MAX_TARGET_RUNS) {
            LOG.i("echo-iso-target-skip:${entry.name}:reason=runs:${runs.size}")
            return null
        }
        if (runs.size > 1) {
            LOG.i(
                "echo-iso-target-runs:${entry.name}:${runs.size}:" +
                    runs.joinToString(",") { "${it.offset}+${it.length}" },
            )
        }
        return IsoPlayTarget(runs, entry.length, entry.name)
    }

    private fun probePlaylists(bdmv: IsoEntry): List<IsoPlaylist> {
        val playlistDir = volume.find(bdmv, PLAYLIST)
        if (playlistDir == null || !playlistDir.directory) {
            LOG.i("echo-iso-mpls:none dirMissing=${playlistDir == null}")
            return emptyList()
        }
        val entries = volume.list(playlistDir)
            .filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(MPLS) }
            .sortedByDescending { it.length }
        if (entries.isEmpty()) {
            LOG.i("echo-iso-mpls:none emptyDir")
            return emptyList()
        }
        val parsed = ArrayList<IsoPlaylist>(entries.size)
        val skipped = ArrayList<String>()
        var rawLogged = 0
        for (entry in entries) {
            val data = try {
                volume.readEntry(entry)
            } catch (e: IsoFormatException) {
                LOG.i("echo-iso-mpls-skip:${entry.name}:reason=read:${e.message}")
                skipped.add(entry.name)
                continue
            }
            val playlist = parseMplPlaylist(entry.name, data)
            if (playlist == null) {
                if (rawLogged < MAX_RAW_LOG) {
                    LOG.i("echo-iso-mpls-raw:${entry.name}:len=${data.size}")
                    dumpHex(entry.name, data)
                    rawLogged++
                }
                skipped.add(entry.name)
            } else {
                parsed.add(playlist)
            }
        }
        parsed.sortByDescending { uniqueDurationMs(it) }
        val summary = parsed.take(TOP_N).joinToString(";") {
            "${it.name}=${it.segments.size}:${it.durationMs}:${uniqueDurationMs(it)}"
        }
        val chosen = pickLongestPlaylist(parsed)
        LOG.i(
            "echo-iso-mpls:count=${entries.size}:parsed=${parsed.size}:skipped=${skipped.size}:" +
                "chosen=${chosen?.name}:items=${chosen?.segments?.size}:" +
                "duration=${chosen?.durationMs}:unique=${chosen?.let(::uniqueDurationMs)}:" +
                "first=${chosen?.segments?.firstOrNull()?.clipId}:" +
                "in=${chosen?.segments?.firstOrNull()?.inTimeUs}:out=${chosen?.segments?.firstOrNull()?.outTimeUs}" +
                ":top=$summary",
        )
        return parsed
    }

    private fun dumpRange(name: String, data: ByteArray, from: Int, limit: Int) {
        if (from < 0 || from >= data.size || limit <= 0) return
        val end = minOf(data.size, from + limit)
        var offset = from
        while (offset < end) {
            val chunkEnd = minOf(offset + HEX_BYTES_PER_LINE, end)
            LOG.i("echo-iso-hex:$name:$offset:${hexRange(data, offset, chunkEnd)}")
            offset = chunkEnd
        }
    }

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun dumpHex(name: String, data: ByteArray) {
        dumpRange(name, data, 0, HEAD_HEX_BYTES)
    }

    private fun hexRange(data: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder((to - from) * 2)
        for (index in from until to) {
            sb.append(HEX_DIGITS[(data[index].toInt() shr 4) and 0x0F])
            sb.append(HEX_DIGITS[data[index].toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        const val BDMV = "BDMV"
        const val STREAM = "STREAM"
        const val PLAYLIST = "PLAYLIST"
        const val M2TS = ".m2ts"
        const val MPLS = ".mpls"
        const val CLIPINF = "CLIPINF"
        const val CLPI = ".clpi"
        const val TOP_N = 5
        const val MAX_TARGET_RUNS = 256
        const val MAX_RAW_LOG = 2
        const val HEAD_HEX_BYTES = 4096
        const val HEX_BYTES_PER_LINE = 64
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

