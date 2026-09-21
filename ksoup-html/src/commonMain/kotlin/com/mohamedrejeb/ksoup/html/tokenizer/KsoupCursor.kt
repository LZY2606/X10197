package com.mohamedrejeb.ksoup.html.tokenizer

/**
 * A single appended input chunk, owned by the [KsoupCursor].
 *
 * Prefixes are released by dropping whole leading segments, so segments
 * are never mutated. [base] is the absolute UTF-16 offset of the chunk's
 * first character.
 */
internal class KsoupSegment(
    val data: String,
    val base: Int,
) {
    /** Absolute offset one past the chunk's last character. */
    val end: Int get() = this.base + this.data.length

    /** Number of characters retained. */
    val length: Int get() = this.data.length

    fun charAt(absoluteIndex: Int): Char = this.data[absoluteIndex - this.base]

    fun substring(from: Int, to: Int): String =
        this.data.substring(from - this.base, to - this.base)
}

/**
 * Single owner of the tokenizer's position and buffer lifecycle state.
 *
 * The cursor unifies what used to be spread across the tokenizer
 * ("current chunk offset", "cumulative input offset", "the buffer range
 * that still has to be retained") and the parser (its own copy of every
 * written chunk). Every token reports absolute spans via this cursor,
 * every pending token marks the oldest position still referenced, and
 * [releaseConsumed] is the only place prefixes are freed.
 *
 * Positions are absolute offsets in UTF-16 code units of the full input
 * stream. Lone surrogates are preserved as the individual code units they
 * are; a cut between a high and a low surrogate is transparent.
 *
 * Segments live in an [ArrayDeque] in append order. An index of the
 * segment containing [index] is cached for O(1) per-character access, and
 * the cache is always validated against the live deque so it can never
 * reference a released segment.
 */
internal class KsoupCursor {

    private val segments = ArrayDeque<KsoupSegment>()

    /** Deque index of the segment containing [index]; `-1` if empty. */
    private var currentIndex: Int = -1

    /** Absolute index of the next character to consume. */
    var index: Int = 0
        private set

    /**
     * Absolute start of the token/section currently being assembled.
     * `-1` when no section is open (matching the legacy tokenizer).
     */
    var sectionStart: Int = 0

    /** Absolute start of the entity currently being decoded, or `-1`. */
    var entityStart: Int = -1

    /** Total number of appended characters. */
    private var totalLength: Int = 0

    /**
     * Package-private memory metrics.
     *
     * [activeChars] is the number of characters currently retained, i.e.
     * the amount still referenced by pending tokens. [peakActiveChars] is
     * the maximum [activeChars] ever held; it bounds memory by the largest
     * pending token, not by total stream length. [copiedChars] counts
     * characters copied across segment boundaries while slicing; it stays
     * linear in the input instead of growing with repeated token assembly.
     */
    var activeChars: Int = 0
        private set
    var peakActiveChars: Int = 0
        private set
    var copiedChars: Int = 0
        private set

    fun reset() {
        this.segments.clear()
        this.currentIndex = -1
        this.index = 0
        this.sectionStart = 0
        this.entityStart = -1
        this.totalLength = 0
        this.activeChars = 0
        this.peakActiveChars = 0
        this.copiedChars = 0
    }

    /** Append a new chunk. Appending never moves [index]. */
    fun append(chunk: String) {
        this.segments.addLast(KsoupSegment(data = chunk, base = this.totalLength))
        this.totalLength += chunk.length
        if (this.currentIndex < 0) {
            this.currentIndex = 0
        }
        this.activeChars += chunk.length
        if (this.activeChars > this.peakActiveChars) {
            this.peakActiveChars = this.activeChars
        }
    }

    /** Absolute offset one past the last appended character (EOF position). */
    val endOffset: Int get() = this.totalLength

    fun hasNext(): Boolean = this.index < this.totalLength

    private fun currentSegment(): KsoupSegment {
        // Validate the cached index against both the deque bounds and the
        // segment's own [base, end) range instead of trusting a stale hint.
        val cached = this.currentIndex
        if (
            cached !in 0 until this.segments.size ||
            this.index < this.segments[cached].base ||
            this.index >= this.segments[cached].end
        ) {
            this.locateIndex(this.index)
        }
        return this.segments[this.currentIndex]
    }

