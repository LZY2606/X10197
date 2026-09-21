package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization contract for the cursor refactor:
 *
 * - Token streams of a complete write and of any chunking are identical
 *   after normalization (token type, decoded value, attribute order,
 *   absolute start/end offsets, EOF position).
 * - Cut points stress surrogate pairs, CRLF, entities (including entity
 *   boundaries), comment endings, script data and foreign content.
 */
class KsoupTokenizerContractTest {

    private val htmlOptions = KsoupHtmlOptions.Default
    private val xmlOptions = KsoupHtmlOptions(
        xmlMode = true,
        decodeEntities = true,
        lowerCaseTags = false,
        lowerCaseAttributeNames = false,
        recognizeCDATA = true,
        recognizeSelfClosing = true,
    )

    private val inputs = listOf(
        // Surrogate pair (😀 is U+1F600, two UTF-16 units) with plain text.
        "ab\uD83D\uDE00cd",
        // Lone surrogates on either side, plus a split pair opportunity.
        "x\uD83Dy\uDE00z\uD83D\uDE00",
        // CRLF straddling token boundaries.
        "<p>a\r\nb</p><p>\r\n</p>",
        // Named, numeric-looking and unknown entities.
        "<p>&amp; &copy; a&amp;b &#169; &unknown; &CounterClockwiseContourIntegral;</p>",
        // Entities in double/single/unquoted attributes, incl. order.
        "<a b=\"x&amp;y\" c='a&copy;b' d=z&amp;q e>v</a>",
        // Comment endings, including the short/long forms.
        "a<!--c-->b<!---->c<!--->d<!---x--->e",
        // Comment split right along its end sequence.
        "<!--ab-->",
        // Script data, including a literal closing-looking sequence.
        "<script>var s='</script>'; if(a<b){}</script>x",
        // Style data.
        "<style>a > b {}</style>y",
        // Title data with entity.
        "<title>a&amp;b</title>z",
        // Foreign content with CDATA.
        "<svg><![CDATA[a]b]] & ]]></svg><math/>",
        // Declaration / processing instruction / bogus comment.
        "<!DOCTYPE html><?pi data?></x ><!",
        // Attributes densely packed.
        "<div a='1' b=\"2\" c=3 d e = f g></div>",
        // Incomplete trailing constructs swallowed at EOF.
        "<p>abc<div attr=\"unclosed",
    )

    private fun allSingleCuts(length: Int): List<IntArray> =
        (1 until length).map { intArrayOf(it) }

    private fun everyPairCuts(length: Int): List<IntArray> {
        val result = mutableListOf<IntArray>()
        for (i in 1 until length) {
            for (j in i + 1 until length) {
                // Limit combinatorial size while covering adjacency.
                if (j == i + 1 || (i + j) % 7 == 0) {
                    result += intArrayOf(i, j)
                }
            }
        }
        return result
    }

    private fun assertEquivalent(
        label: String,
        input: String,
        cuts: IntArray,
        options: KsoupHtmlOptions,
    ) {
        val full = runTokenizerFull(input, options).second.events
        val chunked = runTokenizerChunked(input, cuts, options).second.events

        val normalizedFull = normalizeTokenEvents(full)
        val normalizedChunked = normalizeTokenEvents(chunked)

        assertEquals(
            normalizedFull,
            normalizedChunked,
            "token stream mismatch for '$label' cuts=${cuts.toList()} input=${debug(input)}",
        )

        // EOF position: the final event is onEnd, and every terminal
        // callback reports offsets consistent with the input length.
        assertEquals("end", normalizedFull.last().name)
        val expectedEof = input.length
        normalizedFull.dropLast(1).lastOrNull()?.let { lastToken ->
            assertTrue(
                lastToken.end <= expectedEof,
                "token end past EOF for '$label': ${lastToken.end} > $expectedEof",
            )
        }
    }

    private fun debug(value: String): String =
        value.replace("\r", "\\r").replace("\n", "\\n")

    @Test
    fun fullAndChunkedStreamsAreEquivalentHtml() {
        for (input in inputs) {
            for (cuts in allSingleCuts(input.length)) {
                assertEquivalent("html", input, cuts, htmlOptions)
            }
        }
    }

    @Test
    fun fullAndChunkedStreamsAreEquivalentXml() {
        for (input in inputs) {
            for (cuts in allSingleCuts(input.length)) {
                assertEquivalent("xml", input, cuts, xmlOptions)
            }
        }
    }

    @Test
    fun multipleChunksAreEquivalent() {
        // Representative adjacent multi-cuts; the full pair matrix runs on JVM.
        for (input in inputs) {
            val end = minOf(input.length, 24)
            for (i in 1 until end) {
                for (j in i + 1 until minOf(end, i + 4)) {
                    assertEquivalent("multi", input, intArrayOf(i, j), htmlOptions)
                }
            }
        }
    }

    @Test
    fun oneCharacterChunksAreEquivalent() {
        for (input in inputs) {
            val cuts = IntArray(input.length - 1) { it + 1 }
            assertEquivalent("charwise", input, cuts, htmlOptions)
        }
    }

