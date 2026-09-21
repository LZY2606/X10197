package com.mohamedrejeb.ksoup.html.tokenizer

/**
 * Single owner of the tokenizer's input position and buffer lifecycle.
 *
 * The cursor stores the active input as an ordered list of string segments
 * (one per non-empty write) addressed by absolute UTF-16 code unit offsets.
 * Pending token positions ([index], [sectionStart], [entityStart]) and the
 * range that must still be retained all live here, so the tokenizer states
 * never have to coordinate a "current chunk offset" with an "accumulated
 * input offset".
 *
 * Segments that are no longer referenced by any unfinished token can be
 * released via [releasePrefix], allowing arbitrarily long streamed inputs
 * without retaining the whole document.
 *
 * Characters and slices are always taken from the original UTF-16 units; no
 * platform default encoding or replacement-character behavior is involved,
 * so lone surrogates and surrogate pairs split across writes are preserved.
 */
internal class TokenCursor {

    /** Active segments in write order, oldest first. Never contains empty strings. */
    private val segments = ArrayDeque<String>()

    /** Absolute offset of the first character of [segments]' first segment. */
    private var segmentStart = 0

    /** Absolute offset one past the last character of the last active segment. */
    private var endOffset = 0

    /** Cached segment for the hot [charAt] loop. */
    private var cached: String = ""
    private var cachedStart = 0
    private var cachedEnd = 0

    /** Chunks delivered while the tokenizer was paused, in arrival order. */
    private val pending = ArrayDeque<String>()

    /** The index within the input that is currently being read. */
    var index: Int = 0

    /** The beginning of the section that is currently being read, or -1. */
    var sectionStart: Int = 0

    /** The start of the entity currently being decoded. */
    var entityStart: Int = 0

    /**
     * Number of UTF-16 units copied while assembling slices that span two or
     * more segments. Slices contained in a single segment are zero-copy
     * substrings. Package-private allocation baseline metric.
     */
    var crossSegmentCopyCount: Int = 0
        private set

    /** Absolute offset one past the end of the active input. */
    val end: Int get() = this.endOffset

    /** Absolute offset of the beginning of the oldest retained segment. */
    val start: Int get() = this.segmentStart

    /** Number of currently retained segments. Package-private metric. */
    val retainedSegmentCount: Int get() = this.segments.size

    /** Number of UTF-16 units currently retained. */
    val retainedLength: Int
        get() = if (this.segments.isEmpty()) 0 else this.endOffset - this.segmentStart

    /** Number of chunks queued while the tokenizer was paused. */
    val pendingCount: Int get() = this.pending.size

    val hasPending: Boolean get() = this.pending.isNotEmpty()

    /** Appends a chunk to the active input. Empty chunks are ignored. */
    fun appendActive(chunk: String) {
        if (chunk.isEmpty()) return
        this.segments.addLast(chunk)
        this.endOffset += chunk.length
    }

    /** Stores a chunk received while the tokenizer is paused. */
    fun queuePending(chunk: String) {
        if (chunk.isNotEmpty()) this.pending.addLast(chunk)
    }

    /** Removes and returns the next chunk delivered while paused. */
    fun nextPending(): String? = this.pending.removeFirstOrNull()

    /**
     * The UTF-16 code unit at the given absolute position. Positions always
     * refer to the original input units; no decoding is performed here.
     */
    fun charAt(position: Int): Int {
        if (position < this.cachedStart || position >= this.cachedEnd) {
            this.locate(position)
        }
        return this.cached[position - this.cachedStart].code
    }

    private fun locate(position: Int) {
        var segStart = this.segmentStart
        var segIndex = 0
        for (segment in this.segments) {
            val segEnd = segStart + segment.length
            if (position < segEnd) {
                this.cached = segment
                this.cachedStart = segStart
                this.cachedEnd = segEnd
                return
            }
            segStart = segEnd
            segIndex++
        }
        throw IndexOutOfBoundsException(
            "Position $position outside of active range ${this.segmentStart}..${this.endOffset}"
        )
    }

    /**
     * Returns the original units in the absolute range [start, end). The range
     * must reference retained (not yet released) data.
     */
    fun substring(start: Int, end: Int): String {
        if (start == end) return ""

        var segStart = this.segmentStart
        var startSegment = 0
        while (startSegment < this.segments.size) {
            val segment = this.segments[startSegment]
            if (segStart + segment.length > start) break
            segStart += segment.length
            startSegment++
        }
        require(startSegment < this.segments.size) {
            "Range $start..$end starts outside of retained range ${this.segmentStart}..${this.endOffset}"
        }

        val first = this.segments[startSegment]
        val firstEnd = segStart + first.length

        if (end <= firstEnd) {
            return first.substring(start - segStart, end - segStart)
        }

        val builder = StringBuilder(end - start)
        builder.append(first.substring(start - segStart))
        var consumed = firstEnd
        var segmentIndex = startSegment + 1
        while (segmentIndex < this.segments.size) {
            val segment = this.segments[segmentIndex]
            val nextConsumed = consumed + segment.length
            if (end <= nextConsumed) {
                builder.append(segment.substring(0, end - consumed))
                break
            }
            builder.append(segment)
            consumed = nextConsumed
            segmentIndex++
        }
        this.crossSegmentCopyCount += end - start
        return builder.toString()
    }

    /**
     * Releases every active segment that lies entirely before [horizon].
     * The segment containing [horizon] is always retained so pending tokens
     * can still be read and rewinded to.
     */
    fun releasePrefix(horizon: Int) {
        while (this.segments.isNotEmpty()) {
            val firstLength = this.segments.first().length
            if (this.segments.size > 1 && this.segmentStart + firstLength <= horizon) {
                this.segmentStart += firstLength
                this.segments.removeFirst()
            } else {
                break
            }
        }

        if (
            this.segments.size == 1 &&
            this.segmentStart + this.segments.first().length <= horizon
        ) {
            this.segmentStart += this.segments.first().length
            this.segments.removeFirst()
        }

        this.cached = ""
        this.cachedStart = 0
        this.cachedEnd = 0
    }

    fun reset() {
        this.segments.clear()
        this.pending.clear()
        this.segmentStart = 0
        this.endOffset = 0
        this.cached = ""
        this.cachedStart = 0
        this.cachedEnd = 0
        this.index = 0
        this.sectionStart = 0
        this.entityStart = 0
        this.crossSegmentCopyCount = 0
    }
}
