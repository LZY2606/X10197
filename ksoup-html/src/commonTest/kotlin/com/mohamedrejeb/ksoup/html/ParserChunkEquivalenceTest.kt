package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Public-parser contract: feeding a document in one `end(data)` call versus
 * arbitrary `write` chunking must produce the exact same handler sequence.
 */
class ParserChunkEquivalenceTest {

    private data class Event(
        val name: String,
        val args: List<Any?>,
    )

    private fun recordingHandler(events: MutableList<Event>): KsoupHtmlHandler =
        object : KsoupHtmlHandler {
            override fun onEnd() { events.add(Event("end", emptyList())) }
            override fun onError(error: Exception) { events.add(Event("error", listOf(error.message))) }
            override fun onCloseTag(name: String, isImplied: Boolean) {
                events.add(Event("close", listOf(name, isImplied)))
            }
            override fun onOpenTagName(name: String) {
                events.add(Event("openName", listOf(name)))
            }
            override fun onAttribute(name: String, value: String, quote: String?) {
                events.add(Event("attr", listOf(name, value, quote)))
            }
            override fun onOpenTag(name: String, attributes: Map<String, String>, isImplied: Boolean) {
                events.add(Event("open", listOf(name, LinkedHashMap(attributes), isImplied)))
            }
            override fun onText(text: String) { events.add(Event("text", listOf(text))) }
            override fun onComment(comment: String) { events.add(Event("comment", listOf(comment))) }
            override fun onCDataStart() { events.add(Event("cdataStart", emptyList())) }
            override fun onCDataEnd() { events.add(Event("cdataEnd", emptyList())) }
            override fun onCommentEnd() { events.add(Event("commentEnd", emptyList())) }
            override fun onProcessingInstruction(name: String, data: String) {
                events.add(Event("pi", listOf(name, data)))
            }
        }

    private fun parseFull(source: String, options: KsoupHtmlOptions): List<Event> {
        val events = mutableListOf<Event>()
        KsoupHtmlParser(recordingHandler(events), options).parseComplete(source)
        return events
    }

    private fun parseChunked(
        source: String,
        cuts: IntArray,
        options: KsoupHtmlOptions,
    ): List<Event> {
        val events = mutableListOf<Event>()
        val parser = KsoupHtmlParser(recordingHandler(events), options)
        parser.reset()
        var previous = 0
        for (cut in cuts) {
            parser.write(source.substring(previous, cut))
            previous = cut
        }
        parser.end(source.substring(previous))
        return events
    }

    private fun assertEquivalentEveryCut(
        source: String,
        options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
    ) {
        val full = parseFull(source, options)
        for (cut in 1 until source.length) {
            val chunked = parseChunked(source, intArrayOf(cut), options)
            assertEquals(full, chunked, "Parser mismatch with cut at $cut")
        }
    }

    @Test
    fun parserEquivalentWithEveryCutComplexDocument() {
        val source = """
            <!DOCTYPE html>
            <html lang="en">
            <head><title>T&amp;B</title><script>if(a<b){c()</script></head>
            <body>
            <h1 class="x y" id="h">Hi &#128512;</h1>
            <svg><circle r="1"/><desc>d&amp;e</desc></svg>
            <table><tr><td>a</td><td>b</td></tr></table>
            <!-- a comment --><![CDATA[raw & data]]>
            <p>line1
            line2<p>nested
            <img src="x.png" alt="a&amp;b"/>
            <textarea>raw & text</textarea>
            </body>
            </html>
        """.trimIndent()
        assertEquivalentEveryCut(source)
    }

    @Test
    fun parserEquivalentWithMultipleWritesAndXml() {
        val source = "<root><child a=\"1\" b=\"2&amp;3\"/><![CDATA[x<y & z]]></root>"
        val options = KsoupHtmlOptions(xmlMode = true)
        val full = parseFull(source, options)
        val chunked = parseChunked(source, intArrayOf(1, 7, 13, 20, source.length - 2), options)
        assertEquals(full, chunked)
    }

