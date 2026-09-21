package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import com.mohamedrejeb.ksoup.html.tokenizer.KsoupTokenizerCallbacks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests locking the observable event stream of the parser/tokenizer.
 *
 * For every document in the battery, the events produced by parsing the whole
 * input in a single [KsoupHtmlParser.write] must be equivalent to the events
 * produced by any chunked writing of the same input. Equivalence compares:
 *  - token types and their absolute start/end offsets (tokenizer callbacks)
 *  - decoded values (handler callbacks)
 *  - attribute order
 *  - EOF (both `onEnd` callbacks and the final position, checked in the
 *    tokenizer package tests)
 *
 * Text and attribute-data events may legitimately be fragmented at chunk
 * boundaries (the tokenizer flushes pending data at the end of every write),
 * so adjacent fragments are merged before comparison.
 */
@OptIn(ExperimentalKsoupApi::class)
class StreamContractTest {

    /** Records the full public event stream: tokenizer callbacks (spans) + handler (decoded values). */
    private class Recorder {
        val tokenEvents = mutableListOf<String>()
        val handlerEvents = mutableListOf<String>()

        fun newParser(options: KsoupHtmlOptions): KsoupHtmlParser {
            val callbacksBuilder = KsoupTokenizerCallbacks.Builder()
            callbacksBuilder.onAttribData { s, e -> tokenEvents.add("attrData:$s:$e") }
            callbacksBuilder.onAttribEntity { cp -> tokenEvents.add("attrEntity:$cp") }
            callbacksBuilder.onAttribEnd { q, e -> tokenEvents.add("attrEnd:$q:$e") }
            callbacksBuilder.onAttribName { s, e -> tokenEvents.add("attrName:$s:$e") }
            callbacksBuilder.onCData { s, e, o -> tokenEvents.add("cdata:$s:$e:$o") }
            callbacksBuilder.onCloseTag { s, e -> tokenEvents.add("closeTag:$s:$e") }
            callbacksBuilder.onComment { s, e, o -> tokenEvents.add("comment:$s:$e:$o") }
            callbacksBuilder.onDeclaration { s, e -> tokenEvents.add("decl:$s:$e") }
            callbacksBuilder.onEnd { tokenEvents.add("end") }
            callbacksBuilder.onOpenTagEnd { e -> tokenEvents.add("openTagEnd:$e") }
            callbacksBuilder.onOpenTagName { s, e -> tokenEvents.add("openTagName:$s:$e") }
            callbacksBuilder.onProcessingInstruction { s, e -> tokenEvents.add("pi:$s:$e") }
            callbacksBuilder.onSelfClosingTag { e -> tokenEvents.add("selfClosing:$e") }
            callbacksBuilder.onText { s, e -> tokenEvents.add("text:$s:$e") }
            callbacksBuilder.onTextEntity { cp, e -> tokenEvents.add("textEntity:$cp:$e") }

            val handler = KsoupHtmlHandler.Builder()
                .onOpenTagName { name -> handlerEvents.add("openTagName:$name") }
                .onAttribute { name, value, quote ->
                    handlerEvents.add("attr:$name=${quote.orEmpty()}:$value")
                }
                .onOpenTag { name, attrs, implied ->
                    val attrsString = attrs.entries.joinToString(",") { "${it.key}=${it.value}" }
                    handlerEvents.add("openTag:$name:$implied:[$attrsString]")
                }
                .onText { text -> handlerEvents.add("text:$text") }
                .onCloseTag { name, implied -> handlerEvents.add("closeTag:$name:$implied") }
                .onComment { comment -> handlerEvents.add("comment:$comment") }
                .onCommentEnd { handlerEvents.add("commentEnd") }
                .onCDataStart { handlerEvents.add("cdataStart") }
                .onCDataEnd { handlerEvents.add("cdataEnd") }
                .onProcessingInstruction { name, data -> handlerEvents.add("pi:$name:$data") }
                .onEnd { handlerEvents.add("end") }
                .onError { error -> handlerEvents.add("error:${error.message}") }
                .build()

            return KsoupHtmlParser(
                handler = handler,
                options = options,
                callbacks = callbacksBuilder.build(),
            )
        }
    }