    @Test
    fun surrogatePairIsNotSplitByTokenSpans() {
        // The pair occupies offsets 2..4; cutting between high/low (3)
        // must still decode to the same code unit pair with equal spans.
        val input = "ab\uD83D\uDE00cd"
        val full = runTokenizerFull(input).second.events
        val chunked = runTokenizerChunked(input, intArrayOf(3)).second.events
        assertEquals(normalizeTokenEvents(full), normalizeTokenEvents(chunked))
        assertEquals("\uD83D\uDE00", normalizeTokenEvents(full).first().value!!.substring(2, 4))
    }

    @Test
    fun loneSurrogatesArePreservedVerbatim() {
        val loneHigh = "x\uD83Dy"
        val loneLow = "x\uDE00y"
        for (input in listOf(loneHigh, loneLow)) {
            val full = normalizeTokenEvents(runTokenizerFull(input).second.events)
            val chunked = normalizeTokenEvents(
                runTokenizerChunked(input, intArrayOf(2)).second.events
            )
            assertEquals(full, chunked)
            assertEquals(input, full.first().value)
        }
    }

    @Test
    fun crlfCutProducesEqualAbsoluteSpans() {
        val input = "a\r\nb"
        val full = normalizeTokenEvents(runTokenizerFull(input).second.events)
        val atCr = normalizeTokenEvents(runTokenizerChunked(input, intArrayOf(2)).second.events)
        val atLf = normalizeTokenEvents(runTokenizerChunked(input, intArrayOf(3)).second.events)
        assertEquals(full, atCr)
        assertEquals(full, atLf)
    }

    @Test
    fun commentEndingCutPointsAreEquivalent() {
        val input = "<!--ab-->"
        // Cuts inside/around the final `-->`.
        for (cut in intArrayOf(5, 6, 7, 8)) {
            val full = normalizeTokenEvents(runTokenizerFull(input).second.events)
            val chunked = normalizeTokenEvents(runTokenizerChunked(input, intArrayOf(cut)).second.events)
            assertEquals(full, chunked, "cut=$cut")
        }
    }

    @Test
    fun scriptDataCutPointsAreEquivalent() {
        val input = "<script>ab</script>c"
        for (cut in 1 until input.length) {
            val full = normalizeTokenEvents(runTokenizerFull(input).second.events)
            val chunked = normalizeTokenEvents(runTokenizerChunked(input, intArrayOf(cut)).second.events)
            assertEquals(full, chunked, "cut=$cut")
        }
    }

    @Test
    fun foreignContentCdataCutPointsAreEquivalent() {
        val input = "<svg><![CDATA[ab]]></svg>"
        for (cut in 1 until input.length) {
            val full = normalizeTokenEvents(
                runTokenizerFull(input, KsoupHtmlOptions(xmlMode = true)).second.events
            )
            val chunked = normalizeTokenEvents(
                runTokenizerChunked(input, intArrayOf(cut), KsoupHtmlOptions(xmlMode = true)).second.events
            )
            assertEquals(full, chunked, "cut=$cut")
        }
    }

    @Test
    fun emptyInputHasOnlyEndAtZero() {
        val (tokenizer, recorder) = runTokenizerFull("")
        assertEquals(listOf(TokenEvent("end")), recorder.events)
        assertEquals(0, tokenizer.activeChars)
    }
}

/** Explicit absolute EOF-position contract for trailing data callbacks. */
class KsoupTokenizerEofPositionTest {

    private fun trailingEvents(input: String, options: KsoupHtmlOptions = KsoupHtmlOptions.Default): List<TokenEvent> {
        return runTokenizerFull(input, options).second.events
    }

    @Test
    fun trailingTextReportsAbsoluteEof() {
        val input = "<p>abc"
        val events = trailingEvents(input)
        val text = events.last { it.name == "text" }
        assertEquals(3, text.start)
        assertEquals(input.length, text.end)
    }

    @Test
    fun unterminatedCommentReportsAbsoluteEof() {
        val input = "<!--abcdef"
        val events = trailingEvents(input)
        val comment = events.single { it.name == "comment" }
        assertEquals(4, comment.start)
        assertEquals(input.length, comment.end)
        assertEquals(0, comment.extra)
    }

    @Test
    fun unterminatedCdataReportsAbsoluteEof() {
        val input = "<![CDATA[abc"
        val events = trailingEvents(input, KsoupHtmlOptions(xmlMode = true))
        val cdata = events.single { it.name == "cdata" }
        assertEquals(input.length, cdata.end)
    }

    @Test
    fun unterminatedTagEmitsNoTagEventAndEofIsInputLength() {
        val input = "<p>ab<span"
        val events = trailingEvents(input)
        // The dangling tag is dropped (no openTagName for span); text up
        // to it is emitted; onEnd terminates at the absolute EOF.
        assertEquals(TokenEvent("end"), events.last())
        // No token claims a span at/past EOF; the dangling tag is dropped
        // by the existing error-tolerance policy (not re-evaluated here).
        assertTrue(events.filter { it.name != "end" }.all { it.end <= input.length })
        // The open <p> is the last completed structural event.
        assertEquals(2, events.last { it.name == "openTagEnd" }.end)
    }

    @Test
    fun eofPositionMatchesUtf16LengthWithSurrogates() {
        val input = "x\uD83D\uDE00y"
        val events = trailingEvents(input)
        val text = events.first { it.name == "text" }
        assertEquals(4, input.length)
        assertEquals(0, text.start)
        assertEquals(4, text.end)
    }
}
