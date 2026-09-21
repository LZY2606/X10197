package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser

/**
 * A single raw callback emitted by the tokenizer. Positions are the exact
 * absolute indices the tokenizer reported; decoded values are resolved against
 * the source string so full and chunked parses can be compared.
 */
internal data class RawEvent(
    val name: String,
    val ints: List<Int> = emptyList(),
    val quote: KsoupHtmlParser.QuoteType? = null,
)

@OptIn(com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi::class)
internal fun recordRawEvents(
    source: String,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): MutableList<RawEvent> {
    val events = mutableListOf<RawEvent>()
    val tokenizer = KsoupTokenizer(
        options = options,
        callbacks = object : KsoupTokenizerCallbacks {
            override fun onAttribData(start: Int, endIndex: Int) {
                events.add(RawEvent("AttribData", listOf(start, endIndex)))
            }
            override fun onAttribEntity(codepoint: Int) {
                events.add(RawEvent("AttribEntity", listOf(codepoint)))
            }
            override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
                events.add(RawEvent("AttribEnd", listOf(endIndex), quote))
            }
            override fun onAttribName(start: Int, endIndex: Int) {
                events.add(RawEvent("AttribName", listOf(start, endIndex)))
            }
            override fun onCData(start: Int, endIndex: Int, offset: Int) {
                events.add(RawEvent("CData", listOf(start, endIndex, offset)))
            }
            override fun onCloseTag(start: Int, endIndex: Int) {
                events.add(RawEvent("CloseTag", listOf(start, endIndex)))
            }
            override fun onComment(start: Int, endIndex: Int, offset: Int) {
                events.add(RawEvent("Comment", listOf(start, endIndex, offset)))
            }
            override fun onDeclaration(start: Int, endIndex: Int) {
                events.add(RawEvent("Declaration", listOf(start, endIndex)))
            }
            override fun onEnd() {
                events.add(RawEvent("End"))
            }
            override fun onOpenTagEnd(endIndex: Int) {
                events.add(RawEvent("OpenTagEnd", listOf(endIndex)))
            }
            override fun onOpenTagName(start: Int, endIndex: Int) {
                events.add(RawEvent("OpenTagName", listOf(start, endIndex)))
            }
            override fun onProcessingInstruction(start: Int, endIndex: Int) {
                events.add(RawEvent("ProcessingInstruction", listOf(start, endIndex)))
            }
            override fun onSelfClosingTag(endIndex: Int) {
                events.add(RawEvent("SelfClosingTag", listOf(endIndex)))
            }
            override fun onText(start: Int, endIndex: Int) {
                events.add(RawEvent("Text", listOf(start, endIndex)))
            }
            override fun onTextEntity(codepoint: Int, endIndex: Int) {
                events.add(RawEvent("TextEntity", listOf(codepoint, endIndex)))
            }
        }
    )
    tokenizer.write(source)
    tokenizer.end()
    return events
}

@OptIn(com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi::class)
internal fun recordRawEventsChunked(
    source: String,
    cuts: IntArray,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): MutableList<RawEvent> {
    val events = mutableListOf<RawEvent>()
    val tokenizer = KsoupTokenizer(
        options = options,
        callbacks = object : KsoupTokenizerCallbacks {
            override fun onAttribData(start: Int, endIndex: Int) {
                events.add(RawEvent("AttribData", listOf(start, endIndex)))
            }
            override fun onAttribEntity(codepoint: Int) {
                events.add(RawEvent("AttribEntity", listOf(codepoint)))
            }
            override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
                events.add(RawEvent("AttribEnd", listOf(endIndex), quote))
            }
            override fun onAttribName(start: Int, endIndex: Int) {
                events.add(RawEvent("AttribName", listOf(start, endIndex)))
            }
            override fun onCData(start: Int, endIndex: Int, offset: Int) {
                events.add(RawEvent("CData", listOf(start, endIndex, offset)))
            }
            override fun onCloseTag(start: Int, endIndex: Int) {
                events.add(RawEvent("CloseTag", listOf(start, endIndex)))
            }
            override fun onComment(start: Int, endIndex: Int, offset: Int) {
                events.add(RawEvent("Comment", listOf(start, endIndex, offset)))
            }
            override fun onDeclaration(start: Int, endIndex: Int) {
                events.add(RawEvent("Declaration", listOf(start, endIndex)))
            }
            override fun onEnd() {
                events.add(RawEvent("End"))
            }
            override fun onOpenTagEnd(endIndex: Int) {
                events.add(RawEvent("OpenTagEnd", listOf(endIndex)))
            }
            override fun onOpenTagName(start: Int, endIndex: Int) {
                events.add(RawEvent("OpenTagName", listOf(start, endIndex)))
            }
            override fun onProcessingInstruction(start: Int, endIndex: Int) {
                events.add(RawEvent("ProcessingInstruction", listOf(start, endIndex)))
            }
            override fun onSelfClosingTag(endIndex: Int) {
                events.add(RawEvent("SelfClosingTag", listOf(endIndex)))
            }
            override fun onText(start: Int, endIndex: Int) {
                events.add(RawEvent("Text", listOf(start, endIndex)))
            }
            override fun onTextEntity(codepoint: Int, endIndex: Int) {
                events.add(RawEvent("TextEntity", listOf(codepoint, endIndex)))
            }
        }
    )
    var previous = 0
    for (cut in cuts) {
        tokenizer.write(source.substring(previous, cut))
        previous = cut
    }
    tokenizer.write(source.substring(previous))
    tokenizer.end()
    return events
}

