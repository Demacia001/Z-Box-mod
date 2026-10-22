package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAttemptStateTest {

    private fun bootRetriedState(): PlaybackAttemptState = PlaybackAttemptState().apply {
        hasRetriedSameUrlOnBoot = true
    }

    @Test
    fun sameUrlRetry_defaultsToFalse() {
        assertFalse(PlaybackAttemptState().hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByBeginNewPlay() {
        val st = bootRetriedState()
        st.beginNewPlay()
        assertFalse(st.hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByUserSelfRescue() {
        val st = bootRetriedState()
        st.userSelfRescue()
        assertFalse(st.hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_clearedByLadderReset() {
        val st = bootRetriedState()
        st.resetAutoRetryLadder()
        assertFalse(st.hasRetriedSameUrlOnBoot)
    }

    @Test
    fun sameUrlRetry_keptBySessionBoundary() {
        val st = bootRetriedState()
        st.beginSession()
        assertTrue(st.hasRetriedSameUrlOnBoot)
    }

    @Test
    fun preloadedResultFlag_clearedByBeginNewPlay() {
        val st = PlaybackAttemptState()
        st.usedPreloadedResult = true
        st.beginNewPlay()
        assertFalse(st.usedPreloadedResult)
    }

    @Test
    fun httpStatus_clearedByBeginNewPlay() {
        val st = PlaybackAttemptState()
        st.lastHttpStatus = 403
        st.beginNewPlay()
        assertEquals(0, st.lastHttpStatus)
    }

    @Test
    fun httpStatus_clearedBySessionBoundary() {
        val st = PlaybackAttemptState()
        st.lastHttpStatus = 500
        st.beginSession()
        assertEquals(0, st.lastHttpStatus)
    }
}
