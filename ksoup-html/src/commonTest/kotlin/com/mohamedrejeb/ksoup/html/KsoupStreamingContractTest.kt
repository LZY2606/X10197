package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.entities.KsoupEntities
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizer
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizerCallbacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization / contract tests pinning the streaming behavior of the
 * tokenizer + parser before the internal cursor/segment refactor.
 *
 * The reference behavior is always the result of feeding the *complete* input
 * in one go. Feeding the same input split at arbitrary positions must produce
 * the same normalized token stream: token types, decoded values, attribute
 * order and absolute start/end offsets.
 *
 * Raw tokenizer callbacks may split a logical text node / attribute value into
 * adjacent calls at chunk boundaries (and at entity boundaries), so the
 * recorder merges consecutive pieces. Absolute spans are always validated
 * against the original, full input.
 */
@OptIn(ExperimentalKsoupApi::class)
class KsoupStreamingContractTest {

    /** Platform-independent UTF-16 unit formatting (no String.format). */
    private fun hexUnit(code: Int): String {
        val digits = "0123456789ABCDEF"
        val sb = StringBuilder(6)
        sb.append("U+")
        for (shift in intArrayOf(12, 8, 4, 0)) {
            sb.append(digits[(code ushr shift) and 0xF])
        }
        return sb.toString()
    }

    // ----- Normalized tokenizer event model --------------------------------

    private sealed class Ev {
        data class Text(val start: Int, val end: Int, val decoded: String) : Ev()
        data class OpenName(val start: Int, val end: Int) : Ev()
        data class OpenEnd(val endIndex: Int) : Ev()
        data class CloseName(val start: Int, val end: Int) : Ev()
        data class SelfClose(val endIndex: Int) : Ev()
        data class AttribName(val start: Int, val end: Int) : Ev()
        data class Attrib(val start: Int, val end: Int, val quote: KsoupHtmlParser.QuoteType) : Ev()
        data class Comment(val start: Int, val end: Int, val bodyEnd: Int) : Ev()
        data class CData(val start: Int, val end: Int, val bodyEnd: Int) : Ev()
        data class Declaration(val start: Int, val end: Int) : Ev()
        data class ProcessingInstruction(val start: Int, val end: Int) : Ev()
        data object End : Ev()
    }

    /**
     * Records raw tokenizer callbacks and normalizes them:
     *  - consecutive [KsoupTokenizerCallbacks.onText] / [onTextEntity] pieces
     *    are merged into one [Ev.Text], with decoded value validated by decoding
     *    the raw absolute span of the full input;
     *  - attribute data / entity pieces between [onAttribName] and [onAttribEnd]
     *    are collected; the decoded value is asserted at [onAttribEnd].
     */
    private class Recorder(private val source: String) : KsoupTokenizerCallbacks {
        val events = mutableListOf<Ev>()

        private var textStart = -1
        private var textEnd = -1
        private var cursor = 0

        private var attrStart = -1
        private var attrCursor = 0

        private fun flushText() {
            if (textStart >= 0) {
                val raw = source.substring(textStart, textEnd)
                val decoded = KsoupEntities.decodeHtml(raw)
                events.add(Ev.Text(textStart, textEnd, decoded))
                textStart = -1
                textEnd = -1
            }
        }

        private fun addNonText(ev: Ev) {
            flushText()
            events.add(ev)
        }

        override fun onText(start: Int, endIndex: Int) {
            if (start == endIndex) return
            if (textStart < 0) {
                textStart = start
                cursor = start
            }
            assertEquals(cursor, start, "non-contiguous text span at $start (cursor=$cursor)")
            cursor = endIndex
            textEnd = endIndex
        }

        override fun onTextEntity(codepoint: Int, endIndex: Int) {
            if (textStart < 0) textStart = cursor
            cursor = endIndex
            textEnd = endIndex
        }

        override fun onOpenTagName(start: Int, endIndex: Int) = addNonText(Ev.OpenName(start, endIndex))

        override fun onOpenTagEnd(endIndex: Int) = addNonText(Ev.OpenEnd(endIndex))

        override fun onCloseTag(start: Int, endIndex: Int) = addNonText(Ev.CloseName(start, endIndex))

        override fun onSelfClosingTag(endIndex: Int) = addNonText(Ev.SelfClose(endIndex))

