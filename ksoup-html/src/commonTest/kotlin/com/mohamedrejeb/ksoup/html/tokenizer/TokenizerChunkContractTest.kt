package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract: feeding the complete input at once, or feeding it split at
 * arbitrary boundaries, produces the same token stream.
 *
 * Checks cover token type, decoded value, attribute order, absolute start/end
 * offsets and the EOF position. Cut points exercise surrogate pairs, CRLF,
 * entities, comment endings, script data and foreign content.
 *
 * Chunking is allowed to split delivery of a single text/attribute token, so
 * adjacent same-kind data events are merged by [normalize]; the merged span and
 * decoded value must still be identical.
 */
class TokenizerChunkContractTest {

    private val inputs = listOf(
        // Surrogate pairs (astral code point U+1F600), including one at EOF
        "a\uD83D\uDE00b",
        "x\uD83D\uDE00",
        // Isolated surrogates
        "a\uD83Db",
        "a\uDE00b",
        "\uD83D",
        "\uDE00",
        // CRLF line endings, including a split between CR and LF
        "a\r\nb\r\nc",
        "\r\n",
        // Entities named, numeric, in attributes, and unmatched
        "a&copy;b&#65;d&amp;e&unknown;f",
        "<a href=\"?a=1&amp;b=2\" title='&lt;&gt;'>x</a>",
        "noval&noSemicolon tail",
        // Comment endings, including the tricky long-dash and abrupt EOF cases
        "<!--x-->y<!---->z<!--ab- ->-->",
        "<!-- unterminated comment",
        "<!--->",
        // CDATA
        "<![CDATA[abc]]>tail",
        "<![CDATA[never ends",
        // Script data with embedded angle brackets and a near-miss sequence
        "<script>var a = 1 < 2; </scr</script>tail",
        "<style>.x > .y {}</style>after",
        "<title>a&amp;b</title>t",
        // Foreign content: self-closing recognition depends on context
        "<svg><circle r=\"1\"/></svg><math><mi/></math>",
        // Dense attributes, quotes, entities, whitespace quirks
        "<div a=\"1\" b='2' c=3 d e = 'x&amp;y' />&amp;</div>",
        // Declaration / processing instruction
        "<!DOCTYPE html><?pi data?>",
    )

    @Test
    fun wholeAndFixedChunksAreEquivalent() {
        inputs.forEach { input ->
            val whole = tokenizeWhole(input)
            for (chunkSize in 1..8) {
                val chunked = tokenizeChunked(input, chunkSize)
                assertNormalizedEquals(whole, chunked, "input=${input.debug()} chunkSize=$chunkSize")
            }
        }
    }

    @Test
    fun everySingleCutPointIsEquivalent() {
        inputs.forEach { input ->
            val whole = tokenizeWhole(input)
            (1 until input.length).forEach { cut ->
                val chunked = tokenizeTwoChunks(input, cut)
                assertNormalizedEquals(whole, chunked, "input=${input.debug()} cut=$cut")
            }
        }
    }

    @Test
    fun entitiesThatSpanChunksDecodeExactlyOnce() {
        val input = "x&CounterClockwiseContourIntegral;y"
        val whole = tokenizeWhole(input)
        for (cut in 1 until input.length) {
            assertNormalizedEquals(whole, tokenizeTwoChunks(input, cut), "entity cut=$cut")
        }
        val decoded = tokenizeTwoChunks(input, 5)
        val entityEvents = decoded.filter { it.name == "textEntity" }
        assertEquals(1, entityEvents.size, "entity must decode exactly once: $decoded")
        assertEquals("\u2233", entityEvents.single().value)
    }

    @Test
    fun surrogatePairsAreNeverSplitAcrossEvents() {
        val input = "x\uD83D\uDE00y"
        val whole = tokenizeWhole(input)
        for (cut in 1 until input.length) {
            val events = tokenizeTwoChunks(input, cut)
            events.forEach { event ->
                if (event.name == "text") {
                    assertTrue(
                        event.value.isEmpty() || !event.value.last().isHighSurrogate(),
                        "dangling high surrogate at cut=$cut event=$event",
                    )
                    assertTrue(
                        event.value.isEmpty() || !event.value.first().isLowSurrogate(),
                        "dangling low surrogate at cut=$cut event=$event",
                    )
                }
            }
            assertNormalizedEquals(whole, events, "surrogate cut=$cut")
        }
    }

