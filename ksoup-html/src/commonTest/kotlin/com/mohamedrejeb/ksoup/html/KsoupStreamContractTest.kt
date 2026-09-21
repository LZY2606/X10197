package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizer
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizerCallbacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Characterization / contract tests for the tokenizer + parser streaming behavior.
 *
 * These tests lock the observable behavior of the current implementation so that
 * the internal cursor/segment refactor (unifying current-chunk offset, cumulative
 * input offset, and the buffer range that must remain retained) can change no
 * public contract: token type, decoded value, attribute order, absolute spans and
 * the EOF position.
 */
@OptIn(ExperimentalKsoupApi::class)
class KsoupStreamContractTest {

    // region Raw event recording

    private sealed class Ev {
        data class Text(val start: Int, val end: Int) : Ev()
        data class TextEntity(val codepoint: Int, val end: Int) : Ev()
        data class OpenTagName(val start: Int, val end: Int) : Ev()
        data class OpenTagEnd(val end: Int) : Ev()
        data class CloseTag(val start: Int, val end: Int) : Ev()
        data class SelfClosingTag(val end: Int) : Ev()
        data class AttribName(val start: Int, val end: Int) : Ev()
        data class AttribData(val start: Int, val end: Int) : Ev()
        data class AttribEntity(val codepoint: Int) : Ev()
        data class AttribEnd(val quote: KsoupHtmlParser.QuoteType, val end: Int) : Ev()
        data class Comment(val start: Int, val end: Int, val offset: Int) : Ev()
        data class CData(val start: Int, val end: Int, val offset: Int) : Ev()
        data class Declaration(val start: Int, val end: Int) : Ev()
        data class ProcessingInstruction(val start: Int, val end: Int) : Ev()
        data class End(val inputLength: Int) : Ev()
    }