    @Test
    fun parserAttributeOrderPreservedAcrossChunks() {
        val source = "<div z=\"1\" y=\"2\" x=\"3\" w></div>"
        assertEquivalentEveryCut(source)
    }

    @Test
    fun pauseAndResumeProducesSameEvents() {
        val source = "<p>one</p><p>two&amp;three</p><!-- c --><script>x<y</script><p>four"
        for (pauseAt in 1 until source.length) {
            val full = parseFull(source, KsoupHtmlOptions.Default)

            val events = mutableListOf<Event>()
            val parser = KsoupHtmlParser(recordingHandler(events), KsoupHtmlOptions.Default)
            parser.reset()
            parser.pause()
            parser.write(source.substring(0, pauseAt))
            val whilePaused = events.toList()
            // No events may be emitted while paused.
            assertEquals(emptyList(), whilePaused, "Events emitted while paused at $pauseAt")
            parser.resume()
            parser.write(source.substring(pauseAt))
            parser.end()

            assertEquals(full, events, "Pause/resume mismatch at $pauseAt")
        }
    }

    @Test
    fun endBeforeResumeThenResume() {
        // end() while paused must remember the final chunk; resume() drains and
        // finishes exactly once.
        val source = "<p>a&amp;b</p>"
        val full = parseFull(source, KsoupHtmlOptions.Default)

        val events = mutableListOf<Event>()
        val parser = KsoupHtmlParser(recordingHandler(events), KsoupHtmlOptions.Default)
        parser.reset()
        parser.pause()
        parser.write(source)
        parser.end()
        assertEquals(emptyList(), events)
        parser.resume()
        assertEquals(full, events)
    }

    @Test
    fun repeatedEndReportsErrorOnceAndDoesNotReemit() {
        val full = parseFull("<p>x</p>", KsoupHtmlOptions.Default)

        val events = mutableListOf<Event>()
        val parser = KsoupHtmlParser(recordingHandler(events), KsoupHtmlOptions.Default)
        parser.reset()
        parser.end("<p>x</p>")
        parser.end()
        parser.end()

        val errors = events.count { it.name == "error" }
        assertEquals(2, errors, "Every end() after the first reports one error")
        // Exactly one document's worth of events, aside from the two errors.
        assertEquals(
            full.size,
            events.size - errors,
            "No document events may be re-emitted on repeated end()"
        )
    }

    @Test
    fun multipleWritesWhilePausedDrainInOrder() {
        val source = "<p>a&amp;b</p><!--x--><script>1<2</script>"
        val full = parseFull(source, KsoupHtmlOptions.Default)

        val events = mutableListOf<Event>()
        val parser = KsoupHtmlParser(recordingHandler(events), KsoupHtmlOptions.Default)
        parser.reset()
        parser.pause()
        // All writes happen while paused, including the entity split.
        parser.write(source.substring(0, 5))
        parser.write(source.substring(5, 11))
        parser.write(source.substring(11))
        assertEquals(emptyList(), events)
        parser.end()
        parser.resume()

        assertEquals(full, events)
    }

    @Test
    fun pauseResumePauseCycle() {
        val source = "<p>aa&amp;bb</p><p>cc&amp;dd</p>"
        val full = parseFull(source, KsoupHtmlOptions.Default)

        val events = mutableListOf<Event>()
        val parser = KsoupHtmlParser(recordingHandler(events), KsoupHtmlOptions.Default)
        parser.reset()
        parser.pause()
        parser.write(source.substring(0, 4))
        parser.end(source.substring(4, 12))
        parser.resume()
        // A second pause/resume after end() was already requested must not
        // finish twice.
        parser.pause()
        parser.resume()
        parser.write(source.substring(12)) // write after end() reports error
        parser.end()

        val errors = events.count { it.name == "error" }
        assertEquals(1, errors, "only the write()/end() after done error")
        assertEquals(full, events.filterNot { it.name == "error" })
    }
}