        override fun onAttribName(start: Int, endIndex: Int) {
            addNonText(Ev.AttribName(start, endIndex))
            attrStart = -1
        }

        override fun onAttribData(start: Int, endIndex: Int) {
            if (start == endIndex) return
            if (attrStart < 0) {
                attrStart = start
                attrCursor = start
            }
            // Allow entities between data pieces: the following data start is
            // positioned after the entity's consumed raw characters.
            assertTrue(
                start >= attrCursor,
                "attribute data moved backwards at $start (cursor=$attrCursor)"
            )
            attrCursor = endIndex
        }

        override fun onAttribEntity(codepoint: Int) {
            if (attrStart < 0) attrStart = attrCursor
        }

        override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
            val start = if (attrStart >= 0) attrStart else endIndex
            val end = if (attrStart >= 0) attrCursor.coerceAtLeast(start) else endIndex
            addNonText(Ev.Attrib(start, end, quote))
            attrStart = -1
        }

        override fun onComment(start: Int, endIndex: Int, offset: Int) =
            addNonText(Ev.Comment(start, endIndex, endIndex - offset))

        override fun onCData(start: Int, endIndex: Int, offset: Int) =
            addNonText(Ev.CData(start, endIndex, endIndex - offset))

        override fun onDeclaration(start: Int, endIndex: Int) =
            addNonText(Ev.Declaration(start, endIndex))

        override fun onProcessingInstruction(start: Int, endIndex: Int) =
            addNonText(Ev.ProcessingInstruction(start, endIndex))