    private class RecordingCallbacks(
        var tokenizer: KsoupTokenizer?,
    ) : KsoupTokenizerCallbacks {
        val events = mutableListOf<Ev>()

        override fun onAttribData(start: Int, endIndex: Int) {
            events.add(Ev.AttribData(start, endIndex))
        }
        override fun onAttribEntity(codepoint: Int) {
            events.add(Ev.AttribEntity(codepoint))
        }
        override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
            events.add(Ev.AttribEnd(quote, endIndex))
        }
        override fun onAttribName(start: Int, endIndex: Int) {
            events.add(Ev.AttribName(start, endIndex))
        }
        override fun onCData(start: Int, endIndex: Int, offset: Int) {
            events.add(Ev.CData(start, endIndex, offset))
        }
        override fun onCloseTag(start: Int, endIndex: Int) {
            events.add(Ev.CloseTag(start, endIndex))
        }
        override fun onComment(start: Int, endIndex: Int, offset: Int) {
            events.add(Ev.Comment(start, endIndex, offset))
        }
        override fun onDeclaration(start: Int, endIndex: Int) {
            events.add(Ev.Declaration(start, endIndex))
        }
        override fun onProcessingInstruction(start: Int, endIndex: Int) {
            events.add(Ev.ProcessingInstruction(start, endIndex))
        }
        override fun onEnd() {
            events.add(Ev.End(tokenizer!!.inputLength))
        }
        override fun onOpenTagEnd(endIndex: Int) {
            events.add(Ev.OpenTagEnd(endIndex))
        }
        override fun onOpenTagName(start: Int, endIndex: Int) {
            events.add(Ev.OpenTagName(start, endIndex))
        }
        override fun onSelfClosingTag(endIndex: Int) {
            events.add(Ev.SelfClosingTag(endIndex))
        }
        override fun onText(start: Int, endIndex: Int) {
            events.add(Ev.Text(start, endIndex))
        }
        override fun onTextEntity(codepoint: Int, endIndex: Int) {
            events.add(Ev.TextEntity(codepoint, endIndex))
        }
    }

    private fun newTokenizerRecorder(
        options: KsoupHtmlOptions = KsoupHtmlOptions.Default
    ): Pair<KsoupTokenizer, RecordingCallbacks> {
        val callbacks = RecordingCallbacks(null)
        val tokenizer = KsoupTokenizer(options, callbacks)
        callbacks.tokenizer = tokenizer
        return tokenizer to callbacks
    }

    // endregion
}

    // region Normalization (flush artifacts are chunk-boundary effects, not tokens)

    /**
     * Normalized view of a raw tokenizer stream:
     *  - adjacent [Ev.Text] / [Ev.TextEntity] runs between structural events are
     *    merged into one logical text token whose value is decoded against the
     *    original input and whose absolute span is [runStart, runEnd);
     *  - attribute data/entity fragments belonging to one attribute are merged,
     *    dropping the zero-length flush callbacks that only occur at chunk edges;
     *  - structural events keep their exact type and absolute offsets.
     */
    private data class NText(val start: Int, val end: Int, val value: String)
    private data class NOpenName(val start: Int, val end: Int)
    private data class NOpenEnd(val end: Int)
    private data class NClose(val start: Int, val end: Int)
    private data class NSelfClose(val end: Int)
    private data class NAttrName(val start: Int, val end: Int, val value: String)
    private data class NAttrValue(
        val start: Int,
        val end: Int,
        val quote: KsoupHtmlParser.QuoteType,
        val value: String,
    )
    private data class NComment(val start: Int, val end: Int, val offset: Int, val value: String)
    private data class NCData(val start: Int, val end: Int, val offset: Int, val value: String)
    private data class NDeclaration(val start: Int, val end: Int, val value: String)
    private data class NPI(val start: Int, val end: Int, val value: String)
    private data class NEnd(val inputLength: Int)

    private fun decodeText(events: List<Ev>, input: String): String {
        val sb = StringBuilder()
        for (ev in events) {
            when (ev) {
                is Ev.Text -> sb.append(input, ev.start, ev.end)
                is Ev.TextEntity -> sb.appendCodePointCompat(ev.codepoint)
                else -> error("unexpected $ev in text run")
            }
        }
        return sb.toString()
    }

    private fun StringBuilder.appendCodePointCompat(cp: Int): StringBuilder {
        if (cp in 0x10000..0x10FFFF) {
            this.append(Char(0xD800 + ((cp - 0x10000) ushr 10)))
            this.append(Char(0xDC00 + ((cp - 0x10000) and 0x3FF)))
        } else {
            this.append(Char(cp))
        }
        return this
    }

    private fun normalize(events: List<Ev>, input: String): List<Any> {
        val out = mutableListOf<Any>()
        var i = 0

        fun flushText(run: MutableList<Ev>) {
            val nonEmpty = run.filter { it !is Ev.Text || it.end > it.start }
            if (nonEmpty.isEmpty()) return
            val start = when (val first = nonEmpty.first()) {
                is Ev.Text -> first.start
                is Ev.TextEntity -> first.end - 1
                else -> error("bad text run start")
            }
            val end = when (val last = nonEmpty.last()) {
                is Ev.Text -> last.end
                is Ev.TextEntity -> last.end
                else -> error("bad text run end")
            }
            out.add(NText(start, end, decodeText(nonEmpty, input)))
        }

        while (i < events.size) {
            when (val ev = events[i]) {
                is Ev.Text, is Ev.TextEntity -> {
                    val run = mutableListOf<Ev>()
                    while (i < events.size && (events[i] is Ev.Text || events[i] is Ev.TextEntity)) {
                        run.add(events[i]); i++
                    }
                    flushText(run)
                }
                is Ev.OpenTagName -> {
                    out.add(NOpenName(ev.start, ev.end)); i++
                }
                is Ev.OpenTagEnd -> { out.add(NOpenEnd(ev.end)); i++ }
                is Ev.CloseTag -> { out.add(NClose(ev.start, ev.end)); i++ }
                is Ev.SelfClosingTag -> { out.add(NSelfClose(ev.end)); i++ }
                is Ev.AttribName -> {
                    out.add(NAttrName(ev.start, ev.end, input.substring(ev.start, ev.end))); i++
                }
                is Ev.AttribData, is Ev.AttribEntity, is Ev.AttribEnd -> {
                    val data = mutableListOf<Ev>()
                    var end: Ev.AttribEnd? = null
                    while (i < events.size && events[i] !is Ev.AttribEnd) {
                        val d = events[i]
                        if (d is Ev.AttribData && d.end > d.start) data.add(d)
                        else if (d is Ev.AttribEntity) data.add(d)
                        i++
                    }
                    check(events[i] is Ev.AttribEnd)
                    end = events[i] as Ev.AttribEnd
                    i++
                    if (end.quote == KsoupHtmlParser.QuoteType.NoValue) {
                        out.add(NAttrValue(end.end, end.end, end.quote, ""))
                    } else {
                        val start = if (data.isEmpty()) end.end else when (val f = data.first()) {
                            is Ev.AttribData -> f.start
                            is Ev.AttribEntity -> end.end // entity-only start follows prior data end
                            else -> error("bad attr data")
                        }
                        val sb = StringBuilder()
                        for (d in data) when (d) {
                            is Ev.AttribData -> sb.append(input, d.start, d.end)
                            is Ev.AttribEntity -> sb.appendCodePointCompat(d.codepoint)
                            else -> error("bad attr data")
                        }
                        out.add(NAttrValue(start, end.end, end.quote, sb.toString()))
                    }
                }
                is Ev.Comment -> {
                    out.add(NComment(ev.start, ev.end, ev.offset,
                        input.substring(ev.start, ev.end - ev.offset))); i++
                }
                is Ev.CData -> {
                    out.add(NCData(ev.start, ev.end, ev.offset,
                        input.substring(ev.start, ev.end - ev.offset))); i++
                }
                is Ev.Declaration -> {
                    out.add(NDeclaration(ev.start, ev.end, input.substring(ev.start, ev.end))); i++
                }
                is Ev.ProcessingInstruction -> {
                    out.add(NPI(ev.start, ev.end, input.substring(ev.start, ev.end))); i++
                }
                is Ev.End -> { out.add(NEnd(ev.inputLength)); i++ }
            }
        }
        return out
    }

    // endregion

    // region Parser-level recording (public handler + raw tokenizer callbacks)

    private sealed class HEv {
        data class OpenTagName(val name: String) : HEv()
        data class Attribute(val name: String, val value: String, val quote: String?) : HEv()
        data class OpenTag(val name: String, val attrs: List<Pair<String, String>>) : HEv()
        data class Text(val value: String) : HEv()
        data class Comment(val value: String) : HEv()
        data class Close(val name: String, val implied: Boolean) : HEv()
        data class PI(val name: String, val data: String) : HEv()
        data class CData(val value: String, val recognized: Boolean) : HEv()
        object End : HEv()
    }

    private class RecordingHandler : KsoupHtmlHandler {
        val events = mutableListOf<HEv>()
        private var pendingAttrs: MutableMap<String, String>? = null
        override fun onOpenTagName(name: String) {
            events.add(HEv.OpenTagName(name))
            pendingAttrs = LinkedHashMap()
        }
        override fun onAttribute(name: String, value: String, quote: String?) {
            events.add(HEv.Attribute(name, value, quote))
            pendingAttrs?.put(name, value)
        }
        override fun onOpenTag(name: String, attributes: Map<String, String>, isImplied: Boolean) {
            events.add(HEv.OpenTag(name, pendingAttrs?.toList() ?: attributes.toList()))
        }
        override fun onText(text: String) { events.add(HEv.Text(text)) }
        override fun onComment(comment: String) { events.add(HEv.Comment(comment)) }
        override fun onCloseTag(name: String, isImplied: Boolean) {
            events.add(HEv.Close(name, isImplied))
        }
        override fun onProcessingInstruction(name: String, data: String) {
            events.add(HEv.PI(name, data))
        }
        override fun onCDataStart() { events.add(HEv.CData("", true)) }
        override fun onCDataEnd() { /* marker folded into value */ }
        override fun onEnd() { events.add(HEv.End) }
    }

    private fun runParser(
        input: String,
        cuts: List<Int>,
        options: KsoupHtmlOptions = KsoupHtmlOptions.Default
    ): Pair<List<HEv>, List<Ev>> {
        val handler = RecordingHandler()
        val raw = mutableListOf<Ev>()
        lateinit var tokenizer: KsoupTokenizer
        val rawCallbacks = object : KsoupTokenizerCallbacks {
            override fun onAttribData(start: Int, endIndex: Int) { raw.add(Ev.AttribData(start, endIndex)) }
            override fun onAttribEntity(codepoint: Int) { raw.add(Ev.AttribEntity(codepoint)) }
            override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
                raw.add(Ev.AttribEnd(quote, endIndex))
            }
            override fun onAttribName(start: Int, endIndex: Int) { raw.add(Ev.AttribName(start, endIndex)) }
            override fun onCData(start: Int, endIndex: Int, offset: Int) { raw.add(Ev.CData(start, endIndex, offset)) }
            override fun onCloseTag(start: Int, endIndex: Int) { raw.add(Ev.CloseTag(start, endIndex)) }
            override fun onComment(start: Int, endIndex: Int, offset: Int) { raw.add(Ev.Comment(start, endIndex, offset)) }
            override fun onDeclaration(start: Int, endIndex: Int) { raw.add(Ev.Declaration(start, endIndex)) }
            override fun onProcessingInstruction(start: Int, endIndex: Int) {
                raw.add(Ev.ProcessingInstruction(start, endIndex))
            }
            override fun onEnd() { raw.add(Ev.End(tokenizer.inputLength)) }
            override fun onOpenTagEnd(endIndex: Int) { raw.add(Ev.OpenTagEnd(endIndex)) }
            override fun onOpenTagName(start: Int, endIndex: Int) { raw.add(Ev.OpenTagName(start, endIndex)) }
            override fun onSelfClosingTag(endIndex: Int) { raw.add(Ev.SelfClosingTag(endIndex)) }
            override fun onText(start: Int, endIndex: Int) { raw.add(Ev.Text(start, endIndex)) }
            override fun onTextEntity(codepoint: Int, endIndex: Int) { raw.add(Ev.TextEntity(codepoint, endIndex)) }
        }
        val parser = KsoupHtmlParser(
            handler = handler,
            options = options,
            callbacks = rawCallbacks,
        )
        tokenizer = parser.testTokenizer()
        parser.reset()
        var prev = 0
        for (cut in cuts) {
            parser.write(input.substring(prev, cut))
            prev = cut
        }
        if (prev < input.length) parser.write(input.substring(prev))
        parser.end()
        return handler.events to raw
    }

    // endregion
}
