package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import kotlin.test.Test

/**
 * JVM-only exhaustive cut matrices that are too slow to run within the
 * per-test timeout of the JS/Node test runner. The semantic contract they
 * enforce is the same as [KsoupTokenizerContractTest].
 */
class KsoupTokenizerExhaustiveJvmTest {

    // Mirrors the fixture list of the common contract test.
    private val inputs = listOf(
        "ab\uD83D\uDE00cd",
        "x\uD83Dy\uDE00z\uD83D\uDE00",
        "<p>a\r\nb</p><p>\r\n</p>",
        "<p>&amp; &copy; a&amp;b &#169; &unknown; &CounterClockwiseContourIntegral;</p>",
        "<a b=\"x&amp;y\" c='a&copy;b' d=z&amp;q e>v</a>",
        "a<!--c-->b<!---->c<!--->d<!---x--->e",
        "<!--ab-->",
        "<script>var s='</script>'; if(a<b){}</script>x",
        "<style>a > b {}</style>y",
        "<title>a&amp;b</title>z",
        "<svg><![CDATA[a]b]] & ]]></svg><math/>",
        "<!DOCTYPE html><?pi data?></x ><!",
        "<div a='1' b=\"2\" c=3 d e = f g></div>",
        "<p>abc<div attr=\"unclosed",
    )

    private fun assertEquivalent(input: String, cuts: IntArray, options: KsoupHtmlOptions) {
        val full = normalizeTokenEvents(runTokenizerFull(input, options).second.events)
        val chunked = normalizeTokenEvents(runTokenizerChunked(input, cuts, options).second.events)
        kotlin.test.assertEquals(full, chunked, "cuts=${cuts.toList()}")
    }

    @Test
    fun allPairsOfCutsAreEquivalentHtml() {
        for (input in inputs) {
            for (i in 1 until input.length) {
                for (j in i + 1 until input.length) {
                    assertEquivalent(input, intArrayOf(i, j), KsoupHtmlOptions.Default)
                }
            }
        }
    }

    @Test
    fun allPairsOfCutsAreEquivalentXml() {
        val xml = KsoupHtmlOptions(
            xmlMode = true,
            decodeEntities = true,
            lowerCaseTags = false,
            lowerCaseAttributeNames = false,
            recognizeCDATA = true,
            recognizeSelfClosing = true,
        )
        for (input in inputs) {
            for (i in 1 until input.length) {
                for (j in i + 1 until input.length) {
                    assertEquivalent(input, intArrayOf(i, j), xml)
                }
            }
        }
    }
}