    /** Character at [index] as a UTF-16 code unit. */
    fun currentChar(): Int = this.currentSegment().charAt(this.index).code

    /** Consume the character at [index], crossing into the next segment. */
    fun advance() {
        this.index++
        if (this.currentIndex in 0 until this.segments.size) {
            if (this.index >= this.segments[this.currentIndex].end) {
                this.currentIndex++
                if (this.currentIndex >= this.segments.size) {
                    this.currentIndex = this.segments.size - 1
                }
            }
        }
    }

    /**
     * Move the read position. Forward jumps (fast-forward) stay in or pass
     * the cached segment; backward jumps (entity restart) may land in an
     * earlier segment, which is handled by the deque walk.
     */
    fun setIndex(newIndex: Int) {
        this.index = newIndex
        this.locateIndex(newIndex)
    }

    /**
     * Point [currentIndex] at the segment that contains [absoluteIndex],
     * i.e. the segment with `base <= absoluteIndex < end`. A position at or
     * past the last live segment clamps to the final segment (e.g. the
     * fast-forward not-found position).
     *
     * Lookup walks one segment at a time from the cached position. Forward
     * traversal (parsing/fast-forward) and backward traversal (entity
     * restart) are both amortized O(1); releases shift the cache in
     * [releaseConsumed], so this never rescans the whole stream.
     */
    private fun locateIndex(absoluteIndex: Int) {
        if (this.segments.isEmpty()) {
            this.currentIndex = -1
            return
        }
        var i = this.currentIndex
        if (i < 0 || i >= this.segments.size) i = 0
        while (i > 0 && absoluteIndex < this.segments[i].base) {
            i--
        }
        while (i < this.segments.size - 1 && absoluteIndex >= this.segments[i].end) {
            i++
        }
        this.currentIndex = i
    }

    /**
     * Extract the code units of the absolute span [[from], [to]).
     *
     * The tokenizer reports spans derived from input boundaries, so lone
     * surrogates come out verbatim and a surrogate pair is never split by
     * slicing itself.
     */
    fun slice(from: Int, to: Int): String {
        if (from == to) return ""

        // Use a local segment pointer: slicing must not move the read
        // cursor's cached segment. Walk back from the cache when nearby.
        var segmentIndex = this.currentIndex
        if (segmentIndex !in 0 until this.segments.size) {
            segmentIndex = 0
        }
        while (segmentIndex > 0 && from < this.segments[segmentIndex].base) {
            segmentIndex--
        }
        while (
            segmentIndex < this.segments.size - 1 &&
            from >= this.segments[segmentIndex].end
        ) {
            segmentIndex++
        }
        var segment = this.segments[segmentIndex]

        if (to <= segment.end) {
            return segment.substring(from, to)
        }

        val builder = StringBuilder(to - from)
        var position = from
        while (true) {
            val partEnd = minOf(to, segment.end)
            builder.append(segment.data, position - segment.base, partEnd - segment.base)
            this.copiedChars += partEnd - position
            position = partEnd
            if (position >= to) break
            segmentIndex++
            if (segmentIndex >= this.segments.size) break
            segment = this.segments[segmentIndex]
        }
        return builder.toString()
    }

    /**
     * Release every prefix no longer referenced by an unfinished token.
     *
     * The oldest live position is the minimum of the read position, the
     * open section start and the pending entity start. Only whole, fully
     * consumed segments are dropped, so parsing stays O(1) per character;
     * a partially consumed head segment is retained until it is complete.
     */
    fun releaseConsumed() {
        var watermark = this.index
        if (this.sectionStart in 0..<watermark) {
            watermark = this.sectionStart
        }
        if (this.entityStart in 0..<watermark) {
            watermark = this.entityStart
        }

        var removed = 0
        while (this.segments.isNotEmpty() && this.segments.first().end <= watermark) {
            this.activeChars -= this.segments.removeFirst().length
            removed++
        }
        if (removed > 0) {
            // Shift the cached pointer, never leaving it dangling.
            this.currentIndex = if (this.segments.isEmpty()) -1 else
                (this.currentIndex - removed).coerceAtLeast(0)
        }
    }
}