private fun codepointToString(cp: Int): String {
    return if (cp <= 0xFFFF) {
        cp.toChar().toString()
    } else {
        val adjusted = cp - 0x10000
        val high = (0xD800 + (adjusted ushr 10)).toChar()
        val low = (0xDC00 + (adjusted and 0x3FF)).toChar()
        charArrayOf(high, low).concatToString()
    }
}

/**
 * Normalized, semantic view of the raw tokenizer event stream.
 *
 * Chunked input legitimately splits a single logical text run into multiple
 * `onText` callbacks (one per write). Consecutive text/entity callbacks and
 * consecutive attribute data/entity callbacks are therefore merged; the merged
 * absolute span and the decoded value must still match the single-write parse.
 * All structural events keep their exact reported offsets.
 */
internal sealed class NormEvent {
    data class TextData(val start: Int, val end: Int, val value: String) : NormEvent()
    data class AttribValue(val start: Int, val end: Int, val value: String) : NormEvent()
    data class Other(val raw: RawEvent) : NormEvent()
}

internal fun normalize(events: List<RawEvent>, source: String): List<NormEvent> {
    val result = mutableListOf<NormEvent>()
    var index = 0
    while (index < events.size) {
        val event = events[index]
        when (event.name) {
            "Text", "TextEntity" -> {
                var start = Int.MAX_VALUE
                var end = -1
                val builder = StringBuilder()
                while (index < events.size &&
                    (events[index].name == "Text" || events[index].name == "TextEntity")
                ) {
                    val current = events[index]
                    if (current.name == "Text") {
                        start = minOf(start, current.ints[0])
                        end = maxOf(end, current.ints[1])
                        builder.append(source.substring(current.ints[0], current.ints[1]))
                    } else {
                        val entityEnd = current.ints[1]
                        val entityValue = codepointToString(current.ints[0])
                        val entityStart = entityEnd - entityValue.length
                        start = minOf(start, entityStart)
                        end = maxOf(end, entityEnd)
                        builder.append(entityValue)
                    }
                    index++
                }
                result.add(NormEvent.TextData(start, end, builder.toString()))
            }
            "AttribData", "AttribEntity" -> {
                var start = Int.MAX_VALUE
                var end = -1
                val builder = StringBuilder()
                while (index < events.size &&
                    (events[index].name == "AttribData" || events[index].name == "AttribEntity")
                ) {
                    val current = events[index]
                    if (current.name == "AttribData") {
                        start = minOf(start, current.ints[0])
                        end = maxOf(end, current.ints[1])
                        builder.append(source.substring(current.ints[0], current.ints[1]))
                    } else {
                        builder.append(codepointToString(current.ints[0]))
                    }
                    index++
                }
                result.add(NormEvent.AttribValue(start, end, builder.toString()))
            }
            else -> {
                result.add(NormEvent.Other(event))
                index++
            }
        }
    }
    return result
}

internal fun assertEquivalentAtEveryCut(source: String, options: KsoupHtmlOptions = KsoupHtmlOptions.Default) {
    val full = normalize(recordRawEvents(source, options), source)
    for (cut in 1 until source.length) {
        val chunked = normalize(recordRawEventsChunked(source, intArrayOf(cut), options), source)
        kotlin.test.assertEquals(full, chunked, "Mismatch with single cut at $cut for input ${source.debug()}")
    }
}

internal fun assertEquivalentWithCuts(
    source: String,
    cuts: IntArray,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
) {
    val full = normalize(recordRawEvents(source, options), source)
    val chunked = normalize(recordRawEventsChunked(source, cuts, options), source)
    kotlin.test.assertEquals(full, chunked, "Mismatch with cuts ${cuts.toList()} for input ${source.debug()}")
}

private fun String.debug(): String {
    val escaped = this.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return if (escaped.length > 120) "\"${escaped.take(117)}...\"" else "\"$escaped\""
}
