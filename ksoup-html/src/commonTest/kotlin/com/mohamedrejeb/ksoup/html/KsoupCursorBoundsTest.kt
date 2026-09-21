package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streaming bounds for the cursor/segment lifecycle:
 *
 * - Retained input is released once no pending token references it, so the
 *   active buffer stays related to the largest pending token instead of
 *   growing linearly with total input across many buffer cycles.
 * - Cross-segment slicing does not repeatedly rebuild token strings; the
 *   copy counter stays linear in input for long text and dense attributes.
 */
class KsoupCursorBoundsTest {

    private val cycles = 400
    private val chunkSize = 64

    private fun newParser(): KsoupHtmlParser =
        KsoupHtmlParser(handler = KsoupHtmlHandler.Default)

    @Test
    fun longPlainTextStreamReleasesPrefixesAcrossBufferCycles() {
        val parser = newParser()
        parser.reset()

        val chunk = "a".repeat(chunkSize)
        var maxActive = 0
        repeat(cycles) {
            parser.write(chunk)
            maxActive = maxOf(maxActive, parser.activeChars)
        }
        parser.end()

        val totalLength = cycles * chunkSize
        // Active buffer must not be linear in the total streamed length.
        assertTrue(
            parser.peakActiveChars <= chunkSize.toLong() * 4,
            "peakActiveChars=${parser.peakActiveChars} total=$totalLength",
        )
        assertTrue(
            maxActive <= chunkSize * 4,
            "maxActive observed=$maxActive total=$totalLength",
        )
        assertTrue(totalLength > parser.peakActiveChars * 50)
        // Plain text never crosses a segment boundary while slicing.
        assertTrue(parser.copiedChars == 0, "copiedChars=${parser.copiedChars}")
    }

    @Test
    fun longStreamOfSmallTagsBoundsActiveBufferToLargestPendingToken() {
        val parser = newParser()
        parser.reset()

        // Each chunk is self-contained markup, so no token spans chunks.
        val chunk = "<p>xx</p>".repeat(chunkSize / "<p>xx</p>".length + 1).take(chunkSize)
        repeat(cycles) {
            parser.write(chunk)
        }
        parser.end()

        val totalLength = cycles * chunk.length
        assertTrue(
            parser.peakActiveChars <= chunk.length.toLong() * 4,
            "peakActiveChars=${parser.peakActiveChars} total=$totalLength",
        )
        assertTrue(totalLength > parser.peakActiveChars * 50)
    }

    @Test
    fun oneLargePendingCommentIsRetainedUntilClosedThenReleased() {
        val parser = newParser()
        parser.reset()

        // An unfinished comment suppresses per-write text emission, so it
        // legitimately spans every chunk: while pending, the whole comment
        // is the largest pending token and stays live.
        parser.write("<!--")
        repeat(cycles) {
            parser.write("a".repeat(chunkSize))
        }
        // The `<!--` opener itself is releasable once sectionStart moves
        // past it; the comment body is what stays live.
        assertTrue(
            parser.activeChars == cycles * chunkSize,
            "pending comment must stay live: ${parser.activeChars}",
        )

        // Closing the comment releases every referenced prefix; only the
        // final chunk remains briefly until end() drains the stream.
        parser.write("-->")
        parser.end()
        assertTrue(
            parser.activeChars <= chunkSize.toLong() * 2,
            "activeChars after close=${parser.activeChars}",
        )
    }

    @Test
    fun longTextCopyBaselineIsLinear() {
        // Force each text token to span two segments by cutting plain text
        // mid-token (entity-free). The copied amount must be proportional
        // to the streamed bytes, not quadratic from repeated concatenation.
        val parser = newParser()
        parser.reset()
        parser.write("<p>")
        val piece = "z".repeat(chunkSize)
        repeat(cycles) {
            parser.write(piece)
        }
        parser.write("</p>")
        parser.end()

        val totalText = cycles * chunkSize
        // Every cross-boundary character is copied a small constant times;
        // a quadratic implementation would be cycles times larger.
        assertTrue(
            parser.copiedChars <= totalText.toLong() * 2,
            "copiedChars=${parser.copiedChars} totalText=$totalText",
        )
    }

    @Test
    fun denseAttributesDoNotConcatenateRepeatedly() {
        // Many attributes inside one large opening tag, streamed in small
        // chunks: attribute values are emitted as spans by the tokenizer
        // and assembled once by the parser per attribute.
        val parser = newParser()
        parser.reset()

        val attributes = buildString {
            repeat(2000) { index ->
                append(" a").append(index).append("='v").append(index).append("'")
            }
        }
        val tag = "<div$attributes>"
        for (start in tag.indices step 31) {
            parser.write(tag.substring(start, minOf(start + 31, tag.length)))
        }
        parser.write("x")
        parser.end()

        // Copies stay within a small constant factor of tag length.
        assertTrue(
            parser.copiedChars <= tag.length.toLong() * 2,
            "copiedChars=${parser.copiedChars} tag=${tag.length}",
        )
    }

    @Test
    fun longStreamOfEntitiesReleasesResolvedEntitySpans() {
        // Every chunk holds closed text with a mix of named entities; once
        // decoded, the entity span must no longer pin previous segments.
        val parser = newParser()
        parser.reset()
        val chunk = "a&amp;b&copy;c".repeat(5)
        repeat(cycles) {
            parser.write(chunk)
        }
        parser.end()
        assertTrue(
            parser.peakActiveChars <= chunk.length.toLong() * 4,
            "peakActiveChars=${parser.peakActiveChars}",
        )
    }

    @Test
    fun emptyChunksDoNotDisturbOffsetsOrRetention() {
        val parser = newParser()
        parser.reset()
        parser.write("")
        parser.write("<p>a")
        parser.write("")
        parser.write("b</p>")
        parser.write("")
        parser.end()
        assertTrue(parser.activeChars == 0, "activeChars=${parser.activeChars}")

        val expected = normalizeHandlerEvents(runParserFull("<p>ab</p>").second.events)
        val handler2 = com.mohamedrejeb.ksoup.html.RecordingHandler()
        val parser2 = KsoupHtmlParser(handler = handler2)
        parser2.reset()
        parser2.write("")
        parser2.write("<p>a")
        parser2.write("")
        parser2.write("b</p>")
        parser2.end()
        assertEquals(expected, normalizeHandlerEvents(handler2.events))
    }

    @Test
    fun activeBufferDropsAfterEachClosedTokenInParser() {
        val handler = object : KsoupHtmlHandler {}
        val parser = KsoupHtmlParser(handler = handler)
        parser.reset()

        repeat(50) { iteration ->
            parser.write("<p>xxxxxxxxxxxxxxxx</p>")
            // Between writes, prior segments must already be releasable.
            assertTrue(
                parser.activeChars <= 64,
                "iteration=$iteration activeChars=${parser.activeChars}",
            )
        }
        parser.end()
    }
}
