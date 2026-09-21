package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.annotation.ExperimentalKsoupApi
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser

/**
 * One recorded raw tokenizer callback.
 *
 * Token values are decoded eagerly through the tokenizer's cursor, so the
 * recorded span pairs the exact absolute offsets the callback received
 * with the code units they referred to at that moment.
 */
internal data class TokenEvent(
    val name: String,
    val start: Int = -1,
    val end: Int = -1,
    val extra: Int = 0,
    val value: String? = null,
    val codepoint: Int = -1,
)

@OptIn(ExperimentalKsoupApi::class)
internal class RecordingTokenizerCallbacks(
    private val tokenizer: KsoupTokenizer,
) : KsoupTokenizerCallbacks {
    val events = mutableListOf<TokenEvent>()
    var endCount = 0
        private set

    /**
     * Resolve a span to its code units. Two historical quirks produce
     * spans a cursor cannot resolve, both reachable from malformed short
     * comments (`<!-->`, `<!--->`): a negative start, and a content end
     * (callback end minus the closing-sequence offset) before the start.
     * The parser has always thrown on these; record the raw span instead
     * so full/chunk equivalence can still be checked here.
     */
    private fun resolve(start: Int, endIndex: Int): String? =
        if (start < 0 || start > endIndex) null else tokenizer.slice(start, endIndex)

    override fun onAttribData(start: Int, endIndex: Int) {
        events += TokenEvent("attribData", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onAttribEntity(codepoint: Int) {
        events += TokenEvent("attribEntity", codepoint = codepoint)
    }

    override fun onAttribEnd(quote: KsoupHtmlParser.QuoteType, endIndex: Int) {
        events += TokenEvent("attribEnd:" + quote.name, end = endIndex)
    }

    override fun onAttribName(start: Int, endIndex: Int) {
        events += TokenEvent("attribName", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onCData(start: Int, endIndex: Int, offset: Int) {
        events += TokenEvent("cdata", start, endIndex, offset, resolve(start, endIndex - offset))
    }

    override fun onCloseTag(start: Int, endIndex: Int) {
        events += TokenEvent("closeTag", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onComment(start: Int, endIndex: Int, offset: Int) {
        events += TokenEvent("comment", start, endIndex, offset, resolve(start, endIndex - offset))
    }

    override fun onDeclaration(start: Int, endIndex: Int) {
        events += TokenEvent("declaration", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onEnd() {
        endCount++
        events += TokenEvent("end")
    }

    override fun onOpenTagEnd(endIndex: Int) {
        events += TokenEvent("openTagEnd", end = endIndex)
    }

    override fun onOpenTagName(start: Int, endIndex: Int) {
        events += TokenEvent("openTagName", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onProcessingInstruction(start: Int, endIndex: Int) {
        events += TokenEvent("processingInstruction", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onSelfClosingTag(endIndex: Int) {
        events += TokenEvent("selfClosingTag", end = endIndex)
    }

    override fun onText(start: Int, endIndex: Int) {
        events += TokenEvent("text", start, endIndex, value = resolve(start, endIndex))
    }

    override fun onTextEntity(codepoint: Int, endIndex: Int) {
        events += TokenEvent("textEntity", end = endIndex, codepoint = codepoint)
    }
}

/** Run [input] as a single write plus end, returning tokenizer and recordings. */
@OptIn(ExperimentalKsoupApi::class)
internal fun runTokenizerFull(
    input: String,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): Pair<KsoupTokenizer, RecordingTokenizerCallbacks> {
    lateinit var recorder: RecordingTokenizerCallbacks
    val tokenizer = KsoupTokenizer(options, object : KsoupTokenizerCallbacks {
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
    tokenizer.write(input)
    tokenizer.end()
    return tokenizer to recorder
}

/** Run [input] split at the given UTF-16 cut positions, writing chunks in order. */
@OptIn(ExperimentalKsoupApi::class)
internal fun runTokenizerChunked(
    input: String,
    cuts: IntArray,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): Pair<KsoupTokenizer, RecordingTokenizerCallbacks> {
    lateinit var recorder: RecordingTokenizerCallbacks
    val tokenizer = KsoupTokenizer(options, object : KsoupTokenizerCallbacks {
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
    var previous = 0
    for (cut in cuts) {
        tokenizer.write(input.substring(previous, cut))
        previous = cut
    }
    if (previous < input.length) tokenizer.write(input.substring(previous))
    tokenizer.end()
    return tokenizer to recorder
}

/**
 * Normalize a raw event stream for full/chunk equivalence.
 *
 * Chunk boundaries can legitimately split adjacent text/attribute-data
 * runs, so consecutive span-adjacent payload events are merged. Every
 * other token type is kept verbatim, including its absolute span.
 */
internal fun normalizeTokenEvents(events: List<TokenEvent>): List<TokenEvent> {
    val mergeable = setOf("text", "attribData")
    val result = mutableListOf<TokenEvent>()
    for (event in events) {
        val last = result.lastOrNull()
        if (
            last != null &&
            event.name in mergeable &&
            last.name == event.name &&
            last.end == event.start
        ) {
            result[result.size - 1] = last.copy(
                end = event.end,
                value = (last.value ?: "") + (event.value ?: ""),
            )
        } else {
            result += event
        }
    }
    return result
}
