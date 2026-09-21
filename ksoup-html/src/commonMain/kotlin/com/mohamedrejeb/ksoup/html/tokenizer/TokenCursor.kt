package com.mohamedrejeb.ksoup.html.tokenizer

/**
 * Owns the streaming input position and lifecycle for [KsoupTokenizer].
 *
 * The cursor is the single owner of three things that previously lived across
 * the tokenizer (`buffer`, `index`, `offset`, `sectionStart`, `entityStart`)
 * and the parser (`buffers`, `bufferOffset`):
 *
 *  - **Absolute token spans.** Every index handed to a callback is an absolute
 *    UTF-16 offset from the start of the stream, independent of how the input
 *    was split across [write] calls.
 *  - **The current pending state.** [index] is the next character to consume,
 *    [sectionStart] marks the beginning of the token currently being assembled,
 *    and [entityStart] marks a pending entity (while [entityActive] is set).
 *    The tokenizer owns the state machine; the cursor owns where it points.
 *  - **The releasable prefix.** Input segments are kept in a deque and dropped
 *    in [releaseConsumedPrefix] as soon as no pending token can reference them.
 *
 * Characters are addressed as UTF-16 code units (one [Char]), never through
 * platform default decoding/normalization, so isolated surrogates and
 * combining marks split across chunks behave deterministically.
 *
 * Segments are appended as-is; their contents are copied out at most once per
 * token via [slice] (one backing string, or one [StringBuilder] when a range
 * spans segments). The tokenizer never incrementally concatenates token text
 * into internal buffers.
 */
internal class TokenCursor {

    /** Contiguous retained pieces of the input, in stream order. */
    private val segments = ArrayDeque<String>()

    /** Absolute offset of the first character of [segments]'s first element. */
    private var baseOffset: Int = 0

    /** Absolute offset one past the last retained character. */
    private var endOffset: Int = 0

    /** Absolute index of the next character to consume. */
    var index: Int = 0

    /**
     * Absolute beginning of the token currently being read. `-1` means no
     * section is open (e.g. between attributes) and it is not a retain anchor.
     */
    var sectionStart: Int = 0

    /** Absolute start of the entity currently being decoded. */
    var entityStart: Int = 0

    /** Whether an entity is in flight ([entityStart] is a retain anchor). */
    var entityActive: Boolean = false
        private set

    /** Total absolute length written so far (diagnostics). */
    val totalLength: Int get() = endOffset

    /** Number of characters currently resident in retained segments. */
    val retainedLength: Int get() = endOffset - baseOffset

    /**
     * Peak number of characters that ever had to stay resident. Bounded by the
     * longest token that was in flight, never by total stream length.
     */
    var peakRetainedLength: Int = 0
        private set

    /** Count of [slice] materializations spanning more than one segment. */
    var multiSegmentSliceCount: Int = 0
        private set

    /** Count of characters copied while materializing spanning slices. */
    var copiedCharacterCount: Long = 0L
        private set

    fun reset() {
        segments.clear()
        baseOffset = 0
        endOffset = 0
        index = 0
        sectionStart = 0
        entityStart = 0
        entityActive = false
        peakRetainedLength = 0
        multiSegmentSliceCount = 0
        copiedCharacterCount = 0L
    }

    /** Append a new input segment and make it available for consumption. */
    fun write(chunk: String) {
        if (chunk.isNotEmpty()) {
            segments.addLast(chunk)
            endOffset += chunk.length
            noteRetention()
        }
    }

    /** Whether there is at least one unconsumed character at [index]. */
    fun hasMore(): Boolean = index < endOffset

    /** Character at absolute [position]. */
    fun charAt(position: Int): Char {
        // Fast path: parsing typically advances through the head segment.
        val head = segments.first()
        val local = position - baseOffset
        if (local >= 0 && local < head.length) {
            return head[local]
        }
        return charAtSlow(position)
    }

    private fun charAtSlow(position: Int): Char {
        var base = baseOffset
        val it = segments.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            val local = position - base
            if (local in 0 until seg.length) return seg[local]
            base += seg.length
        }
        throw IndexOutOfBoundsException("position $position outside [$baseOffset,$endOffset)")
    }

    /** Character at the current [index]. */
    fun currentChar(): Char = charAt(index)

    fun advance() {
        index++
    }

    fun rewindTo(position: Int) {
        index = position
    }

    fun beginEntity() {
        entityStart = index
        entityActive = true
    }

    fun clearEntity() {
        entityActive = false
    }

    /** One-past-the-last absolute index currently available. */
    fun end(): Int = endOffset

    /**
     * Materialize the raw text for the absolute range [[start],[end]).
     * At most one allocation: a single backing string when the range lives in
     * one segment, or one [StringBuilder] when it spans segments.
     */
    fun slice(start: Int, end: Int): String {
        if (start == end) return ""
        val head = segments.first()
        val headEnd = end - baseOffset
        if (headEnd <= head.length) {
            return head.substring(start - baseOffset, headEnd)
        }
        return sliceSlow(start, end)
    }

    private fun sliceSlow(start: Int, end: Int): String {
        multiSegmentSliceCount++
        val length = end - start
        copiedCharacterCount += length.toLong()
        val sb = StringBuilder(length)
        var base = baseOffset
        val it = segments.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            val segEnd = base + seg.length
            if (segEnd > start && base < end) {
                val from = (start - base).coerceAtLeast(0)
                val to = (end - base).coerceAtMost(seg.length)
                sb.append(seg, from, to)
            }
            base = segEnd
            if (base >= end) break
        }
        return sb.toString()
    }

    /**
     * Drop every leading segment that no pending token can reference.
     *
     * The earliest live absolute position ([frontier]) is the minimum of the
     * consumption cursor and every open token anchor. A head segment is
     * released only when it ends at or before [frontier], so the retained
     * segments always cover everything the in-flight token might read.
     */
    fun releaseConsumedPrefix() {
        var frontier = index
        if (sectionStart >= 0 && sectionStart < frontier) frontier = sectionStart
        if (entityActive && entityStart < frontier) frontier = entityStart

        // Always retain a (possibly empty) tail segment: after end() the
        // tokenizer may still compute end positions / call [charAt] while
        // handling a repeated end(), mirroring the previous empty-buffer state.
        while (segments.size > 1) {
            val head = segments.first()
            val headEnd = baseOffset + head.length
            if (headEnd <= frontier) {
                segments.removeFirst()
                baseOffset = headEnd
            } else {
                break
            }
        }
        noteRetention()
    }

    private fun noteRetention() {
        val retained = endOffset - baseOffset
        if (retained > peakRetainedLength) peakRetainedLength = retained
    }
}
