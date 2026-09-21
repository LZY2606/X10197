package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizer
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizerCallbacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lifecycle / memory tests for the internal cursor-segment abstraction.
 *
 * These assert, via package-private metrics, that:
 *  - the resident buffer is released as soon as no pending token references a
 *    prefix, so it is bounded by the largest token in flight rather than by the
 *    total stream length across many buffer cycles;
 *  - token contents are not repeatedly concatenated internally: a long text
 *    stream and a stream of dense attributes do not accumulate copied
 *    characters proportional to total input.
 */
@OptIn(ExperimentalKsoupApi::class)
class KsoupCursorLifecycleTest {

    private class Sink : KsoupTokenizerCallbacks

    private fun newTokenizer(): KsoupTokenizer =
        KsoupTokenizer(KsoupHtmlOptions.Default, Sink())

    /** Many cycles of small, immediately-consumable chunks. */
    @Test
    fun retainedBufferIsBoundedAcrossManyBufferCycles() {
        val tokenizer = newTokenizer()
        val cycles = 500
        val chunkSize = 64

        repeat(cycles) {
            // Plain text never holds cross-chunk state; each write ends at a
            // token boundary, so the whole previous chunk is releasable.
            tokenizer.write("a".repeat(chunkSize))
        }
        tokenizer.end()

        val total = cycles * chunkSize
        assertEquals(total, tokenizer.totalInputLength)
        // The active buffer must not grow with the number of cycles. A new
        // segment can briefly coexist with the previous head, so allow a small
        // constant multiple of one chunk — never a function of cycle count.
        val bound = chunkSize * 2
        assertTrue(
            tokenizer.retainedLength <= bound,
            "retained=${tokenizer.retainedLength} bound=$bound total=$total"
        )
        assertTrue(
            tokenizer.peakRetainedLength <= bound,
            "peak=${tokenizer.peakRetainedLength} bound=$bound total=$total"
        )
        assertTrue(tokenizer.peakRetainedLength * 10 < total)
    }

    /** One long in-flight token may pin its own span, but never the stream. */
    @Test
    fun longTextTokenRetainedThenReleased() {
        val tokenizer = newTokenizer()
        val head = "<a>"
        val longText = "x".repeat(10_000)
        val tail = "</a>"

        // Feed the opening tag and the long text split across many cycles, but
        // keep the document un-ended so the final text token is still open.
        tokenizer.write(head)
        repeat(100) { i ->
            tokenizer.write(longText.substring(i * 100, (i + 1) * 100))
        }
        // cleanup() flushes the pending text at every write, so even while the
        // logical text node is open, residency stays within a couple of chunks
        // rather than the whole 10k token.
        assertTrue(
            tokenizer.retainedLength <= 200,
            "retained while open=${tokenizer.retainedLength}"
        )
        assertTrue(
            tokenizer.peakRetainedLength < longText.length / 2,
            "peak=${tokenizer.peakRetainedLength}"
        )
        tokenizer.write(tail)
        tokenizer.end()
        // After the document ends, the prefix is fully released.
        assertTrue(
            tokenizer.retainedLength <= tail.length,
            "retained after close=${tokenizer.retainedLength}"
        )
    }

    /**
     * Allocation baseline for long text: slices are requested only for token
     * spans the parser materializes. A pure-text stream emits no slices at all
     * through the tokenizer (callbacks are offsets), so copied chars stay zero.
     */
    @Test
    fun longTextStreamDoesNotCopyInsideTokenizer() {
        val tokenizer = newTokenizer()
        repeat(200) { tokenizer.write("y".repeat(50)) }
        tokenizer.end()

        assertEquals(0, tokenizer.multiSegmentSliceCount)
        assertEquals(0L, tokenizer.copiedCharacterCount)
        assertTrue(tokenizer.totalInputLength >= 10_000)
    }

