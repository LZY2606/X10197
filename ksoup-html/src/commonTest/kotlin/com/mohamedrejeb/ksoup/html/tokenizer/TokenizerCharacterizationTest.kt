package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization tests pinning the exact pre-refactor token/event shape so
 * the cursor/segment refactor cannot silently change HTML error recovery,
 * namespace handling or public types.
 *
 * Two genuinely broken old behaviors are intentionally NOT pinned here and are
 * covered as new contracts instead (see [TokenizerChunkContractTest]):
 *  - an entity whose source spans two writes threw StringIndexOutOfBounds;
 *  - a surrogate pair split between two writes was delivered as two halves.
 * Both fixes are confined to the new cursor; no HTML tokenization policy
 * (error recovery, foreign content, namespaces) is changed.
 */
@OptIn(ExperimentalKsoupApi::class)
class TokenizerCharacterizationTest {

    @Test
    fun wholeInputEventsAreUnchanged() {
        // Surrogate pair, CRLF, entity, comment, script data, foreign content.
        val input = "<script>x\r&copy;</script><!--c--><svg><b/>\uD83D\uDE00</svg>"
        assertEquals(
            listOf(
                TokenEvent("openTagName", 1, 7, 0, "script"),
                TokenEvent("openTagEnd", 0, 7, 0, ""),
                TokenEvent("text", 8, 11, 0, "x\r\n"),
                TokenEvent("textEntity", 0, 18, 0xA9, "\u00A9"),
                TokenEvent("text", 18, 19, 0, ""),
                TokenEvent("closeTag", 21, 27, 0, "script"),
                TokenEvent("comment", 32, 33, 2, "c"),
                TokenEvent("openTagName", 38, 41, 0, "svg"),
                TokenEvent("openTagEnd", 0, 41, 0, ""),
                TokenEvent("openTagName", 43, 44, 0, "b"),
                TokenEvent("selfClosingTag", 0, 45, 0, ""),
                TokenEvent("closeTag", 48, 51, 0, "svg"),
                TokenEvent("text", 51, 53, 0, "\uD83D\uDE00"),
                TokenEvent("end", 0, 0, 0, ""),
            ),
            tokenizeWhole(input),
        )
    }

    @Test
    fun chunkedEventsAreUnchangedWhenNoSemanticBoundaryIsHit() {
        val input = "<div a=\"1\">ab</div>\r\n<!--c--><script>s</script>"
        val whole = tokenizeWhole(input)
        for (size in 1..8) {
            assertEquals(normalize(whole), normalize(tokenizeChunked(input, size)), "size=$size")
        }
    }

    @Test
    fun unterminatedCommentAtEofIsEmittedVerbatim() {
        val input = "a<!-- never closed"
        assertEquals(
            listOf(
                TokenEvent("text", 0, 1, 0, "a"),
                TokenEvent("comment", 8, 20, 0, " never closed"),
                TokenEvent("end", 0, 0, 0, ""),
            ),
            tokenizeWhole(input),
        )
    }

    @Test
    fun unterminatedTagAtEofProducesNoTagEvents() {
        // The tokenizer signals an ignored incomplete tag by emitting nothing.
        val input = "a<div b=\"x"
        assertEquals(
            listOf(
                TokenEvent("text", 0, 1, 0, "a"),
                TokenEvent("openTagName", 2, 5, 0, "div"),
                TokenEvent("attribName", 6, 7, 0, "b"),
                TokenEvent("attribData", 9, 10, 0, "x"),
                TokenEvent("end", 0, 0, 0, ""),
            ),
            tokenizeWhole(input),
        )
    }

    @Test
    fun foreignContentKeepsSelfClosingRecognition() {
        val input = "<svg><foreignObject><p/></foreignObject></svg>"
        val names = tokenizeWhole(input).filter {
            it.name == "openTagName" || it.name == "selfClosingTag" || it.name == "closeTag"
        }
        assertEquals(
            listOf(
                "openTagName" to "svg",
                "openTagName" to "foreignObject",
                "openTagName" to "p",
                "selfClosingTag" to "",
                "closeTag" to "foreignObject",
                "closeTag" to "svg",
            ),
            names.map { it.name to it.value },
        )
    }

    @Test
    fun callingEndTwiceEmitsOnEndOnlyOnce() {
        val events = mutableListOf<String>()
        val tokenizer = KsoupTokenizer(
            KsoupHtmlOptions.Default,
            object : KsoupTokenizerCallbacks {
                override fun onEnd() { events.add("end") }
            },
        )
        tokenizer.write("a")
        tokenizer.end()
        tokenizer.end()
        assertEquals(listOf("end"), events)
    }

    @Test
    fun parserEndAfterEndReportsErrorWithoutSecondOnEnd() {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(
            handler = com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler.Builder()
                .onEnd { events.add("end") }
                .onError { events.add("error") }
                .build()
        )
        parser.end("a")
        parser.end()
        assertEquals(listOf("end", "error"), events)
    }
}
