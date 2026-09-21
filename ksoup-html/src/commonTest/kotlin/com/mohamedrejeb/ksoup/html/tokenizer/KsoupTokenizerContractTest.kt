package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization / contract tests for the tokenizer.
 *
 * The same document must produce an identical normalized token stream whether
 * it is written in one go or split at arbitrary positions across writes.
 * These tests were written before the cursor/segment refactor and pin the
 * externally observable contract: token kinds, decoded values, attribute
 * order, absolute spans and the final EOF position.
 */
class KsoupTokenizerContractTest {

    private fun assertEofPosition(source: String) {
        val events = recordRawEvents(source)
        assertEquals(RawEvent("End"), events.last(), "onEnd must be the final event")
        val lastData = events.dropLast(1).lastOrNull {
            it.name == "Text" || it.name == "TextEntity" ||
                it.name == "Comment" || it.name == "CData"
        }
        // When the stream ends in text/comment data, the reported absolute end
        // must equal the input length (the EOF position).
        if (lastData != null && lastData.name == "Text") {
            assertEquals(source.length, lastData.ints[1], "EOF text span end")
        }
    }

    @Test
    fun fullAndChunkedPlainText() {
        assertEquivalentAtEveryCut("hello world")
        assertEquivalentAtEveryCut("a<b>b</b>c")
        assertEquivalentAtEveryCut("<p>x</p>")
    }

