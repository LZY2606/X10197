package com.mohamedrejeb.ksoup.html.tokenizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenCursorTest {

    @Test
    fun absolutePositionsAcrossSegments() {
        val cursor = TokenCursor()
        cursor.appendActive("ab")
        cursor.appendActive("cde")
        cursor.appendActive("f")

        assertEquals(6, cursor.end)
        assertEquals(0, cursor.start)
        assertEquals(listOf(97, 98, 99, 100, 101, 102),
            (0 until 6).map { cursor.charAt(it) })

        assertEquals("abcdef", cursor.substring(0, 6))
        assertEquals("bc", cursor.substring(1, 3))
        assertEquals("de", cursor.substring(3, 5))
        assertEquals("cdef", cursor.substring(2, 6))
        assertEquals("", cursor.substring(3, 3))
    }

    @Test
    fun surrogateUnitsAreNeverDecodedOrReplaced() {
        val cursor = TokenCursor()
        // Split a surrogate pair and a lone surrogate across segment edges.
        cursor.appendActive("x\uD83D")
        cursor.appendActive("\uDE00y")
        cursor.appendActive("\uD800")

        assertEquals(0x1F600.let { 0xD83D }, cursor.charAt(1))
        assertEquals(0xDE00, cursor.charAt(2))
        assertEquals('y'.code, cursor.charAt(3))
        assertEquals(0xD800, cursor.charAt(4)) // lone high surrogate preserved
        assertEquals("x\uD83D\uDE00y\uD800", cursor.substring(0, 5))
        // A range covering only the lone surrogate is verbatim.
        assertEquals("\uD800", cursor.substring(4, 5))
    }

    @Test
    fun releasePrefixDropsOnlyWholeLeadingSegments() {
        val cursor = TokenCursor()
        cursor.appendActive("abcd")
        cursor.appendActive("efgh")
        cursor.appendActive("ijkl")

        cursor.releasePrefix(3) // inside the first segment -> nothing released
        assertEquals(0, cursor.start)
        assertEquals(12, cursor.end)
        assertEquals("abcdefghijkl", cursor.substring(0, 12))

        cursor.releasePrefix(4) // exactly at the boundary
        assertEquals(4, cursor.start)
        assertEquals(2, cursor.retainedSegmentCount)
        assertEquals("efghijkl", cursor.substring(4, 12))

        cursor.releasePrefix(9) // within second segment; first of remainder drops
        assertEquals(8, cursor.start)
        assertEquals("ijkl", cursor.substring(8, 12))
        assertEquals(8, cursor.crossSegmentCopyCount.coerceAtMost(8))

        cursor.releasePrefix(12) // everything consumed
        assertEquals(0, cursor.retainedSegmentCount)
        assertEquals(12, cursor.start)
    }

    @Test
    fun releasedRangesAreNoLongerReadable() {
        val cursor = TokenCursor()
        cursor.appendActive("abcd")
        cursor.appendActive("efgh")
        cursor.releasePrefix(4)
        assertFailsWith<IndexOutOfBoundsException> { cursor.substring(0, 2) }
        assertFailsWith<IndexOutOfBoundsException> { cursor.charAt(0) }
        // Still readable after the frontier.
        assertEquals('e'.code, cursor.charAt(4))
    }

    @Test
    fun pendingChunksAreQueuedInOrderAndEmptyChunksIgnored() {
        val cursor = TokenCursor()
        cursor.queuePending("")
        cursor.queuePending("x")
        cursor.queuePending("yz")
        assertEquals(2, cursor.pendingCount)
        assertEquals("x", cursor.nextPending())
        assertEquals("yz", cursor.nextPending())
        assertEquals(null, cursor.nextPending())

        cursor.appendActive("")
        assertEquals(0, cursor.retainedSegmentCount)
    }

    @Test
    fun crossSegmentSlicesCountOnlyCopiedUnits() {
        val cursor = TokenCursor()
        cursor.appendActive("aaaabbbb")
        cursor.appendActive("ccccdddd")

        assertEquals(0, cursor.crossSegmentCopyCount)
        // Same-segment slice: zero copy.
        assertEquals("aaaa", cursor.substring(0, 4))
        assertEquals(0, cursor.crossSegmentCopyCount)
        // Spanning slice copies its own length only.
        val span = cursor.substring(6, 10)
        assertEquals("bbcc", span)
        assertEquals(4, cursor.crossSegmentCopyCount)
        assertTrue(cursor.retainedLength == 16)
    }

    @Test
    fun resetClearsAllState() {
        val cursor = TokenCursor()
        cursor.appendActive("abc")
        cursor.index = 2
        cursor.sectionStart = 1
        cursor.entityStart = 1
        cursor.queuePending("q")
        cursor.reset()
        assertEquals(0, cursor.end)
        assertEquals(0, cursor.index)
        assertEquals(0, cursor.sectionStart)
        assertEquals(0, cursor.pendingCount)
        assertEquals(0, cursor.retainedLength)
    }
}
