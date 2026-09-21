package com.mohamedrejeb.ksoup.html.tokenizer

import kotlin.math.max

/**
 * Single owner of the tokenizer's stream position and retained input.
 *
 * Before this abstraction, "offset of the current chunk", "offset into the
 * accumulated stream" and "which part of the old chunk still has to be kept"
 * were spread across the tokenizer and parser as implicit, cooperating state.
 * [TokenizerCursor] collects all three concerns:
 *
 *  - every position reported to callbacks is an absolute UTF-16 offset into
 *    the complete input stream;
 *  - [index], [sectionStart] and [entityStart] are the only pending state, and
 *    live here;
 *  - [releasePrefix] drops every prefix that is no longer referenced by an
 *    unfinished token, so a long stream is never retained in full.
 *
 * The retained data is held as the original write chunks (segments). Prefixes
 * are released by dropping whole segments; there is no repeated copying of the
 * pending text. Slicing across segments allocates exactly one [String], only
 * when the requested span really spans two segments.
 *
 * Surrogate handling is intentionally explicit: a chunk ending in a high
 * surrogate cannot be decoded until the next write arrives (or EOF proves the
 * unit is isolated). That pending unit is owned by the cursor rather than
 * relying on any platform default.
 */
internal class TokenizerCursor {

    /** Retained chunks covering `[base, limit)`, in write order. */
    private val segments = ArrayDeque<String>()

    /** Absolute offset of the first retained character. */
    private var base: Int = 0

    /**
     * Absolute offset one past the data readable by the state machine.
     * A trailing high surrogate is excluded until the next write or EOF.
     */
    private var limit: Int = 0

    /** High surrogate waiting for a possible low surrogate in the next write. */
    private var heldHighSurrogate: Char? = null

    /** The index within the stream that we are currently looking at. */
    var index: Int = 0
        private set

    /** The beginning of the section that is currently being read. */
    var sectionStart: Int = 0

    /** The start of the entity currently being scanned, if any. */
    var entityStart: Int = 0
    private var hasEntityMark: Boolean = false

    // Cache for the segment backing [index]. Forward scans are O(1) amortized.
    private var cacheSegmentIndex: Int = 0
    private var cacheSegmentBase: Int = 0

    /** Diagnostic: total UTF-16 units currently retained. */
    val retainedLength: Int get() = limit - base

    /** Diagnostic: number of retained segments. */
    val retainedSegmentCount: Int get() = segments.size

    /** Diagnostic: joins performed while slicing across segment boundaries. */
    var crossSegmentSlices: Int = 0
        private set

    /** Diagnostic: times a trailing high surrogate was kept across a write. */
    var heldSurrogateJoins: Int = 0
        private set

    /** Absolute offset one past the data currently readable by the state machine. */
    val readableLimit: Int get() = limit

    /** Absolute offset one past everything appended (including a held unit). */
    val endOffset: Int get() = limit + (if (heldHighSurrogate != null) 1 else 0)

    /** Append a new chunk to the stream. */
    fun append(chunk: String) {
        if (chunk.isEmpty()) return

        val held = heldHighSurrogate
        val trailingHigh = chunk.last().isHighSurrogate()

        if (held != null) {
            heldHighSurrogate = null
            heldSurrogateJoins++
            // Join the held unit with the new chunk. The prepended prefix is a
            // single UTF-16 unit, never the (potentially large) old stream.
            segments.add(held.toString() + chunk)
        } else {
            segments.add(chunk)
        }

        limit += 1 + chunk.length - if (trailingHigh) 1 else 0

        if (trailingHigh) {
            heldHighSurrogate = chunk.last()
        }

        invalidateCache()
    }

    /** Character at the given absolute position, using UTF-16 code units. */
    fun charAt(position: Int): Char {
        require(position in base until limit) { "position $position outside of [$base,$limit)" }

        var segIdx = cacheSegmentIndex
        var segBase = cacheSegmentBase
        if (position < segBase) {
            segIdx = 0
            segBase = base
        }
        while (segIdx < segments.size) {
            val segment = segments[segIdx]
            if (position < segBase + segment.length) {
                cacheSegmentIndex = segIdx
                cacheSegmentBase = segBase
                return segment[position - segBase]
            }
            segBase += segment.length
            segIdx++
        }
        error("unreachable: position $position in [$base,$limit)")
    }

    /**
     * Decode the absolute, half-open span `[start, end)`. Allocates a single
     * substring when the span lives in one segment; joins segments into one
     * [String] otherwise.
     */
    fun slice(start: Int, end: Int): String {
        require(start in base..limit && end in start..limit) {
            "slice [$start,$end) outside of [$base,$limit)"
        }
        if (start == end) return ""

        var segIdx = 0
        var segBase = base
        while (segIdx < segments.size - 1 && start >= segBase + segments[segIdx].length) {
            segBase += segments[segIdx].length
            segIdx++
        }

        val first = segments[segIdx]
        val localStart = start - segBase
        if (end <= segBase + first.length) {
            return first.substring(localStart, end - segBase)
        }

        crossSegmentSlices++
        val builder = StringBuilder(end - start)
        builder.append(first, localStart, first.length)
        var segEnd = segBase + first.length
        segIdx++
        while (true) {
            val segment = segments[segIdx]
            if (end <= segEnd + segment.length) {
                builder.append(segment, 0, end - segEnd)
                break
            }
            builder.append(segment)
            segEnd += segment.length
            segIdx++
        }
        return builder.toString()
    }

    /** Advance the reading position by one UTF-16 unit. */
    fun advance() {
        index++
    }

    fun setIndex(value: Int) {
        index = value
    }

    fun markEntity(position: Int) {
        entityStart = position
        hasEntityMark = true
    }

    fun clearEntityMark() {
        hasEntityMark = false
    }

    /**
     * Drop every prefix that no unfinished token can reference anymore. The
     * state machine never re-reads consumed input; only the active section, an
     * in-flight entity and the current position pin the retained window.
     */
    fun releasePrefix() {
        var retainFrom = index
        if (sectionStart in base until index) retainFrom = minOf(retainFrom, sectionStart)
        if (hasEntityMark && entityStart in base until index) {
            retainFrom = minOf(retainFrom, entityStart)
        }
        if (retainFrom <= base) return

        var dropUntil = base
        while (segments.size > 1 && dropUntil + segments.first().length <= retainFrom) {
            dropUntil += segments.removeFirst().length
        }
        if (dropUntil > base) {
            base = dropUntil
            invalidateCache()
        }
    }

    /**
     * Make a trailing high surrogate readable. Used at EOF, where a still-held
     * unit is known to be isolated rather than the lead of a pair.
     */
    fun flushHeldSurrogate() {
        if (heldHighSurrogate != null) {
            heldHighSurrogate = null
            limit += 1
            invalidateCache()
        }
    }

    fun reset() {
        segments.clear()
        base = 0
        limit = 0
        heldHighSurrogate = null
        index = 0
        sectionStart = 0
        entityStart = 0
        hasEntityMark = false
        crossSegmentSlices = 0
        heldSurrogateJoins = 0
        invalidateCache()
    }

    private fun invalidateCache() {
        cacheSegmentIndex = 0
        cacheSegmentBase = base
    }
}