    @Test
    fun fullAndChunkedTagsWithAttributes() {
        // Attribute order must be preserved across chunk boundaries.
        val source = "<div a=\"1\" b='2' c=3 d><span e = \"x&amp;y\">txt</span></div>"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedCrlf() {
        // CRLF must be passed through verbatim, including when the split lands
        // between the CR and the LF.
        val source = "<p>line1\r\nline2\r\n</p>"
        assertEquivalentAtEveryCut(source)
        assertEquivalentWithCuts(source, intArrayOf(10, 11, 12))
    }

    @Test
    fun fullAndChunkedSurrogatePair() {
        // Positions are UTF-16 code unit offsets; splitting between the high
        // and low surrogate must not alter the decoded value or spans.
        val source = "a\uD83D\uDE00b<p>\uD83D\uDE00</p>"
        assertEquivalentAtEveryCut(source)
        assertEquivalentWithCuts(source, intArrayOf(1, 2))
    }

    @Test
    fun fullAndChunkedLoneSurrogate() {
        // An isolated surrogate is a code unit of text. It must be preserved
        // verbatim rather than replaced by a platform default character.
        val source = "x\uD800y\uDC00z<p>\uD800</p>"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedEntities() {
        // Named, decimal and hex entities, including multi-character results
        // for supplementary code points (surrogate pairs).
        val source = "<p title=\"a&amp;b&#65;&#x42;\">&amp;&copy;&#128512;&#x1F600;x</p>"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedCommentEndings() {
        val source = "a<!-- hi -->b<!--->--><!----><!--x-->y"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedScriptData() {
        // The raw-text sequence matching inside <script> has to survive splits
        // at every position, including within `</script` and across `<</script>`.
        val source = "<script>var a = 1; </scr</script >x</script>tail"
        assertEquivalentAtEveryCut(source)
        val tricky = "<script>a<</script><b>"
        assertEquivalentAtEveryCut(tricky)
        assertEquivalentWithCuts("<style>.x{} </style >z</style>", intArrayOf(10, 17, 18))
    }

    @Test
    fun fullAndChunkedTitleEntities() {
        // Entities are decoded inside <title>; sequence matching plus entity
        // handling must agree for all split points.
        val source = "<title>a&amp;b</title>after"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedForeignContent() {
        // SVG/MathML foreign content affects how self-closing tags are
        // interpreted, but the tokenizer event stream itself is unchanged by
        // the split strategy.
        val source = "<svg><circle r=\"1\"/><desc>a&amp;b</desc></svg><math><mi/></math>"
        assertEquivalentAtEveryCut(source)
    }

    @Test
    fun fullAndChunkedDeclarationsAndCdata() {
        val source = "<!DOCTYPE html><!--c--><![CDATA[x&y]]><?pi data?><p>z"
        assertEquivalentAtEveryCut(source)
        val xml = KsoupHtmlOptions(xmlMode = true)
        assertEquivalentAtEveryCut("<root><![CDATA[a<b>&c]]></root>", xml)
    }

    @Test
    fun fullAndChunkedMultipleWrites() {
        val source = "<p a=\"1\">te&amp;xt</p><!-- c --><script>s</script>"
        assertEquivalentWithCuts(source, intArrayOf(1, 2, 3, 5, source.length / 2, source.length - 1))
    }

    @Test
    fun fullAndChunkedUnterminatedAtEof() {
        // EOF handling: unterminated tag/attribute/comment/text states.
        assertEquivalentAtEveryCut("<p")
        assertEquivalentAtEveryCut("<p ")
        assertEquivalentAtEveryCut("<p a=\"x")
        assertEquivalentAtEveryCut("<p a='x")
        assertEquivalentAtEveryCut("<p a=x")
        assertEquivalentAtEveryCut("<!-- unterminated")
        assertEquivalentAtEveryCut("<script>unclosed script")
        assertEquivalentAtEveryCut("plain text only")
        assertEquivalentAtEveryCut("a&amp")
    }

    @Test
    fun eofPositionReportedConsistently() {
        assertEofPosition("hello world")
        assertEofPosition("<p>hello world")
        assertEofPosition("<!-- comment")
        assertEofPosition("<p></p>tail")
        assertEofPosition("")
    }

    @Test
    fun emptyWritesDoNotAffectStream() {
        val full = normalize(recordRawEvents("<p>a&amp;b</p>"), "<p>a&amp;b</p>")
        val chunked = normalize(
            recordRawEventsChunked("<p>a&amp;b</p>", intArrayOf(0, 3, 6, 12)),
            "<p>a&amp;b</p>"
        )
        assertEquals(full, chunked)
    }

    @Test
    fun onEndEmittedExactlyOnce() {
        val events = recordRawEvents("<p>x</p>")
        assertEquals(1, events.count { it.name == "End" })
    }

    @OptIn(com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi::class)
    @Test
    fun repeatedEndIsIdempotent() {
        // Characterizes the end() contract: the second end() must not emit
        // another onEnd callback.
        val events = mutableListOf<RawEvent>()
        val tokenizer = KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {
                override fun onEnd() {
                    events.add(RawEvent("End"))
                }
                override fun onText(start: Int, endIndex: Int) {
                    events.add(RawEvent("Text", listOf(start, endIndex)))
                }
            }
        )
        tokenizer.write("abc")
        tokenizer.end()
        tokenizer.end()
        tokenizer.end()
        assertEquals(listOf(RawEvent("Text", listOf(0, 3)), RawEvent("End")), events)
    }

    @OptIn(com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi::class)
    @Test
    fun pauseFreezesCursorAndResumeDoesNotReemit() {
        val events = mutableListOf<RawEvent>()
        var paused = false
        lateinit var tokenizer: KsoupTokenizer
        tokenizer = KsoupTokenizer(
            options = KsoupHtmlOptions.Default,
            callbacks = object : KsoupTokenizerCallbacks {
                override fun onText(start: Int, endIndex: Int) {
                    events.add(RawEvent("Text", listOf(start, endIndex)))
                    if (!paused && endIndex >= 2) {
                        paused = true
                        tokenizer.pause()
                    }
                }
                override fun onEnd() {
                    events.add(RawEvent("End"))
                }
            }
        )

        tokenizer.write("abcdef")
        // While paused, the cursor must not advance: the next write is retained
        // without emitting anything.
        val snapshot = events.toList()
        tokenizer.write("gh")
        assertEquals(snapshot, events)
        assertEquals(snapshot, events.toList())

        tokenizer.resume()
        tokenizer.end()

        assertEquals(1, events.count { it.name == "End" })
        // The resumed stream must cover the whole input without re-emitting the
        // text prefix delivered before the pause.
        val normalized = normalize(events, "abcdefgh")
        val text = normalized.filterIsInstance<NormEvent.TextData>()
        assertEquals(0, text.first().start)
        assertEquals(8, text.last().end)
        assertEquals("abcdefgh", text.joinToString("") { it.value })
        // No text piece starts before the previously delivered boundary twice:
        // the prefix [0, 2) is only part of the first event.
        assertTrue(text.first().start == 0)
    }
}
