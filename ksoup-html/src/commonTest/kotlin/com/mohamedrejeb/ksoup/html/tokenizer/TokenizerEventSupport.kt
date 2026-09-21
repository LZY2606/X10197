package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser

/**
 * Test support for tokenizer contract tests.
 *
 * A recorded event keeps the exact public callback name, arguments and the
 * decoded value (sliced from the complete input using the reported absolute
 * offsets). These are the observable results that must stay identical when the
 * internal cursor/segment machinery is refactored.
 */
internal data class TokenEvent(
    val name: String,
    val start: Int,
    val end: Int,
    val extra: Int,
    val value: String,
) {
    override fun toString(): String = "$name($start,$end,$extra)=${"'$value'"}"
}

@OptIn(ExperimentalKsoupApi::class)
internal class RecordingCallbacks : KsoupTokenizerCallbacks {
    val events = mutableListOf<TokenEvent>()
    lateinit var input: String

    private fun add(name: String, start: Int, end: Int, extra: Int = 0) {
        val value = if (name in valueEvents) {
            val lo = start.coerceAtLeast(0)
            val hi = end.coerceIn(lo, input.length)
            input.substring(lo, hi)
        } else {
            ""
        }
        events.add(TokenEvent(name, start, end, extra, value))
    }

    override fun onAttribData(start: Int, endIndex: Int) = add("attribData", start, endIndex)
    override fun onAttribEntity(codepoint: Int) {
        events.add(TokenEvent("attribEntity", 0, 0, codepoint, codePointToString(codepoint)))
    }
    override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
        events.add(TokenEvent("attribEnd", 0, endIndex, quote.ordinal, quote.name))
    }
    override fun onAttribName(start: Int, endIndex: Int) = add("attribName", start, endIndex)
    override fun onCData(start: Int, endIndex: Int, offset: Int) = add("cdata", start, endIndex - offset, offset)
    override fun onCloseTag(start: Int, endIndex: Int) = add("closeTag", start, endIndex)
    override fun onComment(start: Int, endIndex: Int, offset: Int) = add("comment", start, endIndex - offset, offset)
    override fun onDeclaration(start: Int, endIndex: Int) = add("declaration", start, endIndex)
    override fun onEnd() { events.add(TokenEvent("end", 0, 0, 0, "")) }
    override fun onOpenTagEnd(endIndex: Int) {
        events.add(TokenEvent("openTagEnd", 0, endIndex, 0, ""))
    }
    override fun onOpenTagName(start: Int, endIndex: Int) = add("openTagName", start, endIndex)
    override fun onProcessingInstruction(start: Int, endIndex: Int) = add("processingInstruction", start, endIndex)
    override fun onSelfClosingTag(endIndex: Int) {
        events.add(TokenEvent("selfClosingTag", 0, endIndex, 0, ""))
    }
    override fun onText(start: Int, endIndex: Int) = add("text", start, endIndex)
    override fun onTextEntity(codepoint: Int, endIndex: Int) {
        events.add(TokenEvent("textEntity", 0, endIndex, codepoint, codePointToString(codepoint)))
    }

    companion object {
        private val valueEvents = setOf(
            "attribData", "attribName", "cdata", "closeTag", "comment",
            "declaration", "openTagName", "processingInstruction", "text",
        )
    }
}

/** Run the tokenizer over the whole input in a single [KsoupTokenizer.write]. */
@OptIn(ExperimentalKsoupApi::class)
internal fun tokenizeWhole(input: String, options: KsoupHtmlOptions = KsoupHtmlOptions.Default): List<TokenEvent> {
    val recorder = RecordingCallbacks()
    recorder.input = input
    val tokenizer = KsoupTokenizer(options, recorder)
    tokenizer.write(input)
    tokenizer.end()
    return recorder.events
}

/** Run the tokenizer over the input, feeding one fixed-size chunk per write. */
@OptIn(ExperimentalKsoupApi::class)
internal fun tokenizeChunked(
    input: String,
    chunkSize: Int,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): List<TokenEvent> {
    val recorder = RecordingCallbacks()
    recorder.input = input
    val tokenizer = KsoupTokenizer(options, recorder)
    var index = 0
    while (index < input.length) {
        val next = minOf(index + chunkSize, input.length)
        tokenizer.write(input.substring(index, next))
        index = next
    }
    tokenizer.end()
    return recorder.events
}

/**
 * Feed the input as two chunks cut at [cut]. Mirrors the way a stream might
 * arrive, without relying on a fixed chunk size.
 */
@OptIn(ExperimentalKsoupApi::class)
internal fun tokenizeTwoChunks(
    input: String,
    cut: Int,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): List<TokenEvent> {
    val recorder = RecordingCallbacks()
    recorder.input = input
    val tokenizer = KsoupTokenizer(options, recorder)
    tokenizer.write(input.substring(0, cut))
    tokenizer.write(input.substring(cut))
    tokenizer.end()
    return recorder.events
}

/**
 * Adjacent `text` (and `attribData`) pieces are the same logical token: chunk
 * boundaries may split delivery, but merging them has to reproduce the whole
 * absolute span and decoded value. Entity events are standalone and never
 * merged. All other events must match exactly, including absolute offsets.
 */
internal fun normalize(events: List<TokenEvent>): List<TokenEvent> {
    val result = mutableListOf<TokenEvent>()
    for (event in events) {
        val mergeable = event.name == "text" || event.name == "attribData"
        val previous = result.lastOrNull()
        if (
            mergeable &&
            previous != null &&
            previous.name == event.name &&
            previous.end == event.start
        ) {
            result[result.lastIndex] = TokenEvent(
                name = previous.name,
                start = previous.start,
                end = event.end,
                extra = 0,
                value = previous.value + event.value,
            )
        } else {
            result.add(event)
        }
    }
    return result
}

internal fun assertNormalizedEquals(
    expected: List<TokenEvent>,
    actual: List<TokenEvent>,
    message: String = "",
) {
    val normalizedExpected = normalize(expected)
    val normalizedActual = normalize(actual)
    if (normalizedExpected != normalizedActual) {
        throw AssertionError(
            buildString {
                append(message)
                append('\n')
                append("expected:\n")
                normalizedExpected.forEach { append("  ").append(it).append('\n') }
                append("actual:\n")
                normalizedActual.forEach { append("  ").append(it).append('\n') }
            }
        )
    }
}


/** Deterministic UTF-16 encoding, never relying on platform default behavior. */
internal fun codePointToString(codePoint: Int): String =
    if (codePoint <= 0xFFFF) {
        Char(codePoint).toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf(
            Char(0xD800 + (offset ushr 10)),
            Char(0xDC00 + (offset and 0x3FF)),
        ).concatToString()
    }
