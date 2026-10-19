package com.github.tvbox.osc.player.engine.iso

import java.util.Locale

data class IsoPlayTarget(val m2tsOffset: Long, val m2tsLength: Long, val name: String)

class IsoLocator(private val volume: UdfVolume) {

    fun locate(): IsoPlayTarget {
        val bdmv = volume.find(volume.root(), BDMV)
            ?: throw IsoFormatException("BDMV directory missing")
        if (!bdmv.directory) throw IsoFormatException("BDMV is not a directory")
        val stream = volume.find(bdmv, STREAM)
            ?: throw IsoFormatException("BDMV/$STREAM directory missing")
        if (!stream.directory) throw IsoFormatException("$STREAM is not a directory")
        val candidates = volume.list(stream).filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(M2TS) }
        if (candidates.isEmpty()) throw IsoFormatException("no m2ts file found")
        val target = candidates.maxByOrNull { it.length }
            ?: throw IsoFormatException("no m2ts file found")
        if (target.length <= 0) throw IsoFormatException("empty m2ts: ${target.name}")
        val offset = target.extents.firstOrNull()?.offset
            ?: throw IsoFormatException("m2ts has no extents: ${target.name}")
        var expected = offset
        for (extent in target.extents) {
            if (extent.offset != expected) throw IsoFormatException("fragmented m2ts: ${target.name}")
            expected += extent.length
        }
        if (expected - offset < target.length) {
            throw IsoFormatException("truncated m2ts: ${target.name}")
        }
        return IsoPlayTarget(offset, target.length, target.name)
    }

    private companion object {
        const val BDMV = "BDMV"
        const val STREAM = "STREAM"
        const val M2TS = ".m2ts"
    }
}
