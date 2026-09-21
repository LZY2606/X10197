package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions

/** A recorded handler event; payloads are captured in emission order. */
internal data class HandlerEvent(
    val name: String,
    val text: String? = null,
    val tag: String? = null,
    val implied: Boolean? = null,
    val attrName: String? = null,
    val attrValue: String? = null,
    val quote: String? = null,
    val attributes: List<Pair<String, String>>? = null,
    val error: String? = null,
)

internal class RecordingHandler : KsoupHtmlHandler {
    val events = mutableListOf<HandlerEvent>()

    override fun onText(text: String) { events += HandlerEvent("text", text = text) }
    override fun onOpenTagName(name: String) { events += HandlerEvent("openTagName", tag = name) }
    override fun onOpenTag(name: String, attributes: Map<String, String>, isImplied: Boolean) {
        events += HandlerEvent("openTag", tag = name, implied = isImplied,
            attributes = attributes.entries.map { it.key to it.value })
    }
    override fun onCloseTag(name: String, isImplied: Boolean) {
        events += HandlerEvent("closeTag", tag = name, implied = isImplied)
    }
    override fun onAttribute(name: String, value: String, quote: String?) {
        events += HandlerEvent("attribute", attrName = name, attrValue = value, quote = quote)
    }
    override fun onComment(comment: String) { events += HandlerEvent("comment", text = comment) }
    override fun onCommentEnd() { events += HandlerEvent("commentEnd") }
    override fun onCDataStart() { events += HandlerEvent("cdataStart") }
    override fun onCDataEnd() { events += HandlerEvent("cdataEnd") }
    override fun onProcessingInstruction(name: String, data: String) {
        events += HandlerEvent("processingInstruction", tag = name, text = data)
    }
    override fun onEnd() { events += HandlerEvent("end") }
    override fun onReset() { events += HandlerEvent("reset") }
    override fun onError(error: Exception) { events += HandlerEvent("error", error = error.message) }
}

internal fun runParserFull(
    input: String,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): Pair<KsoupHtmlParser, RecordingHandler> {
    val handler = RecordingHandler()
    val parser = KsoupHtmlParser(handler = handler, options = options)
    parser.parseComplete(input)
    return parser to handler
}

internal fun runParserChunked(
    input: String,
    cuts: IntArray,
    options: KsoupHtmlOptions = KsoupHtmlOptions.Default,
): Pair<KsoupHtmlParser, RecordingHandler> {
    val handler = RecordingHandler()
    val parser = KsoupHtmlParser(handler = handler, options = options)
    parser.reset()
    var previous = 0
    for (cut in cuts) {
        parser.write(input.substring(previous, cut))
        previous = cut
    }
    if (previous < input.length) parser.write(input.substring(previous))
    parser.end()
    return parser to handler
}

/**
 * Adjacent text events are the only legitimate difference between a full
 * parse and chunked delivery; comments/attributes/tags are kept verbatim.
 */
internal fun normalizeHandlerEvents(events: List<HandlerEvent>): List<HandlerEvent> {
    val result = mutableListOf<HandlerEvent>()
    for (event in events) {
        val last = result.lastOrNull()
        if (event.name == "text" && last?.name == "text") {
            result[result.size - 1] = last.copy(text = (last.text ?: "") + (event.text ?: ""))
        } else {
            result += event
        }
    }
    return result
}