    private data class Doc(
        val name: String,
        val input: String,
        val options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
    )

    /** Merge adjacent text fragments (span-merge for token events, concat for handler events). */
    private fun normalize(events: List<String>, mergeSpans: Boolean): List<String> {
        val out = mutableListOf<String>()
        for (event in events) {
            val prev = out.lastOrNull()
            if (prev != null && prev.startsWith("text:") && event.startsWith("text:")) {
                if (mergeSpans) {
                    val prevStart = prev.substringAfter("text:").substringBefore(":").toInt()
                    val prevEnd = prev.substringAfterLast(":").toInt()
                    val start = event.substringAfter("text:").substringBefore(":").toInt()
                    val end = event.substringAfterLast(":").toInt()
                    if (prevEnd == start) {
                        out[out.lastIndex] = "text:$prevStart:$end"
                    } else {
                        out.add(event)
                    }
                } else {
                    out[out.lastIndex] = prev + event.removePrefix("text:")
                }
            } else if (
                mergeSpans && prev != null &&
                prev.startsWith("attrData:") && event.startsWith("attrData:")
            ) {
                val prevStart = prev.substringAfter("attrData:").substringBefore(":").toInt()
                val prevEnd = prev.substringAfterLast(":").toInt()
                val start = event.substringAfter("attrData:").substringBefore(":").toInt()
                val end = event.substringAfterLast(":").toInt()
                if (prevEnd == start) {
                    out[out.lastIndex] = "attrData:$prevStart:$end"
                } else {
                    out.add(event)
                }
            } else {
                out.add(event)
            }
        }
        return out
    }

    private fun runParser(doc: Doc, chunks: List<String>): Pair<List<String>, List<String>> {
        val recorder = Recorder()
        val parser = recorder.newParser(doc.options)
        for (chunk in chunks) parser.write(chunk)
        parser.end()
        return normalize(recorder.tokenEvents, mergeSpans = true) to
            normalize(recorder.handlerEvents, mergeSpans = false)
    }

    private fun assertChunkingEquivalent(doc: Doc, chunks: List<String>, label: String) {
        val reference = runParser(doc, listOf(doc.input))
        val actual = runParser(doc, chunks)
        assertEquals(
            reference.first, actual.first,
            "token events differ for doc=${doc.name} ($label)",
        )
        assertEquals(
            reference.second, actual.second,
            "handler events differ for doc=${doc.name} ($label)",
        )
    }

    private fun checkDocument(doc: Doc) {
        // Whole input in a single write vs every possible 2-chunk split.
        for (cut in 0..doc.input.length) {
            assertChunkingEquivalent(
                doc,
                listOf(doc.input.substring(0, cut), doc.input.substring(cut)),
                "cut=$cut",
            )
        }
        // Fixed-size chunkings, including chunk size 1 (every char its own write).
        for (size in listOf(1, 2, 3, 7, 13)) {
            assertChunkingEquivalent(doc, doc.input.chunked(size), "chunkSize=$size")
        }
    }

