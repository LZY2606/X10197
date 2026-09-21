package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization tests for pause/resume/end lifecycle behavior:
 *  - while paused, no callbacks fire and input is buffered
 *  - resume does not re-emit callbacks
 *  - consecutive end() calls and write() after end() report errors
 */
@OptIn(ExperimentalKsoupApi::class)
class ParserLifecycleContractTest {

    private class Recorder {
        val events = mutableListOf<String>()

        fun handlerBuilder(): KsoupHtmlHandler.Builder {
            return KsoupHtmlHandler.Builder()
                .onOpenTagName { name -> events.add("openTagName:$name") }
                .onOpenTag { name, _, implied -> events.add("openTag:$name:$implied") }
                .onText { text -> events.add("text:$text") }
                .onCloseTag { name, implied -> events.add("closeTag:$name:$implied") }
                .onEnd { events.add("end") }
                .onError { error -> events.add("error:${error.message}") }
        }
    }

    private fun parseUninterrupted(input: String): List<String> {
        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.write(input)
        parser.end()
        return recorder.events
    }

    @Test
    fun pauseStopsEventsAndResumeDoesNotDuplicate() {
        val input = "<div><p>one</p><p>two</p><p>three</p></div>"
        val extra = "<span>buffered</span>"
        val reference = parseUninterrupted(input + extra)

        val events = mutableListOf<String>()
        lateinit var parser: KsoupHtmlParser
        var paused = false
        val h = KsoupHtmlHandler.Builder()
            .onOpenTagName { name ->
                events.add("openTagName:$name")
                if (name == "p" && !paused) {
                    paused = true
                    parser.pause()
                }
            }
            .onOpenTag { name, _, implied -> events.add("openTag:$name:$implied") }
            .onText { text -> events.add("text:$text") }
            .onCloseTag { name, implied -> events.add("closeTag:$name:$implied") }
            .onEnd { events.add("end") }
            .onError { error -> events.add("error:${error.message}") }
            .build()
        parser = KsoupHtmlParser(handler = h)

        parser.write(input)
        // Paused inside the first <p> open tag name. The tokenizer only checks the
        // pause flag between characters, so the rest of the current character's
        // work (the `>` handling that emits onOpenTag) still completes.
        val eventsAfterPause = events.toList()
        assertEquals(
            listOf("openTagName:div", "openTag:div:false", "openTagName:p", "openTag:p:false"),
            eventsAfterPause,
        )

        // Writing while paused buffers input without emitting events.
        parser.write(extra)
        assertEquals(eventsAfterPause, events)

        // Resume finishes the whole input; events match the uninterrupted run exactly once.
        parser.resume()
        parser.end()
        assertEquals(reference, events)
    }

    @Test
    fun pauseInsideTextCallback() {
        val input = "<p>hello world</p><b>after</b>"
        val reference = parseUninterrupted(input)

        val events = mutableListOf<String>()
        lateinit var parser: KsoupHtmlParser
        var paused = false
        val handler = KsoupHtmlHandler.Builder()
            .onOpenTagName { name -> events.add("openTagName:$name") }
            .onOpenTag { name, _, implied -> events.add("openTag:$name:$implied") }
            .onText { text ->
                events.add("text:$text")
                if (!paused) {
                    paused = true
                    parser.pause()
                }
            }
            .onCloseTag { name, implied -> events.add("closeTag:$name:$implied") }
            .onEnd { events.add("end") }
            .onError { error -> events.add("error:${error.message}") }
            .build()
        parser = KsoupHtmlParser(handler = handler)

        parser.write(input)
        // Paused after the first text event; nothing further fired.
        assertEquals(
            listOf("openTagName:p", "openTag:p:false", "text:hello world"),
            events,
        )
        parser.resume()
        parser.end()
        assertEquals(reference, events)
    }

    @Test
    fun endWhilePausedFinishesOnResume() {
        val input = "<p>one</p><p>two</p>"
        val reference = parseUninterrupted(input)

        val events = mutableListOf<String>()
        lateinit var parser: KsoupHtmlParser
        var paused = false
        val handler = KsoupHtmlHandler.Builder()
            .onOpenTagName { name ->
                events.add("openTagName:$name")
                if (name == "p" && !paused) {
                    paused = true
                    parser.pause()
                }
            }
            .onOpenTag { name, _, implied -> events.add("openTag:$name:$implied") }
            .onText { text -> events.add("text:$text") }
            .onCloseTag { name, implied -> events.add("closeTag:$name:$implied") }
            .onEnd { events.add("end") }
            .onError { error -> events.add("error:${error.message}") }
            .build()
        parser = KsoupHtmlParser(handler = handler)

        parser.write(input)
        parser.end()
        // Paused: end() must not emit the trailing events yet.
        assertEquals(events.none { it == "end" }, true)

        parser.resume()
        assertEquals(reference, events)
    }

    @Test
    fun consecutiveEndCallsTriggerErrorOnce() {
        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.write("<p>x</p>")
        parser.end()
        parser.end()
        parser.end()
        assertEquals(
            listOf(
                "openTagName:p", "openTag:p:false", "text:x",
                "closeTag:p:false", "end",
                "error:.end() after done!",
                "error:.end() after done!",
            ),
            recorder.events,
        )
    }

    @Test
    fun writeAfterEndTriggersError() {
        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.end("<p>x</p>")
        parser.write("<p>y</p>")
        assertEquals(
            listOf(
                "openTagName:p", "openTag:p:false", "text:x",
                "closeTag:p:false", "end",
                "error:.write() after done!",
            ),
            recorder.events,
        )
    }

    @Test
    fun resumeWithoutPauseIsNoOp() {
        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.write("<p>x</p>")
        parser.resume()
        parser.resume()
        parser.end()
        assertEquals(
            listOf("openTagName:p", "openTag:p:false", "text:x", "closeTag:p:false", "end"),
            recorder.events,
        )
    }

    @Test
    fun endWithChunkEqualsWriteThenEnd() {
        val input = "<div><p>chunked</p></div>"
        val reference = parseUninterrupted(input)

        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.end(input)
        assertEquals(reference, recorder.events)
    }

    @Test
    fun parseCompleteEqualsWriteThenEnd() {
        val input = "<div><p>complete</p></div>"
        val reference = parseUninterrupted(input)

        val recorder = Recorder()
        val parser = KsoupHtmlParser(handler = recorder.handlerBuilder().build())
        parser.parseComplete(input)
        assertEquals(reference, recorder.events)
    }
}
