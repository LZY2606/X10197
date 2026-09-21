package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parser-level characterization contract for the cursor refactor:
 * handler results (tag/text/comment payloads, attribute order) must be
 * identical for a complete parse and any chunking of the same input.
 *
 * Inputs that triggered the pre-existing malformed-comment crash are
 * covered separately in [KsoupHtmlLegacyBehaviorTest].
 */
class KsoupHtmlStreamingContractTest {

    private val inputs = listOf(
        "",
        "<p>Hello, world!</p>",
        "ab\uD83D\uDE00cd\uD83D lone \uDE00",
        "<p>a\r\nb</p><p>\rx\n</p>",
        "<p>&amp; &copy; a&amp;b &#169; &unknown;</p>",
        "<a B=\"x&amp;y\" A='a&copy;b' Z=z&amp;q D>v</a>",
        "a<!--c-->b<!---->c<!---x--->e",
        "<script>var s='</script>'; if(a<b){}</script>x",
        "<style>a > b {}</style><title>t&amp;t</title>",
        "<svg><![CDATA[a]b]] & ]]></svg><math/>",
        "<!DOCTYPE html><?pi data?></x >",
        "<div a='1' b=\"2\" c=3 d e = f g><p>nested</p></div>",
        "<p>abc<div attr=\"unclosed",
        TestData.htmlString,
    )

    private fun assertEquivalent(label: String, input: String, cuts: IntArray,
                                 options: KsoupHtmlOptions = KsoupHtmlOptions.Default) {
        val full = normalizeHandlerEvents(runParserFull(input, options).second.events)
        val chunked = normalizeHandlerEvents(runParserChunked(input, cuts, options).second.events)
        assertEquals(full, chunked, "handler mismatch for '$label' cuts=${cuts.toList()}")
        // EOF contract: onEnd is the final event.
        assertEquals(HandlerEvent("end"), full.last())
    }

    @Test
    fun everySingleCutIsEquivalent() {
        for ((idx, input) in inputs.withIndex()) {
            if (input.isEmpty()) continue
            // The large fixture document only gets sampled cuts; the
            // smaller documents cover every boundary exhaustively.
            val cuts = if (input === TestData.htmlString) {
                // A fixed spread of cuts in the large document; the small
                // fixtures below cover every boundary exhaustively.
                listOf(1, 31, 127, 509, 1021, 2049, 4093, 8007)
                    .filter { it < input.length }
            } else {
                (1 until input.length).toList()
            }
            for (cut in cuts) {
                assertEquivalent("input-$idx", input, intArrayOf(cut))
            }
        }
    }

    @Test
    fun oneCharacterChunksAreEquivalent() {
        for ((idx, input) in inputs.withIndex()) {
            if (input.length < 2 || input === TestData.htmlString) continue
            assertEquivalent("charwise-$idx", input, IntArray(input.length - 1) { it + 1 })
        }
    }

    @Test
    fun largeDocumentAcrossManyChunksIsEquivalent() {
        val input = TestData.htmlString
        val chunkSize = 4099
        val cuts = (chunkSize until input.length step chunkSize).toList().toIntArray()
        assertEquivalent("large", input, cuts)
    }

    @Test
    fun attributeOrderIsPreservedAcrossChunks() {
        val input = "<input z=\"1\" y='2' x=3 w v />"
        for (cut in 1 until input.length) {
            val events = runParserChunked(input, intArrayOf(cut)).second.events
            val attrs = events.filter { it.name == "attribute" }.map { it.attrName }
            assertEquals(listOf("z", "y", "x", "w", "v"), attrs, "cut=$cut")
        }
    }

    @Test
    fun foreignContentSelfClosingAndCdataAcrossChunks() {
        val input = "<svg a='1'><![CDATA[xy]]></svg>"
        val full = normalizeHandlerEvents(
            runParserFull(input, KsoupHtmlOptions(xmlMode = true)).second.events
        )
        for (cut in 1 until input.length) {
            val chunked = normalizeHandlerEvents(
                runParserChunked(input, intArrayOf(cut), KsoupHtmlOptions(xmlMode = true)).second.events
            )
            assertEquals(full, chunked, "cut=$cut")
        }
    }

    @Test
    fun writeEndVariantsProduceSameDocument() {
        val input = "<p>ab&amp;c</p>"
        val a = run {
            val handler = RecordingHandler()
            val parser = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser(handler = handler)
            parser.reset()
            parser.write("<p>ab")
            parser.write("&amp;c</p>")
            parser.end()
            normalizeHandlerEvents(handler.events)
        }
        val b = run {
            val handler = RecordingHandler()
            val parser = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser(handler = handler)
            parser.reset()
            parser.write("<p>ab&amp;c</p>")
            parser.end()
            normalizeHandlerEvents(handler.events)
        }
        val c = normalizeHandlerEvents(runParserFull(input).second.events)
        assertEquals(c, a)
        assertEquals(c, b)
    }

    @Test
    fun parserPauseBuffersChunksAndResumeReplaysNothing() {
        val handler = RecordingHandler()
        val parser = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser(handler = handler)
        parser.reset()
        parser.write("<p>ab")
        parser.pause()
        parser.write("cd</p>")
        parser.write("<p>xy</p>")
        val whilePaused = handler.events.toList()
        parser.resume()
        parser.end()
        assertEquals(whilePaused.count { it.name == "text" }, 1)

        val expected = normalizeHandlerEvents(
            runParserFull("<p>abcd</p><p>xy</p>").second.events
        )
        assertEquals(expected, normalizeHandlerEvents(handler.events))
    }

    @Test
    fun parserEndAfterEndReportsErrorAndDoesNotReemit() {
        val handler = RecordingHandler()
        val parser = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser(handler = handler)
        parser.reset()
        parser.write("<p>a</p>")
        parser.end()
        val eventsAfterFirstEnd = handler.events.toList()
        parser.end()
        // Legacy parser contract: second end() is an error, no new parse events.
        assertEquals(eventsAfterFirstEnd, handler.events.dropLast(1))
        assertEquals(HandlerEvent("error", error = ".end() after done!"), handler.events.last())
        assertEquals(1, handler.events.count { it.name == "end" })
    }

    @Test
    fun parserWriteAfterEndReportsErrorAndIsIgnored() {
        val handler = RecordingHandler()
        val parser = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser(handler = handler)
        parser.reset()
        parser.end()
        val before = handler.events.toList()
        parser.write("<p>ignored</p>")
        assertEquals(before + HandlerEvent("error", error = ".write() after done!"), handler.events)
    }
}
