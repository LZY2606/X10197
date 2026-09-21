package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the cursor releases prefixes no unfinished token references,
 * so active buffer size is related to the largest unfinished token (plus the
 * current write granularity), never to the total streamed length.
 */
@OptIn(ExperimentalKsoupApi::class)
class TokenizerBufferLifecycleTest {

    private fun newTokenizer(sink: (String) -> Unit): KsoupTokenizer {
        return KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {
                override fun onText(start: Int, endIndex: Int) {
                    // The parser resolves spans immediately; do nothing here.
                    sink("text")
                }
            }
        )
    }

    @Test
    fun longStreamOfClosedTagsDoesNotRetainWholeInput() {
        val cycles = 200
        val chunk = "<p>some short text</p>\n".repeat(50) // ~1100 units/cycle
        val tokenizer = newTokenizer { }
        var maxRetained = 0
        repeat(cycles) {
            tokenizer.write(chunk)
            maxRetained = maxOf(maxRetained, tokenizer.retainedLength)
        }
        tokenizer.end()

        val totalInput = chunk.length * cycles
        assertTrue(totalInput > 50_000, "test input should span many buffer cycles, was $totalInput")
        // Active retention stays near a single write granularity; it does not
        // grow linearly with total input.
        assertTrue(
            maxRetained <= chunk.length + 64,
            "retained length $maxRetained should be bounded by one chunk (~${chunk.length}), " +
                "total input was $totalInput"
        )
        assertEquals(0, tokenizer.retainedLength, "after end() everything is released")
        assertTrue(
            tokenizer.crossSegmentCopyCount == 0,
            "closed-tag stream emits each piece within one write, no cross-segment copies expected"
        )
    }

    @Test
    fun retentionIsBoundedByLargestUnfinishedToken() {
        // A comment that never sees its closing `-->` is one pending token:
        // the whole comment must stay alive (it is emitted only at EOF).
        // Earlier, already-closed writes must not be retained alongside it.
        val tokenizer = newTokenizer { }

        tokenizer.write("<p>a</p><!--")
        var maxRetained = 0
        var maxPendingToken = 0
        repeat(100) {
            tokenizer.write("c".repeat(30))
            maxRetained = maxOf(maxRetained, tokenizer.retainedLength)
            maxPendingToken = maxOf(maxPendingToken, tokenizer.maxPendingTokenSize)
        }
        // Close the pending comment, then stream lots of unrelated data.
        tokenizer.write("-->")
        repeat(50) {
            tokenizer.write("<i>zz</i>")
        }
        tokenizer.end()

        // The unfinished comment (3000 units) is the binding pending token;
        // allow only small slack for the opening marker and the current write.
        assertTrue(
            maxRetained in 3_000..3_064,
            "retention must follow the largest pending token, got $maxRetained"
        )
        assertTrue(
            maxPendingToken in 3_000..3_064,
            "maxPendingTokenSize tracks the open comment, got $maxPendingToken"
        )
        // Active buffer is a function of the pending token, not of the 3000+
        // already streamed units: it never exceeds pending token plus one write.
        assertTrue(
            tokenizer.retainedLength == 0 || tokenizer.retainedLength <= maxPendingToken + 64
        )
        assertEquals(0, tokenizer.retainedLength, "everything released after close + end()")
    }

    @Test
    fun openAttributeValueFlushesAtWritesWithoutAccumulating() {
        // Characterization: attribute data already written is emitted at write
        // boundaries (so the parser can record it); only the current write can
        // be retained. This mirrors the old chunk behavior and keeps retention
        // independent of the number of writes.
        val tokenizer = KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {}
        )
        tokenizer.write("<div data=" + '"')
        var maxRetained = 0
        repeat(100) {
            tokenizer.write("v".repeat(30))
            maxRetained = maxOf(maxRetained, tokenizer.retainedLength)
        }
        tokenizer.end() // unterminated at EOF: tag is ignored, data released
        assertTrue(maxRetained <= 64, "only the current write retained, got $maxRetained")
        assertEquals(0, tokenizer.retainedLength)
    }

    @Test
    fun longTextDoesNotRepeatedlyConcatenateSegments() {
        // Allocation baseline for long plain text: a write boundary lies in the
        // middle of one text node. Emitting a piece must not copy earlier
        // pieces. Total cross-segment copying must stay bounded by the last
        // unfinished fragment, not by the number of writes.
        val tokenizer = newTokenizer { }
        val chunkSize = 64
        repeat(500) {
            tokenizer.write("a".repeat(chunkSize))
        }
        tokenizer.end()

        // Every emitted text piece is wholly inside one write (cleanup flushes
        // at each boundary), so slice assembly never crosses a segment.
        assertEquals(0, tokenizer.crossSegmentCopyCount)
        assertTrue(tokenizer.retainedLength <= chunkSize)
    }

    @Test
    fun denseAttributesDoNotRepeatedlyConcatenateSegments() {
        // Allocation baseline for dense attribute data: attribute pieces emitted
        // at write boundaries never pull earlier pieces with them.
        val tokenizer = KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {}
        )
        tokenizer.write("<div ")
        repeat(300) { index ->
            tokenizer.write("a$index=\"v\" ")
        }
        tokenizer.write(">x</div>")
        tokenizer.end()

        assertEquals(0, tokenizer.crossSegmentCopyCount)
        assertTrue(tokenizer.retainedLength <= 32)
    }

    @Test
    fun crossSegmentSliceIsPossibleButBounded() {
        // A slice requested for a token spanning exactly two adjacent writes
        // (an entity that starts in one chunk and ends in the next) does copy,
        // but only the token itself — never the whole stream.
        val source = "x".repeat(5_000) + "&amp;" + "y".repeat(5_000)
        val cut = source.indexOf("&amp;") + 2 // inside the entity name
        val events = mutableListOf<RawEvent>()
        val tokenizer = KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {
                override fun onText(start: Int, endIndex: Int) {
                    events.add(RawEvent("Text", listOf(start, endIndex)))
                }
                override fun onTextEntity(codepoint: Int, endIndex: Int) {
                    events.add(RawEvent("TextEntity", listOf(codepoint, endIndex)))
                }
            }
        )
        tokenizer.write(source.substring(0, cut))
        tokenizer.write(source.substring(cut))
        tokenizer.end()

        // The entity slice "&am" + "p;" spans two segments: exactly 5 units.
        assertTrue(tokenizer.crossSegmentCopyCount in 1..5,
            "cross-segment copy bounded to the entity span, was ${tokenizer.crossSegmentCopyCount}")
        val normalized = normalize(events, source)
        val text = normalized.filterIsInstance<NormEvent.TextData>().joinToString("") { it.value }
        assertEquals("x".repeat(5_000) + "&" + "y".repeat(5_000), text)
    }
}