    @Test
    fun isolatedSurrogatesAreDeliveredDeterministicallyAtEof() {
        // A high surrogate held at the end of a chunk is retained and joined
        // with the following low surrogate; only a genuinely unpaired unit is
        // delivered as itself at EOF.
        assertNormalizedEquals(
            tokenizeWhole("\uD83D\uDE00"),
            tokenizeTwoChunks("\uD83D\uDE00", 1),
        )
        assertEquals("\uD83D", tokenizeWhole("\uD83D").single { it.name == "text" }.value)
        assertEquals("\uDE00", tokenizeWhole("\uDE00").single { it.name == "text" }.value)
        assertNormalizedEquals(
            tokenizeWhole("\uD83D\uD83D\uDE00"),
            tokenizeTwoChunks("\uD83D\uD83D\uDE00", 1),
        )
    }

    @Test
    fun absoluteOffsetsAndEofPositionAreStable() {
        val input = "<div a=\"1\">ab&copy;cd</div>"
        val whole = tokenizeWhole(input)
        val chunked = tokenizeChunked(input, 3)
        assertNormalizedEquals(whole, chunked)

        // Exactly one EOF event per feeding, no matter the chunking.
        assertEquals(1, whole.count { it.name == "end" })
        assertEquals(1, chunked.count { it.name == "end" })

        // Concrete absolute spans for representative tokens.
        assertEquals(TokenEvent("openTagName", 1, 4, 0, "div"), whole.first { it.name == "openTagName" })
        assertEquals(TokenEvent("attribName", 6, 7, 0, "a"), whole.first { it.name == "attribName" })
        assertEquals(TokenEvent("attribData", 9, 10, 0, "1"), whole.first { it.name == "attribData" })
        assertEquals(TokenEvent("closeTag", 22, 25, 0, "div"), whole.first { it.name == "closeTag" })
        // Decoded entity event points just past the consumed entity span.
        val entity = whole.single { it.name == "textEntity" }
        assertEquals(0xA9, entity.extra)
        assertEquals(18, entity.end)
    }

    @Test
    fun attributeOrderIsPreservedAcrossChunks() {
        val input = "<tag z=\"1\" a='2' m=3 last>"
        val whole = tokenizeWhole(input)
        val chunked = tokenizeTwoChunks(input, 9)
        assertEquals(
            listOf("z", "a", "m", "last"),
            whole.filter { it.name == "attribName" }.map { it.value },
        )
        assertEquals(
            whole.filter { it.name == "attribName" },
            chunked.filter { it.name == "attribName" },
        )
        assertEquals(
            listOf("Double", "Single", "Unquoted", "NoValue"),
            whole.filter { it.name == "attribEnd" }.map { it.value },
        )
    }

    @Test
    fun crlfAndCommentEndCutPointsAreEquivalent() {
        listOf(
            "a\r\nb",
            "<!--x-->y",
            "<!--->",
            "<script>x</script>y",
        ).forEach { input ->
            val whole = tokenizeWhole(input)
            (1 until input.length).forEach { cut ->
                assertNormalizedEquals(whole, tokenizeTwoChunks(input, cut), "cut=$cut input=${input.debug()}")
            }
            assertEquals(1, whole.count { it.name == "end" })
        }
    }

    @Test
    fun xmlModeAlsoHoldsEquivalence() {
        val options = KsoupHtmlOptions(xmlMode = true)
        val input = "<root a=\"x&amp;y\"><child/>te\uD83D\uDE00xt</root>"
        val whole = tokenizeWhole(input, options)
        (1 until input.length).forEach { cut ->
            assertNormalizedEquals(whole, tokenizeTwoChunks(input, cut, options), "xml cut=$cut")
        }
    }

    private fun String.debug(): String =
        this.replace("\r", "\\r").replace("\n", "\\n")
}
