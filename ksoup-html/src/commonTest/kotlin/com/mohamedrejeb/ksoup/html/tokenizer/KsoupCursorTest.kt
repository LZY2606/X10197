package com.mohamedrejeb.ksoup.html.tokenizer

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit-level invariants for [KsoupCursor] segment bookkeeping. */
class KsoupCursorTest {

    @Test
    fun absoluteOffsetsAcrossSegments() {
        val cursor = KsoupCursor()
        cursor.append("ab")
        cursor.append("cde")
        cursor.append("f")
        assertEquals(6, cursor.endOffset)

        val chars = StringBuilder()
        while (cursor.hasNext()) {
            chars.append(cursor.currentChar().toChar())
            cursor.advance()
        }
        assertEquals("abcdef", chars.toString())
    }

    @Test
    fun sliceWithinAndAcrossSegments() {
        val cursor = KsoupCursor()
        cursor.append("ab")
        cursor.append("cde")
        cursor.append("f")
        assertEquals("a", cursor.slice(0, 1))
        val singleSegmentCopies = cursor.copiedChars
        assertEquals(0, singleSegmentCopies)
        assertEquals("bc", cursor.slice(1, 3))   // spans 2 segments: "b" + "c"
        val afterTwoSegments = cursor.copiedChars
        assertEquals(2, afterTwoSegments)
        // Starting at a segment boundary still assembles the span once.
        assertEquals("cdef", cursor.slice(2, 6))
        val afterBoundarySpan = cursor.copiedChars
        assertEquals(afterTwoSegments + 4, afterBoundarySpan)
        assertEquals("abcdef", cursor.slice(0, 6)) // all 3 segments: copies 6
        assertEquals(afterBoundarySpan + 6, cursor.copiedChars)
        assertEquals("", cursor.slice(3, 3))
        // Empty slices never copy.
        assertEquals(afterBoundarySpan + 6, cursor.copiedChars)
    }

    @Test
    fun setIndexBackwardAndForwardTracksSegment() {
        val cursor = KsoupCursor()
        cursor.append("ab")
        cursor.append("cd")
        cursor.advance(); cursor.advance(); cursor.advance()
        assertEquals('d'.code, cursor.currentChar())
        cursor.setIndex(0)
        assertEquals('a'.code, cursor.currentChar())
        cursor.setIndex(3)
        assertEquals('d'.code, cursor.currentChar())
        cursor.setIndex(1)
        assertEquals('b'.code, cursor.currentChar())
    }

    @Test
    fun releaseDropsOnlyFullyConsumedSegments() {
        val cursor = KsoupCursor()
        cursor.append("ab")
        cursor.append("cd")
        cursor.append("ef")

        // Consume into the second segment; section still starts in segment 1.
        cursor.sectionStart = 1
        cursor.setIndex(3)
        cursor.advance()
        cursor.releaseConsumed()
        assertEquals("bcdef", cursor.slice(1, 6))
        // Section anchor keeps segment 0 (chars 0-1) alive.
        assertEquals(6, cursor.activeChars)

        // Once the section advances past segment 1, it is releasable.
        cursor.sectionStart = 3
        cursor.releaseConsumed()
        assertEquals(4, cursor.activeChars)
        assertEquals("cdef", cursor.slice(2, 6))
    }

    @Test
    fun releaseAfterFullConsumptionClearsSegments() {
        val cursor = KsoupCursor()
        cursor.append("ab")
        cursor.append("cd")
        while (cursor.hasNext()) cursor.advance()
        cursor.sectionStart = cursor.endOffset
        cursor.entityStart = -1
        cursor.releaseConsumed()
        assertEquals(0, cursor.activeChars)
        assertEquals(-1, cursor.entityStart)

        // A later append starts fresh and offsets stay absolute.
        cursor.append("xy")
        assertEquals('x'.code, cursor.currentChar())
        assertEquals("xy", cursor.slice(4, 6))
    }

    @Test
    fun entityAnchorPinsSegmentUntilCleared() {
        val cursor = KsoupCursor()
        cursor.append("a&")
        cursor.append("amp;b")
        cursor.sectionStart = 1
        cursor.entityStart = 1
        cursor.setIndex(6) // index at final 'b', so segment 2 is in use
        cursor.releaseConsumed()
        // The entity anchor keeps segment 1 live; index keeps segment 2 live.
        assertEquals(7, cursor.activeChars)
        assertEquals("&amp;", cursor.slice(1, 6))
        cursor.entityStart = -1
        cursor.sectionStart = 6
        // Segment 1 ends at 2 <= 6, so it is releasable even while the
        // read position is at the start of the final segment.
        cursor.releaseConsumed()
        assertEquals(5, cursor.activeChars)
    }

    @Test
    fun appendDoesNotAdvanceAndSurvivesPauseLikeUse() {
        val cursor = KsoupCursor()
        cursor.append("abc")
        // Simulate pausing after consuming up to index 2, then appending.
        cursor.advance(); cursor.advance()
        val positionBeforeAppend = cursor.index
        cursor.append("def")
        assertEquals(positionBeforeAppend, cursor.index)
        assertEquals('c'.code, cursor.currentChar())
        cursor.advance()
        assertEquals('d'.code, cursor.currentChar())
    }
}
