package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.tokenizer.TokenCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenCursorTest {

    @Test
    fun addressesCharactersAcrossSegmentsByAbsoluteOffset() {
        val cursor = TokenCursor()
        cursor.write("ab")
        cursor.write("cde")
        cursor.write("f")
        assertEquals(6, cursor.end())
        assertEquals("a", cursor.charAt(0).toString())
        assertEquals("b", cursor.charAt(1).toString())
        assertEquals("c", cursor.charAt(2).toString())
        assertEquals("e", cursor.charAt(4).toString())
        assertEquals("f", cursor.charAt(5).toString())
    }

    @Test
    fun sliceWithinAndAcrossSegments() {
        val cursor = TokenCursor()
        cursor.write("ab")
        cursor.write("cde")
        cursor.write("f")
        assertEquals("", cursor.slice(2, 2))
        assertEquals("cd", cursor.slice(2, 4))
        assertEquals("abcdef", cursor.slice(0, 6))
        assertEquals("b", cursor.slice(1, 2))
        assertEquals("bcd", cursor.slice(1, 4))
        assertEquals("ef", cursor.slice(4, 6))
    }

    @Test
    fun advancingAndRewinding() {
        val cursor = TokenCursor()
        cursor.write("abc")
        cursor.write("def")
        cursor.advance(); cursor.advance()
        assertEquals(2, cursor.index)
        assertEquals('c', cursor.charAt(cursor.index))
        cursor.rewindTo(0)
        assertEquals(0, cursor.index)
        assertEquals('a', cursor.currentChar())
    }

    @Test
    fun releaseDropsConsumedLeadingSegments() {
        val cursor = TokenCursor()
        cursor.write("ab")
        cursor.write("cde")
        cursor.write("f")
        cursor.index = 2
        cursor.sectionStart = 2
        cursor.releaseConsumedPrefix()
        assertEquals(4, cursor.retainedLength)
        assertEquals('c', cursor.charAt(2))
        assertEquals("cdef", cursor.slice(2, 6))
    }

    @Test
    fun openSectionAnchorPreventsReleaseOfItsSpan() {
        val cursor = TokenCursor()
        cursor.write("abcdef")
        cursor.write("gh")
        cursor.index = 8
        cursor.sectionStart = 1
        cursor.releaseConsumedPrefix()
        assertEquals(8, cursor.retainedLength)
        assertEquals("bcdefgh", cursor.slice(1, 8))
    }

    @Test
    fun inactiveAnchorsDoNotPinData() {
        val cursor = TokenCursor()
        cursor.write("abc")
        cursor.write("def")
        cursor.index = 6
        cursor.sectionStart = 6
        cursor.releaseConsumedPrefix()
        // First fully-consumed segment is dropped; only the tail remains.
        assertEquals(3, cursor.retainedLength)
        assertEquals("def", cursor.slice(3, 6))
    }

    @Test
    fun surrogateCodeUnitsAreAddressedIndividually() {
        val cursor = TokenCursor()
        // Construct segments from explicit code units so platforms that
        // normalize lone surrogates in string literals (Kotlin/JS) still see
        // the exact UTF-16 units the stream delivered.
        cursor.write(charArrayOf('x', Char(0xD83D)).concatToString())
        cursor.write(charArrayOf(Char(0xDE00), 'y').concatToString())
        assertEquals(4, cursor.end())
        assertEquals(0xD83D, cursor.charAt(1).code)
        assertEquals(0xDE00, cursor.charAt(2).code)
        // Reassembled units round-trip exactly, no platform default behavior.
        val rejoined = cursor.slice(0, 4)
        assertEquals(listOf(0x78, 0xD83D, 0xDE00, 0x79),
            IntArray(rejoined.length) { rejoined[it].code }.toList())
    }

    @Test
    fun loneSurvivesAcrossSegments() {
        val cursor = TokenCursor()
        cursor.write(charArrayOf('x', Char(0xD83D)).concatToString())
        cursor.write("y")
        val out = cursor.slice(0, 3)
        assertEquals(listOf(0x78, 0xD83D, 0x79),
            IntArray(out.length) { out[it].code }.toList())
    }

    @Test
    fun charAtBeyondEndThrows() {
        val cursor = TokenCursor()
        cursor.write("a")
        cursor.index = 1
        assertFailsWith<IndexOutOfBoundsException> { cursor.charAt(1) }
    }

    @Test
    fun resetClearsSegmentsAndCounters() {
        val cursor = TokenCursor()
        cursor.write("abcdef")
        cursor.index = 6
        cursor.releaseConsumedPrefix()
        cursor.reset()
        assertEquals(0, cursor.end())
        assertEquals(0, cursor.index)
        assertEquals(0, cursor.retainedLength)
        assertEquals(0, cursor.peakRetainedLength)
    }

    @Test
    fun entitySpanAcrossSegmentsDecodesViaSlice() {
        val cursor = TokenCursor()
        cursor.write("a&co")
        cursor.write("py;b")
        assertEquals("&copy;", cursor.slice(1, 7))
    }
}
