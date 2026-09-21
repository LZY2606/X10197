package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterization of pause/resume and end() behavior around the cursor.
 *
 * While paused the cursor must not advance and no additional callbacks
 * may fire; resuming must not replay callbacks that already fired. Repeated
 * `end()` keeps the legacy tokenizer contract of re-running the trailing
 * handler each call.
 */
@OptIn(ExperimentalKsoupApi::class)
class KsoupTokenizerLifecycleTest {

    private fun newTokenizer(): Pair<KsoupTokenizer, RecordingTokenizerCallbacks> {
        lateinit var recorder: RecordingTokenizerCallbacks
        val tokenizer = KsoupTokenizer(KsoupHtmlOptions.Default, object : KsoupTokenizerCallbacks {
            override fun onAttribData(start: Int, endIndex: Int) = recorder.onAttribData(start, endIndex)
            override fun onAttribEntity(codepoint: Int) = recorder.onAttribEntity(codepoint)
            override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) =
                recorder.onAttribEnd(quote, endIndex)
            override fun onAttribName(start: Int, endIndex: Int) = recorder.onAttribName(start, endIndex)
            override fun onCData(start: Int, endIndex: Int, offset: Int) =
                recorder.onCData(start, endIndex, offset)
            override fun onCloseTag(start: Int, endIndex: Int) = recorder.onCloseTag(start, endIndex)
            override fun onComment(start: Int, endIndex: Int, offset: Int) =
                recorder.onComment(start, endIndex, offset)
            override fun onDeclaration(start: Int, endIndex: Int) = recorder.onDeclaration(start, endIndex)
            override fun onEnd() = recorder.onEnd()
            override fun onOpenTagEnd(endIndex: Int) = recorder.onOpenTagEnd(endIndex)
            override fun onOpenTagName(start: Int, endIndex: Int) = recorder.onOpenTagName(start, endIndex)
            override fun onProcessingInstruction(start: Int, endIndex: Int) =
                recorder.onProcessingInstruction(start, endIndex)
            override fun onSelfClosingTag(endIndex: Int) = recorder.onSelfClosingTag(endIndex)
            override fun onText(start: Int, endIndex: Int) = recorder.onText(start, endIndex)
            override fun onTextEntity(codepoint: Int, endIndex: Int) =
                recorder.onTextEntity(codepoint, endIndex)
        })
        recorder = RecordingTokenizerCallbacks(tokenizer)
        return tokenizer to recorder
    }

    @Test
    fun pausedWritesDoNotAdvanceAndResumeContinuesOnce() {
        val (tokenizer, recorder) = newTokenizer()
        tokenizer.write("<p>ab")
        val beforePause = recorder.events.toList()
        assertTrue(beforePause.isNotEmpty())

        tokenizer.pause()
        assertFalse(tokenizer.running)

        // Chunks appended while paused are retained but never parsed.
        tokenizer.write("cd</p>")
        tokenizer.write("<p>xy</p>")
        assertEquals(beforePause, recorder.events, "no callbacks may fire while paused")

        tokenizer.resume()
        assertTrue(tokenizer.running)

        // resume() only drains appended data; end() finalizes the stream.
        tokenizer.end()

        val full = normalizeTokenEvents(runTokenizerFull("<p>ab" + "cd</p>" + "<p>xy</p>").second.events)
        val resumed = normalizeTokenEvents(recorder.events)
        assertEquals(full, resumed, "resume continues exactly where parsing stopped")
    }

    @Test
    fun resumeWithoutNewDataDoesNotReplayCallbacks() {
        val (tokenizer, recorder) = newTokenizer()
        tokenizer.write("<p>hello")
        val atPause = recorder.events.toList()

        tokenizer.pause()
        tokenizer.resume()
        assertEquals(atPause, recorder.events)

        tokenizer.write(" world</p>")
        tokenizer.end()
        assertEquals(
            normalizeTokenEvents(runTokenizerFull("<p>hello world</p>").second.events),
            normalizeTokenEvents(recorder.events),
        )
    }

    @Test
    fun pauseInsideCallbackThenResumeEmitsEverySpanOnce() {
        val events = mutableListOf<String>()
        var pauseOnce = true
        lateinit var tokenizer: KsoupTokenizer
        tokenizer = KsoupTokenizer(KsoupHtmlOptions.Default, object : KsoupTokenizerCallbacks {
            override fun onText(start: Int, endIndex: Int) {
                events += "text($start,$endIndex)"
                if (pauseOnce) {
                    pauseOnce = false
                    tokenizer.pause()
                }
            }
            override fun onOpenTagName(start: Int, endIndex: Int) { events += "open($start,$endIndex)" }
            override fun onOpenTagEnd(endIndex: Int) { events += "openEnd($endIndex)" }
            override fun onCloseTag(start: Int, endIndex: Int) { events += "close($start,$endIndex)" }
            override fun onEnd() { events += "end" }
        })

        tokenizer.write("ab")
        val whilePaused = events.toList()
        // The pause happens during the onText of the first write.
        tokenizer.write("cd")
        assertEquals(whilePaused, events, "write while paused must not parse")

        tokenizer.resume()
        tokenizer.end()

        // Every absolute text span appears exactly once; the chunk
        // boundary legitimately splits the text into adjacent spans that
        // together cover [0,4) without overlap or replay.
        val textSpans = events.filter { it.startsWith("text(") }
        assertEquals(listOf("text(0,2)", "text(2,4)"), textSpans)
        val merged = normalizeTokenEvents(runTokenizerFull("abcd").second.events)
            .filter { it.name == "text" }
        assertEquals(1, merged.size)
        assertEquals(0, merged.first().start)
        assertEquals(4, merged.first().end)
        assertEquals(listOf("end"), events.subList(events.size - 1, events.size))
    }

    @Test
    fun repeatedEndRunsTrailingHandlerAgainOnTokenizer() {
        // Legacy tokenizer contract: end() is unguarded and re-emits onEnd.
        val (tokenizer, recorder) = newTokenizer()
        tokenizer.write("abc")
        tokenizer.end()
        val afterFirstEnd = recorder.events.toList()
        tokenizer.end()
        assertEquals(afterFirstEnd.size + 1, recorder.events.size)
        assertEquals(2, recorder.endCount)
        assertEquals(TokenEvent("end"), recorder.events.last())
    }

    @Test
    fun repeatedEndOnEmptyInputEmitsEndTwiceOnTokenizer() {
        val (tokenizer, recorder) = newTokenizer()
        tokenizer.end()
        tokenizer.end()
        assertEquals(listOf(TokenEvent("end"), TokenEvent("end")), recorder.events)
    }

    @Test
    fun resetClearsCursorState() {
        val (tokenizer, recorder) = newTokenizer()
        tokenizer.write("<p>ab")
        tokenizer.reset()
        assertTrue(tokenizer.running)
        // Reset drops all retained segments; nothing is pending.
        assertEquals(0, tokenizer.activeChars)
        // New parse uses absolute offsets starting back at zero.
        val (freshTokenizer, freshRecorder) = newTokenizer()
        freshTokenizer.write("xy")
        freshTokenizer.end()
        assertEquals(0, freshRecorder.events.first { it.name == "text" }.start)
        assertEquals(0, freshTokenizer.activeChars)
    }
}