    private fun docs(): List<Doc> {
        val emoji = "\uD83D\uDE00" // 😀
        val party = "\uD83C\uDF89" // 🎉
        return listOf(
            Doc("simple", "<div class=\"a\">Hello <b>world</b>!</div>"),
            Doc("surrogatePairs", "<p title=\"x${emoji}y\">a${emoji}b${party}c</p>"),
            // Isolated surrogates must pass through untouched (no platform-dependent replacement).
            Doc("isolatedSurrogates", "<p>a\uD800b\uDC00c\uD800</p>"),
            Doc("isolatedSurrogateInAttr", "<p title=\"a\uD800b\">x\uDC00</p>"),
            Doc("crlf", "<div>\r\n<p>a\rb\r\nc\nd</p>\r\n</div>"),
            Doc("crlfInTag", "<a\r\nhref=\"x\r\ny\"\r>link</a>"),
            Doc(
                "entities",
                "<p>&amp; &#169; &#xA9; &notreal; &amp &#x1F600; &unknown; &#; &#x;</p>",
            ),
            Doc(
                "entitiesInAttributes",
                "<a href=\"?a=1&amp;b=2\" title='&lt;x&gt;' data-x=&#65; data-y=\"&#x1F600;\">t</a>",
            ),
            Doc("entitiesNoDecode", "<p>&amp; &#169;</p>", KsoupHtmlOptions(decodeEntities = false)),
            Doc("comments", "<!-- c --><p>x</p><!---><!--><!-- -- --><!-- unclosed"),
            Doc("commentEndings", "<!-- a ---><p>b</p><!-- c ----><!-- d --!><p>e</p>"),
            Doc(
                "scriptData",
                "<script>if (a < b) { x(\"</scr\" + \"ipt>\"); }</script><p>after</p>",
            ),
            Doc("scriptWithLt", "<script>a << b <//script></script><p>done</p>"),
            Doc("styleData", "<style>.a > .b { color: red; }</style><p>x</p>"),
            Doc("titleTextarea", "<title>a &amp; b</title><textarea>x &amp; y</textarea>"),
            Doc("scriptUnclosed", "<p>before</p><script>var a = 1;"),
            Doc(
                "foreignContent",
                "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0L1 1\"/><foreignObject>" +
                    "<div>html</div></foreignObject></svg><math><mi>x</mi></math>",
            ),
            Doc("cdataDefault", "<p>a</p><![CDATA[some <data> here]]><p>b</p>"),
            Doc(
                "cdataRecognized",
                "<p>a</p><![CDATA[some <data> here]]><p>b</p>",
                KsoupHtmlOptions(recognizeCDATA = true),
            ),
            Doc("cdataUnclosed", "<![CDATA[never closed"),
            Doc("declarationPi", "<!DOCTYPE html><?xml version=\"1.0\"?><p>x</p>"),
            Doc("eofInTagName", "<p>text</p><di"),
            Doc("eofInAttribute", "<div class=\"value"),
            Doc("eofInAttributeName", "<div cla"),
            Doc("eofAfterEquals", "<div class="),
            Doc("eofInEntity", "<p>a&am"),
            Doc("eofBareAmp", "<p>a&"),
            Doc("eofSelfClosing", "<p>x</p><br/"),
            Doc("eofText", "<p>trailing text"),
            Doc("eofClosingTag", "<p>x</p></p"),
            Doc(
                "attributes",
                "<a  b  c=d e=\"f\" g='h' i = \"j\" k= l>m</a>",
            ),
            Doc("duplicateAttributes", "<a x=\"1\" x=\"2\" X=\"3\">t</a>"),
            Doc("selfClosing", "<br/><img src=\"x\"/><p/>text"),
            Doc(
                "selfClosingRecognized",
                "<br/><img src=\"x\"/><p/>text",
                KsoupHtmlOptions(recognizeSelfClosing = true),
            ),
            Doc("xmlMode", "<root><item a=\"1\"/><empty></empty></root>", KsoupHtmlOptions(xmlMode = true)),
            Doc("specialComment", "<!-- x --></!foo><p>y</p>"),
            Doc("denseAttributes", buildString {
                append("<div")
                for (i in 0 until 30) append(" a$i=\"v$i\"")
                append(">x</div>")
            }),
            Doc("longText", buildString {
                append("<p>")
                repeat(40) { append("Lorem ipsum dolor sit amet $it &amp; consectetur. ") }
                append("</p>")
            }),
            Doc("mixedCaseTags", "<DIV CLASS=\"A\">X</DIV>"),
            Doc("impliedClose", "<ul><li>one<li>two<li>three</ul><p>a<p>b"),
        )
    }

    @Test
    fun fullInputEqualsArbitraryChunking() {
        for (doc in docs()) {
            checkDocument(doc)
        }
    }
}
