package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeoutsTest {

    @Test
    fun isoSession_usesFortyFiveSeconds() {
        assertEquals(45_000L, PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = true))
    }

    @Test
    fun nonIsoSession_keepsTwentySeconds() {
        assertEquals(20_000L, PlaybackTimeouts.switchLinePlayTimeoutMs(isoSession = false))
    }
}