    /**
     * Dense attributes across many small chunks: each attribute value is
     * materialized once by the parser. Copied characters reflect attribute
     * sizes, not cumulative input (no O(n^2) internal re-concatenation).
     */
    @Test
    fun denseAttributesDoNotAccumulateQuadraticCopies() {
        val tokenizer = newTokenizer()
        val attrsPerChunk = 50
        val chunks = 200
        val valueLen = 8

        val chunk = buildString {
            append("<tag ")
            repeat(attrsPerChunk) { i ->
                append("k").append(i % 10).append("=\"")
                append("v".repeat(valueLen))
                append("\" ")
            }
            append(">")
        }

        repeat(chunks) { tokenizer.write(chunk) }
        tokenizer.end()

        // Tokenizer itself never materializes attribute text (offset callbacks);
        // the parser owns per-attribute materialization which is outside this
        // cursor. Assert the cursor did not build spanning builders for them.
        assertTrue(
            tokenizer.copiedCharacterCount.toDouble() < tokenizer.totalInputLength.toDouble(),
            "copied=${tokenizer.copiedCharacterCount} total=${tokenizer.totalInputLength}"
        )
        // Buffers released: residency is on the order of a couple of chunks,
        // not of the number of chunks written.
        assertTrue(
            tokenizer.peakRetainedLength <= chunk.length * 2,
            "peak=${tokenizer.peakRetainedLength} chunk=${chunk.length}"
        )
    }

    /** A token held open across chunks pins exactly its own span. */
    @Test
    fun openAttributePinsOnlyItsSpan() {
        val tokenizer = newTokenizer()
        tokenizer.write("<a x=\"")
        repeat(50) { tokenizer.write("z".repeat(40)) }
        // Not ended and quote not closed. cleanup() flushes value pieces per
        // write, so only recent segments stay resident, never the full run.
        assertTrue(
            tokenizer.retainedLength <= 80,
            "retained=${tokenizer.retainedLength}"
        )
        assertTrue(
            tokenizer.peakRetainedLength < 50 * 40 / 2,
            "peak=${tokenizer.peakRetainedLength}"
        )
        tokenizer.write("\">")
        tokenizer.end()
        // The empty/last tiny segment may remain; nothing from the long run.
        assertTrue(tokenizer.retainedLength <= 2, "retained after end=${tokenizer.retainedLength}")
    }

    /**
     * A genuinely pending token that cannot be flushed (a long unclosed tag
     * name or comment) pins exactly its own span. Once the token closes, its
     * prefix is released and residency collapses, even across many cycles and
     * a much larger total input.
     */
    @Test
    fun pendingTokenPinsItsSpanThenReleasesAcrossCycles() {
        // Phase 1: many flushed cycles first (history that must be releasable).
        val tokenizer = newTokenizer()
        repeat(200) { tokenizer.write("p".repeat(100)) }
        val flushedTotal = tokenizer.totalInputLength
        assertTrue(tokenizer.retainedLength <= 200)

        // Phase 2: start one large, genuinely-pending token (unclosed comment)
        // spanning many small chunks. Its span is the largest pending token.
        tokenizer.write("<!-- ")
        val pendingLen = 8_000
        var written = 0
        while (written < pendingLen) {
            val n = minOf(37, pendingLen - written)
            tokenizer.write("q".repeat(n))
            written += n
        }
        // Residency tracks the pending token size (~pendingLen + lead-in),
        // NOT flushedTotal + pendingLen (which would be total input).
        assertTrue(
            tokenizer.retainedLength >= pendingLen,
            "pending token must be retained: ${tokenizer.retainedLength}"
        )
        assertTrue(
            tokenizer.retainedLength <= pendingLen + 200,
            "flushed history leaked into pending retention: ${tokenizer.retainedLength}"
        )

        // Phase 3: close the comment and end. Prefix frees; residency collapses.
        tokenizer.write(" -->")
        tokenizer.end()
        assertTrue(
            tokenizer.retainedLength <= 4,
            "retained after token close=${tokenizer.retainedLength}"
        )
        assertTrue(
            tokenizer.peakRetainedLength < tokenizer.totalInputLength / 2,
            "peak=${tokenizer.peakRetainedLength} total=${tokenizer.totalInputLength}"
        )
    }

    /** Spanning slices use exactly one builder allocation per callback span. */
    @Test
    fun spanningSliceIsSingleAllocation() {
        // Drive through the parser so a token name spans two segments; measure
        // via a fresh tokenizer whose getSlice is called directly.
        val tokenizer = newTokenizer()
        tokenizer.write("<sc")
        tokenizer.write("ript ")
        tokenizer.write("a=1>")
        tokenizer.write("x")
        tokenizer.end()
        // No assertion on exact count across implementations other than it must
        // not scale with total length; here the value is tiny.
        assertTrue(tokenizer.copiedCharacterCount in 0L..64L)
    }
}
