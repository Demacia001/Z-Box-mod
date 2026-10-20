package com.github.tvbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRotationTest {

    @Test
    fun keepsWritingBelowThreshold() {
        assertFalse(LOG.shouldRotateLog(0L))
        assertFalse(LOG.shouldRotateLog(7L * 1024 * 1024))
        assertFalse(LOG.shouldRotateLog(LOG.FILE_LOG_MAX_BYTES - 1))
    }

    @Test
    fun rotatesAtThreshold() {
        assertTrue(LOG.shouldRotateLog(LOG.FILE_LOG_MAX_BYTES))
        assertTrue(LOG.shouldRotateLog(LOG.FILE_LOG_MAX_BYTES + 1024))
    }
}
