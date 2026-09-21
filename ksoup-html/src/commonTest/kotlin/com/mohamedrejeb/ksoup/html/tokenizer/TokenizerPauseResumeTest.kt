package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * While the tokenizer/parser is paused:
 *  - the cursor must not advance and no callbacks may fire;
 *  - resume flushes buffered chunks without re-emitting anything already seen;
 *  - end() requested during a pause is delivered after the flush, once.
 */
@OptIn(ExperimentalKsoupApi::class)
class TokenizerPauseResumeTest {

    @Test
    fun tokenizerPausesAndResumesWithoutReplay() {
        val events = mutableListOf<String>()
        val tokenizer = KsoupTokenizer(
            KsoupHtmlOptions.Default,
            object : KsoupTokenizerCallbacks {
                override fun onText(start: Int, endIndex: Int) { events.add("text($start,$endIndex)") }
                override fun onOpenTagName(start: Int, endIndex: Int) { events.add("open($start,$endIndex)") }
                override fun onOpenTagEnd(endIndex: Int) { events.add("openEnd($endIndex)") }
                override fun onCloseTag(start: Int, endIndex: Int) { events.add("close($start,$endIndex)") }
                override fun onEnd() { events.add("end") }
            },
        )

        tokenizer.write("ab")
        val cursorAfterFirstWrite = tokenizer.debugCursorPosition()
        tokenizer.pause()
        tokenizer.write("<p>cd")
        tokenizer.write("ef</p>gh")
        // Paused writes are not parsed: the cursor stays put and no event for
        // the new bytes is emitted.
        assertEquals(cursorAfterFirstWrite, tokenizer.debugCursorPosition())
        assertEquals(listOf("text(0,2)"), events)
        tokenizer.resume()
        tokenizer.end()

        assertEquals(
            listOf(
                "text(0,2)",
                "open(3,4)",
                "openEnd(4)",
                "text(5,7)",
                "text(7,9)",
                "close(11,12)",
                "text(13,15)",
                "end",
            ),
            events,
        )
        // Resume continues strictly after the last parsed position.
        assertEquals(15, tokenizer.debugCursorPosition())
    }

    @Test
    fun parserQueuesWritesWhilePausedAndFlushesOnceOnResume() {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(
            handler = KsoupHtmlHandler.Builder()
                .onText { events.add("text:'$it'") }
                .onOpenTag { name, attrs, _ -> events.add("open:$name:$attrs") }
                .onCloseTag { name, implied -> events.add("close:$name:$implied") }
                .onEnd { events.add("end") }
                .build()
        )

        parser.write("<p>ab")
        parser.pause()
        parser.write("cd")
        parser.write("</p>x")
        parser.resume()
        parser.end()

        assertEquals(
            listOf(
                "open:p:{}",
                "text:'ab'",
                "text:'cd'",
                "close:p:false",
                "text:'x'",
                "end",
            ),
            events,
        )
    }

    @Test
    fun endRequestedWhilePausedIsDeliveredAfterResume() {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(
            handler = KsoupHtmlHandler.Builder()
                .onText { events.add("text:$it") }
                .onEnd { events.add("end") }
                .build()
        )

        parser.write("ab")
        parser.pause()
        parser.write("cd")
        parser.end()
        assertEquals(emptyList(), events) // nothing emitted while paused
        parser.resume()

        assertEquals(listOf("text:ab", "text:cd", "end"), events)
    }
}