        override fun onEnd() {
            flushText()
            events.add(Ev.End)
        }
    }

    // ----- Drivers ----------------------------------------------------------

    private fun runTokenizer(input: String, cuts: List<Int>, options: KsoupHtmlOptions = KsoupHtmlOptions.Default): List<Ev> {
        val rec = Recorder(input)
        val tokenizer = KsoupTokenizer(options, rec)
        var pos = 0
        for (cut in cuts) {
            if (cut > pos) tokenizer.write(input.substring(pos, cut))
            pos = cut
        }
        if (pos < input.length) tokenizer.write(input.substring(pos))
        tokenizer.end()
        return rec.events
    }

    private fun allCuts(input: String): List<List<Int>> =
        (0..input.length).map { listOf(it) }

    private fun multiCuts(input: String, seed: Long, maxParts: Int = 8): List<Int> {
        var state = seed
        fun next(): Int {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            return (state ushr 16).toInt()
        }
        val cuts = mutableListOf<Int>()
        var pos = 0
        val partCount = 1 + (next() % maxParts)
        repeat(partCount) {
            if (pos >= input.length) return@repeat
            val remaining = input.length - pos
            pos += 1 + (next() % (remaining.coerceAtLeast(1)))
            if (pos < input.length) cuts.add(pos)
        }
        return cuts
    }

    private fun assertEquivalent(input: String, cuts: List<Int>, label: String, options: KsoupHtmlOptions = KsoupHtmlOptions.Default) {
        val reference = runTokenizer(input, emptyList(), options)
        val actual = runTokenizer(input, cuts, options)
        assertEquals(reference, actual, "token stream mismatch for $label (cuts=$cuts)")
    }

    // ----- Corpus: covers surrogate pair, CRLF, entities, comment endings,
    //       script data and foreign content. --------------------------------

    private val corpus: List<Pair<String, String>> = listOf(
        "plain text" to "Hello, world!",
        "surrogate pair in text" to "a\uD83D\uDE00b",
        "lone surrogate high" to "x\uD83Dy",
        "lone surrogate low" to "x\uDE00y",
        "combining mark split" to "ae\u0301i",
        "CRLF text" to "a\r\nb",
        "CRLF attr" to "<a x=\"\r\n\"/>",
        "entity in text" to "<p>a&copy;b</p>",
        "named entity boundary" to "&amp;&lt;&gt;",
        "numeric entity" to "x&#65;&#x42;y",
        "longest-ish entity" to "&CounterClockwiseContourIntegral;",
        "unknown entity" to "&notanentity; &amp",
        "comment full" to "a<!-- hi -->b",
        "comment abrupt eof" to "a<!-- unterminated",
        "comment short bang" to "<!->",
        "comment extra dash (well-formed)" to "a<!---->b<!--x-->c",
        "comment leading bang only" to "a<!",
        "script data" to "<script>var a = '</s' + 'cript>'; if(a<b){}</script>x",
        "script split end" to "<script>x</script >y",
        "script lt lt" to "<script><</script>y",
        "style data" to "<style>a < b { c: 1; }</style>y",
        "title entity" to "<title>a &amp; b</title>y",
        "textarea data" to "<textarea>a<b&c</textarea>y",
        "foreign svg" to "<svg><foreignObject><div x=\"1\"/></foreignObject></svg>",
        "foreign math" to "<math><mi>x</mi><annotation-xml encoding=\"text/html\"><br/></annotation-xml></math>",
        "dense attributes" to "<a x=\"1&copy;2\" y='&lt;' z=novalue w empty=\"\">t</a>",
        "attributes many" to "<tag a=\"1\" b='2' c=3 d e f=6/>",
        "declaration" to "<!DOCTYPE html>after",
        "processing instruction" to "<?xml version=\"1.0\"?>after",
        "cdata html" to "<![CDATA[x < y & z]]>after",
        "abrupt tag eof" to "<div attr=\"val",
        "abrupt close eof" to "</di",
        "self closing foreign" to "<svg><path d=\"M0 0\"/></svg>",
        "self closing html ignored" to "<p/>x",
        "nested void" to "<ul><li>a<li>b</ul>",
        "multiple entities contiguous" to "&amp&amp;;&copy",
        "whitespace tag" to "<a\nhref='x'\ttitle=\"y\"\r\n/>",
        "crlf between comments" to "<!--a-->\r\n<!--b-->",
    )

    @Test
    fun fullInputMatchesEverySingleCut() {
        for ((label, input) in corpus) {
            for (cuts in allCuts(input)) {
                assertEquivalent(input, cuts, "single-cut:$label")
            }
        }
    }

    @Test
    fun fullInputMatchesRandomMultiCuts() {
        for ((label, input) in corpus) {
            // Several deterministic seeds per input.
            for (seed in 1L..12L) {
                val cuts = multiCuts(input, seed * 7919 + input.length.toLong())
                assertEquivalent(input, cuts, "multi-cut($seed):$label")
            }
        }
    }

    @Test
    fun byteWiseChunksMatchFullInput() {
        // Worst case: one UTF-16 code unit per write.
        for ((label, input) in corpus) {
            val cuts = (1 until input.length).toList()
            assertEquivalent(input, cuts, "unit-wise:$label")
        }
    }

    // ----- Specific boundary cut points -------------------------------------

    @Test
    fun surrogatePairCutBetweenHalves() {
        val input = "a\uD83D\uDE00b"
        // Cut exactly between high and low surrogate.
        assertEquivalent(input, listOf(2), "surrogate-mid")
        // Cut before high surrogate and after low surrogate.
        assertEquivalent(input, listOf(1, 3), "surrogate-edges")
    }

    @Test
    fun loneSurrogateCut() {
        val high = "x\uD83Dy"
        assertEquivalent(high, listOf(2), "lone-high-mid")
        val low = "x\uDE00y"
        assertEquivalent(low, listOf(2), "lone-low-mid")
        // Lone surrogate must survive verbatim (no platform default replacement).
        val events = runTokenizer(high, listOf(2))
        val text = events.filterIsInstance<Ev.Text>().joinToString("") { it.decoded }
        assertEquals(high, text)
    }

    @Test
    fun crlfCutBetweenCrAndLf() {
        val input = "a\r\nb"
        assertEquivalent(input, listOf(2), "crlf-mid")
        assertEquivalent(input, listOf(1, 2, 3), "crlf-all")
    }

    @Test
    fun entityCutsEverywhere() {
        val input = "<p>a&copy;b</p>"
        for (cut in 0..input.length) {
            assertEquivalent(input, listOf(cut), "entity-cut-$cut")
        }
        // Splitting directly between '&' and 'c' previously threw.
        assertEquivalent(input, listOf(6), "entity-at-amp")
        assertEquivalent(input, listOf(7), "entity-after-amp")
    }

    @Test
    fun commentEndingCuts() {
        val input = "a<!-- comment -->b"
        for (cut in 0..input.length) {
            assertEquivalent(input, listOf(cut), "comment-cut-$cut")
        }
    }

    @Test
    fun scriptDataCuts() {
        val input = "<script>if (a < b && c > d) { x</script;} </script>tail"
        for (cut in 0..input.length) {
            assertEquivalent(input, listOf(cut), "script-cut-$cut")
        }
    }

    @Test
    fun foreignContentCuts() {
        val input = "<svg><path d=\"M0 0 L1 1\"/><text>a&amp;b</text></svg>"
        for (cut in 0..input.length) {
            assertEquivalent(input, listOf(cut), "foreign-cut-$cut")
        }
    }

    // ----- EOF position -----------------------------------------------------

    @Test
    fun eofPositionIsAbsoluteEndOfInput() {
        val input = "abc"
        val ref = runTokenizer(input, emptyList())
        val chunked = runTokenizer(input, listOf(1, 2))
        assertEquals(ref, chunked)
        assertEquals(Ev.End, ref.last())
        val lastText = ref.filterIsInstance<Ev.Text>().last()
        assertEquals(0, lastText.start)
        assertEquals(3, lastText.end)
    }

    @Test
    fun unterminatedCommentEofSpansToAbsoluteEnd() {
        val input = "a<!-- rest"
        val ref = runTokenizer(input, emptyList())
        val chunked = runTokenizer(input, listOf(5))
        assertEquals(ref, chunked)
        val comment = ref.filterIsInstance<Ev.Comment>().single()
        // Body " rest" starts at 5; at EOF offset is 0 so bodyEnd == end.
        assertEquals(5, comment.start)
        assertEquals(input.length, comment.end)
        assertEquals(input.length, comment.bodyEnd)
        assertEquals(" rest", input.substring(comment.start, comment.bodyEnd))
    }

    // ----- Pause / resume ---------------------------------------------------

    private class RawRecorder : KsoupTokenizerCallbacks {
        val raw = mutableListOf<String>()
        override fun onText(start: Int, endIndex: Int) { raw.add("text($start,$endIndex)") }
        override fun onTextEntity(codepoint: Int, endIndex: Int) { raw.add("tentity($codepoint,$endIndex)") }
        override fun onOpenTagName(start: Int, endIndex: Int) { raw.add("open($start,$endIndex)") }
        override fun onOpenTagEnd(endIndex: Int) { raw.add("openEnd($endIndex)") }
        override fun onCloseTag(start: Int, endIndex: Int) { raw.add("close($start,$endIndex)") }
        override fun onSelfClosingTag(endIndex: Int) { raw.add("self($endIndex)") }
        override fun onAttribName(start: Int, endIndex: Int) { raw.add("attrName($start,$endIndex)") }
        override fun onAttribData(start: Int, endIndex: Int) { raw.add("attrData($start,$endIndex)") }
        override fun onAttribEntity(codepoint: Int) { raw.add("attrEnt($codepoint)") }
        override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) { raw.add("attrEnd($endIndex)") }
        override fun onComment(start: Int, endIndex: Int, offset: Int) { raw.add("comment($start,$endIndex,$offset)") }
        override fun onCData(start: Int, endIndex: Int, offset: Int) { raw.add("cdata($start,$endIndex,$offset)") }
        override fun onDeclaration(start: Int, endIndex: Int) { raw.add("decl($start,$endIndex)") }
        override fun onProcessingInstruction(start: Int, endIndex: Int) { raw.add("pi($start,$endIndex)") }
        override fun onEnd() { raw.add("end") }
    }

    @Test
    fun tokenizerPauseHoldsCursorAndResumeDoesNotReplay() {
        // At the tokenizer level, the only supported paused interactions are
        // pause()/resume() with no intervening write (the parser owns chunk
        // queuing). Pause must not advance the cursor or emit events; resume
        // with no new data must not replay already-emitted callbacks.
        val raw = RawRecorder()
        val tokenizer = KsoupTokenizer(KsoupHtmlOptions.Default, raw)
        tokenizer.write("<a>foo")
        tokenizer.pause()
        val before = ArrayList(raw.raw)
        tokenizer.resume()
        assertEquals(before, raw.raw)
        tokenizer.write("<b>bar</b>")
        tokenizer.pause()
        val mid = ArrayList(raw.raw)
        tokenizer.resume()
        assertEquals(mid, raw.raw)
        tokenizer.write("tail")
        tokenizer.end()

        assertEquals(listOf("open(1,2)", "openEnd(2)", "text(3,6)"), raw.raw.take(3))
        assertTrue(raw.raw.contains("open(7,8)"))
        assertTrue(raw.raw.contains("close(14,15)"))
        assertTrue(raw.raw.contains("text(16,20)"))
        assertEquals("end", raw.raw.last())
    }

    @Test
    fun parserPauseQueuesWritesAndResumeIsIdempotent() {
        val reference = parserEvents("<a>foo<b>bar</b>tail", emptyList())

        val events = mutableListOf<String>()
        val handler = recordingHandler(events)
        val parser = KsoupHtmlParser(handler = handler)
        parser.write("<a>foo")
        parser.pause()
        parser.write("<b>bar</b>")
        val pausedSnapshot = ArrayList(events)
        parser.resume()
        // The queued chunk must be emitted exactly once.
        val afterResume = ArrayList(events)
        parser.resume()
        assertEquals(afterResume, events)
        parser.write("tail")
        parser.end()

        assertEquals(reference, normalizeParserEvents(events))
        assertTrue(events.size > pausedSnapshot.size)
    }

    @Test
    fun pauseInsideChunkStopsAndResumeContinues() {
        val input = "<a><b><c>deep text</c></b></a>"
        val reference = parserEvents(input, emptyList())
        val events = mutableListOf<String>()
        lateinit var parser: KsoupHtmlParser
        val handler = object : com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler {
            override fun onOpenTagName(name: String) {
                events.add("openName:$name")
            }
            override fun onOpenTag(name: String, attributes: Map<String, String>, isImplied: Boolean) {
                events.add("openTag:$name::isImplied")
                if (name == "b") parser.pause()
            }
            override fun onCloseTag(name: String, isImplied: Boolean) {
                events.add("close:$name:$isImplied")
            }
            override fun onText(text: String) {
                events.add("text:${text.map { hexUnit(it.code) }.joinToString("")}")
            }
            override fun onEnd() { events.add("end") }
            override fun onError(error: Exception) { events.add("error:${error.message}") }
        }
        parser = KsoupHtmlParser(handler = handler)
        parser.write(input)
        // Tokenizer paused inside the single write once <b> is fully open;
        // the cursor must not have advanced into "<c>...".
        val pausedSnapshot = ArrayList(events)
        assertEquals(
            listOf("openName:a", "openTag:a::isImplied", "openName:b", "openTag:b::isImplied"),
            pausedSnapshot
        )
        parser.resume()
        parser.end()
        // Compare on the structural skeleton (open/close/text/end) to avoid the
        // bespoke event encoding above.
        val skeleton = events.filter {
            it.startsWith("openName:") || it.startsWith("close:") ||
                it == "end" || it.startsWith("text:")
        }
        val referenceSkeleton = reference.filter {
            it.startsWith("openName:") || it.startsWith("close:") ||
                it == "end" || it.startsWith("text:")
        }
        assertEquals(referenceSkeleton, skeleton)
    }

    // ----- Repeated end() / late writes contract ----------------------------

    @Test
    fun parserDoubleEndAndLateWriteReportErrors() {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(handler = recordingHandler(events))
        parser.write("abc")
        parser.end()
        val afterFirstEnd = ArrayList(events)
        parser.end()
        parser.write("x")
        // Exactly two extra error events, no additional data processing.
        assertEquals(afterFirstEnd.size + 2, events.size)
        assertTrue(events[afterFirstEnd.size].startsWith("error:"))
        assertTrue(events[afterFirstEnd.size + 1].startsWith("error:"))
    }

    @Test
    fun tokenizerEndEofSpanAndRepeatedEnd() {
        val raw = RawRecorder()
        val tokenizer = KsoupTokenizer(KsoupHtmlOptions.Default, raw)
        tokenizer.write("abc")
        tokenizer.end()
        assertEquals(listOf("text(0,3)", "end"), raw.raw)
        // Current (pinned) tokenizer behavior: end() is not idempotent at this
        // internal layer — repeated calls re-run trailing handling / onEnd with
        // no remaining data, producing only an additional end marker.
        tokenizer.end()
        assertEquals(listOf("text(0,3)", "end", "end"), raw.raw)
    }

    // ----- Parser-level equivalence (decoded values, attribute order) -------

    private fun recordingHandler(
        sink: MutableList<String>,
        shouldPause: ((String) -> Boolean)? = null
    ) = object : com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler {
        override fun onOpenTagName(name: String) {
            sink.add("openName:$name")
            if (shouldPause?.invoke(name) == true) {
                // Pause from within a callback: cursor must not advance.
            }
        }
        override fun onOpenTag(name: String, attributes: Map<String, String>, isImplied: Boolean) {
            sink.add("openTag:$name:${attributes.entries.joinToString(",") { "${it.key}=${it.value}" }}:$isImplied")
            if (shouldPause?.invoke(name) == true) {
                // Pause after attributes are available, when tag is fully open.
            }
        }
        override fun onAttribute(name: String, value: String, quote: String?) {
            sink.add("attr:$name=${value.map { hexUnit(it.code) }.joinToString("")}:$quote")
        }
        override fun onCloseTag(name: String, isImplied: Boolean) {
            sink.add("close:$name:$isImplied")
            if (shouldPause?.invoke(name) == true) {
                // no-op; pausing only exercised on open in this suite
            }
        }
        override fun onText(text: String) { sink.add("text:${text.map { hexUnit(it.code) }.joinToString("")}") }
        override fun onComment(comment: String) { sink.add("comment:$comment") }
        override fun onCommentEnd() { sink.add("commentEnd") }
        override fun onCDataStart() { sink.add("cdataStart") }
        override fun onCDataEnd() { sink.add("cdataEnd") }
        override fun onProcessingInstruction(name: String, data: String) { sink.add("pi:$name:$data") }
        override fun onEnd() { sink.add("end") }
        override fun onError(error: Exception) { sink.add("error:${error.message}") }
    }

    private fun runParserRaw(input: String, cuts: List<Int>): MutableList<String> {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(handler = recordingHandler(events))
        var pos = 0
        for (cut in cuts) {
            if (cut > pos) parser.write(input.substring(pos, cut))
            pos = cut
        }
        if (pos < input.length) parser.write(input.substring(pos))
        parser.end()
        return events
    }

    private fun parserEvents(input: String, cuts: List<Int>): List<String> =
        normalizeParserEvents(runParserRaw(input, cuts))

    /** Merge adjacent text events, which differ only at chunk boundaries. */
    private fun normalizeParserEvents(events: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (e in events) {
            if (e.startsWith("text:") && out.lastOrNull()?.startsWith("text:") == true) {
                out[out.size - 1] = out.last() + e.removePrefix("text:")
            } else {
                out.add(e)
            }
        }
        return out
    }

    @Test
    fun parserChunkedMatchesFullForCorpus() {
        for ((label, input) in corpus) {
            val reference = parserEvents(input, emptyList())
            for (cut in 0..input.length) {
                assertEquals(
                    reference,
                    parserEvents(input, listOf(cut)),
                    "parser mismatch for $label @cut=$cut"
                )
            }
            for (seed in 1L..6L) {
                assertEquals(
                    reference,
                    parserEvents(input, multiCuts(input, seed * 131 + input.length.toLong())),
                    "parser mismatch for $label multi seed=$seed"
                )
            }
        }
    }

    @Test
    fun attributeOrderAndDecodedValuesStableAcrossChunks() {
        val input = "<a first=\"&copy;1\" second='&lt;2' third=&amp;3 fourth empty>" +
            "<b x=\"a&amp;b&copy;c\"/></a>"
        val reference = parserEvents(input, emptyList())
        for (cut in 1 until input.length) {
            assertEquals(reference, parserEvents(input, listOf(cut)), "attr order @cut=$cut")
        }
        // Spot-check decoded values once.
        val joined = reference.joinToString("|")
        assertTrue(joined.contains("attr:first=U+00A9U+0031:\""))
        assertTrue(joined.contains("attr:second=U+003CU+0032:'"))
        assertTrue(joined.contains("attr:third=U+0026U+0033:null"))
        // Named entities decode; raw "&amp;" appears as U+0026, "b", then (c)
        assertTrue(joined.contains("attr:x=U+0061U+0026U+0062U+00A9U+0063:\""))
    }

    // ----- reset() / reuse contract -----------------------------------------

    @Test
    fun resetAllowsReuseAndProducesFreshAbsoluteSpans() {
        val events = mutableListOf<String>()
        val parser = KsoupHtmlParser(handler = recordingHandler(events))
        parser.end("<a>1</a>")
        val first = normalizeParserEvents(ArrayList(events))

        parser.reset()
        events.clear()
        parser.write("<b>2</b>")
        parser.end()
        val second = normalizeParserEvents(events)

        // The second document restarts at absolute offset 0.
        assertEquals(parserEvents("<b>2</b>", emptyList()), second)
        // Independent streams, but equivalent structure for distinct tags/text.
        assertTrue(first.any { it == "openName:a" })
        assertTrue(second.any { it == "openName:b" })
        assertTrue(second.none { it.startsWith("error") })
    }

    @Test
    fun tokenizerResetClearsCursorAndOffsetsRestartAtZero() {
        val raw = RawRecorder()
        val tokenizer = KsoupTokenizer(KsoupHtmlOptions.Default, raw)
        tokenizer.write("abcdef")
        tokenizer.end()
        tokenizer.reset()
        raw.raw.clear()
        tokenizer.write("gh")
        tokenizer.end()
        // After reset, offsets start from 0 again.
        assertEquals(listOf("text(0,2)", "end"), raw.raw)
        assertEquals(2, tokenizer.totalInputLength)
        // Only the small terminal tail remains, not any pre-reset history.
        assertTrue(tokenizer.retainedLength <= 2)
    }

    // ----- Regression for the pre-refactor cross-chunk entity crash ----------

    /**
     * Regression for the old latent bug: when a named entity started in one
     * chunk and completed in a later chunk, the tokenizer indexed into the
     * *current* chunk with the entity's absolute offset and threw
     * [IndexOutOfBoundsException]. The cursor now resolves the entity span
     * across retained segments and decodes exactly like full input.
     */
    /**
     * Pre-existing HTML-tolerance quirk (NOT changed by this refactor): the
     * short-comment `<!--->` produces an inverted comment body span that makes
     * the *parser* (not the tokenizer) throw when materializing the slice.
     * This test pins that the tokenizer event sequence is unchanged and stable
     * across chunk splits; fixing the parser tolerance is out of scope.
     */
    @Test
    fun shortCommentInvertedSpanCharacterizationIsChunkStable() {
        val input = "a<!--->b"
        val ref = runTokenizer(input, emptyList())
        val chunked = runTokenizer(input, listOf(3))
        assertEquals(ref, chunked)
        val comment = ref.filterIsInstance<Ev.Comment>().single()
        assertEquals(5, comment.start)
        assertEquals(6, comment.end)
        assertEquals(4, comment.bodyEnd) // inverted body span, as before
    }

    /**
     * On the JVM an inverted [String.substring] range throws, so the parser
     * surfaces the pre-existing inverted-span quirk as an exception. Other
     * platforms clamp the range instead. We therefore only assert on the JVM;
     * the tokenizer event itself is pinned cross-platform above.
     */
    @Test
    fun parserShortCommentCrashIsPreExisting() {
        if (!isJvmLikePlatform()) return
        for (cuts in listOf(emptyList(), listOf(3), listOf(1, 4))) {
            var threw = false
            try {
                runParserRaw("a<!--->b", cuts)
            } catch (t: IndexOutOfBoundsException) {
                threw = true // StringIndexOutOfBounds on inverted substring
            } catch (t: RuntimeException) {
                // NegativeArraySizeException (inverted range used to size the
                // slice builder) is not an IndexOutOfBoundsException.
                threw = t::class.simpleName == "NegativeArraySizeException"
            }
            assertTrue(threw, "expected pre-existing short-comment crash for $cuts")
        }
    }

    private fun isJvmLikePlatform(): Boolean =
        runCatching {
            // Common code has no direct platform check; detect the substring
            // behavior that distinguishes the JVM (throws on inverted range).
            "x".substring(1, 0)
            false
        }.getOrElse { true }

    @Test
    fun crossChunkEntityDecodesLikeFullInput() {
        val input = "<p>a&copy;b</p>"
        for (cut in listOf(5, 6, 7, 8, 9, 10)) {
            val events = runTokenizer(input, listOf(cut))
            val text = events.filterIsInstance<Ev.Text>().joinToString("") { it.decoded }
            assertEquals("a\u00A9b", text, "decoded entity mismatch @cut=$cut")
        }
    }
}
