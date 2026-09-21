package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.tokenizer.runTokenizerChunked
import com.mohamedrejeb.ksoup.html.tokenizer.runTokenizerFull
import com.mohamedrejeb.ksoup.html.tokenizer.normalizeTokenEvents
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization tests pinning behavior discovered in the legacy
 * implementation, so the position/lifecycle refactor cannot silently
 * change error-tolerance policy.
 *
 * Malformed short comments (`<!-->`, `<!--->`) make the tokenizer
 * report negative/inverted spans. On the JVM that span is sliced against
 * retained input and historically surfaces as [IndexOutOfBoundsException]
 * (see KsoupHtmlLegacyJvmBehaviorTest); on JS, native substring semantics
 * differ. HTML error-handling policy is intentionally NOT changed by the
 * cursor refactor; these tests document the raw tokenizer behavior, which
 * must remain independent of how the input is chunked.
 */
class KsoupHtmlLegacyBehaviorTest {

    private val malformedShortComments = listOf("<!--->", "<!--->x", "<!-->x")

    @Test
    fun tokenizerRawSpansForMalformedCommentsAreChunkingIndependent() {
        // At the raw tokenizer boundary the inverted/negative spans are the
        // historical output; full and chunked parsing must produce the same
        // raw event stream (the crash only happens when slicing the span).
        for (input in malformedShortComments) {
            val full = runTokenizerFull(input).second.events
            for (cut in 1 until input.length) {
                val chunked = runTokenizerChunked(input, intArrayOf(cut)).second.events
                assertEquals(full, chunked, "input=$input cut=$cut")
            }
        }
    }

    /**
     * Historical behavior was that a named entity split across chunks
     * threw [IndexOutOfBoundsException] because the tokenizer discarded
     * prior chunks but still indexed them cumulatively. The cursor is the
     * single segment owner precisely so this can no longer happen; this
     * test guards the corrected lifecycle (entities are resolved against
     * retained data), not a change to entity-decoding policy.
     */
    @Test
    fun crossChunkNamedEntitiesNoLongerCrashAndMatchFullInput() {
        val input = "<p>a&amp;b&copy;c</p>"
        val full = normalizeTokenEvents(runTokenizerFull(input).second.events)
        for (cut in 1 until input.length) {
            val chunked = normalizeTokenEvents(
                runTokenizerChunked(input, intArrayOf(cut)).second.events
            )
            assertEquals(full, chunked, "cut=$cut")
        }
    }

    @Test
    fun unknownEntitiesKeepLegacyLeadingAmpersandOutput() {
        // Legacy decoder policy, preserved verbatim:
        // `&unknown;` -> "&" (rest handled as raw text), same in full/chunked.
        val input = "<p>&unknown;</p>"
        val full = normalizeHandlerEvents(runParserFull(input).second.events)
        val chunked = normalizeHandlerEvents(runParserChunked(input, intArrayOf(4)).second.events)
        assertEquals(full, chunked)
        val text = full.filter { it.name == "text" }.joinToString("") { it.text ?: "" }
        assertEquals("&", text)
    }

    @Test
    fun numericEntitiesAreNotDecodedLegacyPolicy() {
        // The port does not decode numeric entities; that policy is unchanged.
        val input = "&#169;&#xA9;&#128512;"
        val full = normalizeHandlerEvents(runParserFull(input).second.events)
        val chunked = normalizeHandlerEvents(
            runParserChunked(input, intArrayOf(5)).second.events
        )
        assertEquals(full, chunked)
        assertEquals(
            input,
            full.filter { it.name == "text" }.joinToString("") { it.text ?: "" },
        )
    }

    @OptIn(ExperimentalKsoupApi::class)
    @Test
    fun tokenizerDoubleEndKeepsLegacyRepeatedOnEnd() {
        var endCount = 0
        val tokenizer = com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizer(
            KsoupHtmlOptions.Default,
            object : com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizerCallbacks {
                override fun onEnd() { endCount++ }
            },
        )
        tokenizer.write("abc")
        tokenizer.end()
        tokenizer.end()
        tokenizer.end()
        assertEquals(3, endCount)
    }
}
