package com.github.tvbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoProbeLengthTest {

    @Test
    fun parseContentRangeTotal_readsTotalAfterSlash() {
        assertEquals(45_098_467_328L, parseContentRangeTotal("bytes 0-1023/45098467328"))
    }

    @Test
    fun parseContentRangeTotal_acceptsUnsatisfiedRange() {
        assertEquals(123L, parseContentRangeTotal("bytes */123"))
    }

    @Test
    fun parseContentRangeTotal_rejectsUnknownTotal() {
        assertNull(parseContentRangeTotal("bytes 0-1023/*"))
    }

    @Test
    fun parseContentRangeTotal_rejectsMalformed() {
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal(""))
        assertNull(parseContentRangeTotal("bytes"))
        assertNull(parseContentRangeTotal("bytes 0-1023"))
        assertNull(parseContentRangeTotal("bytes 0-1023/"))
        assertNull(parseContentRangeTotal("bytes 0-1023/abc"))
        assertNull(parseContentRangeTotal("bytes 0-1023/0"))
        assertNull(parseContentRangeTotal("0-1023/45098467328"))
    }

    @Test
    fun probeTotalLength_prefersContentRangeTotal() {
        val headers = mapOf(CONTENT_RANGE_HEADER to listOf("bytes 0-1023/45098467328"))
        assertEquals(45_098_467_328L, probeTotalLength(headers, 1024))
    }

    @Test
    fun probeTotalLength_fallsBackToOpenedLength() {
        assertEquals(1024L, probeTotalLength(emptyMap(), 1024))
        assertEquals(1024L, probeTotalLength(mapOf(CONTENT_RANGE_HEADER to listOf("bytes 0-1023/*")), 1024))
    }

    @Test
    fun probeTotalLength_matchesHeaderCaseInsensitively() {
        val headers = mapOf("content-range" to listOf("bytes 0-1023/2048"))
        assertEquals(2048L, probeTotalLength(headers, 1024))
    }

    @Test
    fun isRangeUnsupported_detectsNoneHeader() {
        assertTrue(isRangeUnsupported(mapOf(ACCEPT_RANGES_HEADER to listOf("none"))))
        assertTrue(isRangeUnsupported(mapOf("accept-ranges" to listOf("None"))))
        assertFalse(isRangeUnsupported(mapOf(ACCEPT_RANGES_HEADER to listOf("bytes"))))
        assertFalse(isRangeUnsupported(emptyMap()))
    }

    @Test
    fun sourceMode_distinguishesPartialAndUnsupported() {
        assertEquals("206", sourceMode(mapOf(CONTENT_RANGE_HEADER to listOf("bytes 0-1023/2048"))))
        assertEquals("200", sourceMode(emptyMap()))
        assertEquals("range-none", sourceMode(mapOf(ACCEPT_RANGES_HEADER to listOf("none"))))
    }
}
