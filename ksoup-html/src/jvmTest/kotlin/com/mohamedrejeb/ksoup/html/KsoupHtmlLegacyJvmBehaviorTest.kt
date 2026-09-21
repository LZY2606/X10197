package com.mohamedrejeb.ksoup.html

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * JVM-only characterization: on the JVM, malformed short comments
 * produce negative/inverted spans that `String.substring` rejects, and the
 * legacy parser propagated that [IndexOutOfBoundsException]. HTML
 * error-recovery policy is intentionally left untouched by the cursor
 * refactor. (On JS, native substring semantics differ; the raw tokenizer
 * spans are covered platform-independently by [KsoupHtmlLegacyBehaviorTest].)
 */
class KsoupHtmlLegacyJvmBehaviorTest {

    private val malformedShortComments = listOf("<!--->", "<!--->x", "<!-->x")

    @Test
    fun parserStillThrowsForMalformedShortComments_fullInput() {
        for (input in malformedShortComments) {
            val parser = KsoupHtmlParser(handler = KsoupHtmlHandler.Default)
            parser.reset()
            assertFailsWith<IndexOutOfBoundsException>("input=$input") {
                parser.write(input)
                parser.end()
            }
        }
    }

    @Test
    fun parserStillThrowsForMalformedShortComments_chunkedInput() {
        for (input in malformedShortComments) {
            for (cut in 1 until input.length) {
                val parser = KsoupHtmlParser(handler = KsoupHtmlHandler.Default)
                parser.reset()
                assertFailsWith<IndexOutOfBoundsException>("input=$input cut=$cut") {
                    parser.write(input.substring(0, cut))
                    parser.write(input.substring(cut))
                    parser.end()
                }
            }
        }
    }
}
