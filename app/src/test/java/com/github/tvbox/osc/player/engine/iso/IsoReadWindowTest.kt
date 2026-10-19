package com.github.tvbox.osc.player.engine.iso

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IsoReadWindowTest {

    private val target = IsoPlayTarget(1000, 500, "sample.m2ts")

    @Test
    fun fullRange_mapsToIsoOffset() {
        assertEquals(IsoReadWindow(1000, 500), isoReadWindow(target, 0, C.LENGTH_UNSET.toLong()))
        assertEquals(IsoReadWindow(1000, 200), isoReadWindow(target, 0, 200))
    }

    @Test
    fun offsetRange_mapsAndClamps() {
        assertEquals(IsoReadWindow(1400, 100), isoReadWindow(target, 400, 2048))
        assertEquals(IsoReadWindow(1500, 0), isoReadWindow(target, 500, 2048))
    }

    @Test
    fun positionPastEnd_throws() {
        assertThrows(IsoFormatException::class.java) {
            isoReadWindow(target, 501, 1)
        }
    }

    @Test
    fun closeGuard_runsOnce() {
        val guard = IsoCloseGuard()
        var closed = 0
        guard.close { closed++ }
        assertEquals(0, closed)
        guard.activate()
        guard.close { closed++ }
        guard.close { closed++ }
        assertEquals(1, closed)
    }
}
