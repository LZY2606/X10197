package com.mohamedrejeb.ksoup.html.tokenizer

import com.mohamedrejeb.ksoup.entities.KsoupEntities
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlOptions
import com.mohamedrejeb.ksoup.html.parser.KsoupHtmlParser

/**
 * KsoupTokenizer is an HTML Tokenizer which is able to receive HTML string,
 * breaks it up into individual tokens, and return those tokens with the [KsoupTokenizerCallbacks]
 *
 * @param options KsoupHtmlOptions
 *
 */
internal class KsoupTokenizer(
    options: KsoupHtmlOptions,
    private val callbacks: KsoupTokenizerCallbacks
) {
    private val xmlMode = options.xmlMode
    private val decodeEntities = options.decodeEntities

    /**
     * Single owner of absolute positions, pending token marks and retained
     * input across multiple [write] calls.
     */
    private val cursor = TokenizerCursor()

    /** The current state the tokenizer is in. */
    private var state = State.Text
    /** The start of the last entity, mirrored as a cursor mark. */
    private var entityStart: Int
        get() = cursor.entityStart
        set(value) = cursor.markEntity(value)
    /**
     * Some behavior, e.g., When decoding entities, is done while we are in another state.
     * This keeps track of the other state type.
     */
    private var baseState = State.Text
    /** For special parsing behavior inside script and style tags. */
    private var isSpecial = false
    /** Indicates whether the tokenizer has been paused. */
    public var running: Boolean = true
        private set
    /** Indicates whether the tokenizer has been finished via [end]. */
    private var ended = false
    /** end() was requested while paused; finish after [resume] catches up. */
    private var endPending = false

    private val index: Int
        get() = cursor.index

    @OptIn(ExperimentalUnsignedTypes::class)
    fun reset() {
        this.cursor.reset()
        this.state = State.Text
        this.baseState = State.Text
        this.currentSequence = null
        this.isSpecial = false
        this.running = true
        this.ended = false
        this.endPending = false
    }

    fun write(chunk: String) {
        this.cursor.append(chunk)
        if (this.running) this.parse()
    }

    fun end() {
        if (this.ended) return
        this.ended = true
        if (this.running) this.finish() else this.endPending = true
    }

    fun pause() {
        this.running = false
    }

    fun resume() {
        this.running = true
        if (this.cursor.index < this.cursor.endOffset) {
            this.parse()
        }
        if (this.ended) this.finish()
    }

    /** Absolute position of the state machine cursor (package-private metric). */
    internal fun debugCursorPosition(): Int = this.cursor.index

    /** Absolute span of input currently retained for unfinished tokens. */
    internal fun debugRetainedLength(): Int = this.cursor.retainedLength

    internal fun debugRetainedSegmentCount(): Int = this.cursor.retainedSegmentCount

    internal fun debugCrossSegmentSlices(): Int = this.cursor.crossSegmentSlices

    internal fun debugHeldSurrogateJoins(): Int = this.cursor.heldSurrogateJoins

    /** Decode an absolute span via the tokenizer's retained input. */
    internal fun slice(start: Int, end: Int): String = this.cursor.slice(start, end)

    private fun stateText(c: Int) {
        if (
            c == CharCodes.Lt.code ||
            (!this.decodeEntities && this.fastForwardTo(CharCodes.Lt.code))
        ) {
            if (this.index > this.cursor.sectionStart) {
                this.callbacks.onText(this.cursor.sectionStart, this.index)
            }
            this.state = State.BeforeTagName
            this.cursor.sectionStart = this.index
        } else if (this.decodeEntities && c == CharCodes.Amp.code) {
            this.startEntity()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private var currentSequence: UByteArray? = null
    private var sequenceIndex = 0
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateSpecialStartSequence(c: Int) {
        val currentSequence = this.currentSequence ?: return

        val isEnd = this.sequenceIndex == currentSequence.size
        val isMatch = if (isEnd) {
            // If we are at the end of the sequence, make sure the tag name has ended
            isEndOfTagSection(c)
        } else {
            // Otherwise, do a case-insensitive comparison
            (c or 0x20) == currentSequence[this.sequenceIndex].toInt()
        }

        if (!isMatch) {
            this.isSpecial = false
        } else if (!isEnd) {
            this.sequenceIndex++
            return
        }

        this.sequenceIndex = 0
        this.state = State.InTagName

        this.stateInTagName(c)
    }

    /** Look for an end tag. For <title> tags, also decode entities. */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateInSpecialTag(c: Int) {
        val currentSequence = this.currentSequence ?: return

        if (this.sequenceIndex == currentSequence.size) {
            if (c == CharCodes.Gt.code || isWhitespace(c)) {
                val endOfText = this.index - currentSequence.size

                if (this.cursor.sectionStart < endOfText) {
                    // Spoof the index so that reported locations match up.
                    val actualIndex = this.index
                    this.cursor.setIndex(endOfText)
                    this.callbacks.onText(this.cursor.sectionStart, endOfText)
                    this.cursor.setIndex(actualIndex)
                }

                this.isSpecial = false
                this.cursor.sectionStart = endOfText + 2 // Skip over the `</`
                this.stateInClosingTagName(c)
                return // We are done skip the rest of the function.
            }

            this.sequenceIndex = 0
        }

        if ((c or 0x20) == currentSequence[this.sequenceIndex].toInt()) {
            this.sequenceIndex += 1
        } else if (this.sequenceIndex == 0) {
            if (currentSequence == Sequences.TitleEnd) {
                // We have to parse entities in <title> tags.
                if (this.decodeEntities && c == CharCodes.Amp.code) {
                    this.startEntity()
                }
            } else if (this.fastForwardTo(CharCodes.Lt.code)) {
                // Outside <title> tags, we can fast-forward.
                this.sequenceIndex = 1
            }
        } else {
            // If we see a `<`, set the sequence index to 1 useful for eg. `<</script>`.
            this.sequenceIndex = if (c == CharCodes.Lt.code) 1 else 0
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateCDATASequence(c: Int) {
        if (c == Sequences.Cdata[sequenceIndex].toInt()) {
            if (++sequenceIndex == Sequences.Cdata.size) {
                this.state = State.InCommentLike
                this.currentSequence = Sequences.CdataEnd
                this.sequenceIndex = 0
                this.cursor.sectionStart = index + 1
            }
        } else {
            this.sequenceIndex = 0
            this.state = State.InDeclaration
            this.stateInDeclaration(c) // Re-Consume the character
        }
    }

    /**
     * When we wait for one specific character, we can speed things up
     * by skipping through the buffer until we it.
     *
     * @returns Whether the character was found.
     */
    private fun fastForwardTo(c: Int): Boolean {
        while (this.cursor.index < this.readableLimit()) {
            if (this.cursor.charAt(this.cursor.index).code == c) {
                return true
            }
            this.cursor.advance()
        }

        /*
         * We increment the index at the end of the `parse` loop,
         * so set it to `readableLimit() - 1` here.
         *
         * TODO: Refactor `parse` to increment index before calling states.
         */
        this.cursor.setIndex(this.readableLimit() - 1)

        return false
    }

    /**
     * Absolute offset one past the input readable by the state machine. A
     * trailing high surrogate that might lead a surrogate pair is excluded
     * until the next write (or [finish] proves the unit is isolated).
     */
    private fun readableLimit(): Int = this.cursor.readableLimit

    /**
     * Comments and CDATA end with `-->` and `]]>`.
     *
     * Their common qualities are:
     * - Their end sequences have a distinct character they start with.
     * - That character is then repeated, so we have to check multiple repeats.
     * - All characters but the start character of the sequence can be skipped.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateInCommentLike(c: Int) {
        val currentSequence = this.currentSequence ?: return

        if (c == currentSequence[sequenceIndex].toInt()) {
            if (++sequenceIndex == currentSequence.size) {
                if (currentSequence == Sequences.CdataEnd) {
                    callbacks.onCData(cursor.sectionStart, index, 2)
                } else {
                    callbacks.onComment(cursor.sectionStart, index, 2)
                }

                sequenceIndex = 0
                cursor.sectionStart = index + 1
                state = State.Text
            }
        } else if (sequenceIndex == 0) {
            // Fast-forward to the first character of the sequence
            if (fastForwardTo(currentSequence[0].toInt())) {
                sequenceIndex = 1
            }
        } else if (c != currentSequence[sequenceIndex - 1].toInt()) {
            // Allow long sequences, e.g., --->, ]]>
            sequenceIndex = 0
        }
    }

    /**
     * HTML only allows ASCII alpha characters (a-z and A-Z) at the beginning of a tag name.
     *
     * XML allows a lot more characters here (@see https://www.w3.org/TR/REC-xml/#NT-NameStartChar).
     * We allow anything that wouldn't end the tag.
     */
    private fun isTagStartChar(c: Int): Boolean {
        return if (xmlMode) !isEndOfTagSection(c) else isASCIIAlpha(c)
    }

    /**
     * Check if `c` is a valid character of an HTML Entity.
     */
    private fun isInEntityChar(c: Int): Boolean {
        return isASCIIAlpha(c) || isDigit(c) || c == CharCodes.Semi.code
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun startSpecial(sequence: UByteArray, offset: Int) {
        this.isSpecial = true
        this.currentSequence = sequence
        this.sequenceIndex = offset
        this.state = State.SpecialStartSequence
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateBeforeTagName(c: Int) {
        if (c == CharCodes.ExclamationMark.code) {
            state = State.BeforeDeclaration
            cursor.sectionStart = index + 1
        } else if (c == CharCodes.QuestionMark.code) {
            state = State.InProcessingInstruction
            cursor.sectionStart = index + 1
        } else if (isTagStartChar(c)) {
            // Lowercase the character
            val lower = c or 0x20
            cursor.sectionStart = index

            if (xmlMode) {
                state = State.InTagName
            } else if (lower == Sequences.ScriptEnd[2].toInt()) {
                this.state = State.BeforeSpecialS
            } else if (lower == Sequences.TitleEnd[2].toInt()) {
                this.state = State.BeforeSpecialT
            } else {
                this.state = State.InTagName
            }
        } else if (c == CharCodes.Slash.code) {
            state = State.BeforeClosingTagName
        } else {
            state = State.Text
            stateText(c)
        }
    }

    private fun stateInTagName(c: Int) {
        if (isEndOfTagSection(c)) {
            callbacks.onOpenTagName(cursor.sectionStart, index)
            cursor.sectionStart = -1
            state = State.BeforeAttributeName
            stateBeforeAttributeName(c)
        }
    }

    private fun stateBeforeClosingTagName(c: Int) {
        if (isWhitespace(c)) {
            // Ignore
        } else if (c == CharCodes.Gt.code) {
            state = State.Text
        } else {
            state = if (isTagStartChar(c)) {
                State.InClosingTagName
            } else {
                State.InSpecialComment
            }
            cursor.sectionStart = index
        }
    }

    private fun stateInClosingTagName(c: Int) {
        if (c == CharCodes.Gt.code || isWhitespace(c)) {
            callbacks.onCloseTag(cursor.sectionStart, index)
            cursor.sectionStart = -1
            state = State.AfterClosingTagName
            this.stateAfterClosingTagName(c)
        }
    }

    private fun stateAfterClosingTagName(c: Int) {
        // Skip everything until ">"
        if (c == CharCodes.Gt.code || this.fastForwardTo(CharCodes.Gt.code)) {
            this.state = State.Text
            this.cursor.sectionStart = this.index + 1
        }
    }

    private fun stateBeforeAttributeName(c: Int) {
        if (c == CharCodes.Gt.code) {
            this.callbacks.onOpenTagEnd(this.index)
            if (this.isSpecial) {
                this.state = State.InSpecialTag
                this.sequenceIndex = 0
            } else {
                this.state = State.Text
            }
            this.cursor.sectionStart = this.index + 1
        } else if (c == CharCodes.Slash.code) {
            this.state = State.InSelfClosingTag
        } else if (!isWhitespace(c)) {
            this.state = State.InAttributeName
            this.cursor.sectionStart = this.index
        }
    }

    private fun stateInSelfClosingTag(c: Int) {
        if (c == CharCodes.Gt.code) {
            this.callbacks.onSelfClosingTag(this.index)
            this.state = State.Text
            this.cursor.sectionStart = this.index + 1
            this.isSpecial = false // Reset special state, in case of self-closing special tags
        } else if (!isWhitespace(c)) {
            this.state = State.BeforeAttributeName
            this.stateBeforeAttributeName(c)
        }
    }

    private fun stateInAttributeName(c: Int) {
        if (c == CharCodes.Eq.code || isEndOfTagSection(c)) {
            this.callbacks.onAttribName(this.cursor.sectionStart, this.index)
            this.cursor.sectionStart = this.index
            this.state = State.AfterAttributeName
            this.stateAfterAttributeName(c)
        }
    }

    private fun stateAfterAttributeName(c: Int) {
        if (c == CharCodes.Eq.code) {
            this.state = State.BeforeAttributeValue
        } else if (c == CharCodes.Slash.code || c == CharCodes.Gt.code) {
            this.callbacks.onAttribEnd(KsoupHtmlParser.QuoteType.NoValue, this.cursor.sectionStart)
            this.cursor.sectionStart = -1
            this.state = State.BeforeAttributeName
            this.stateBeforeAttributeName(c)
        } else if (!isWhitespace(c)) {
            this.callbacks.onAttribEnd(KsoupHtmlParser.QuoteType.NoValue, this.cursor.sectionStart)
            this.state = State.InAttributeName
            this.cursor.sectionStart = this.index
        }
    }

    private fun stateBeforeAttributeValue(c: Int) {
        if (c == CharCodes.DoubleQuote.code) {
            this.state = State.InAttributeValueDq
            this.cursor.sectionStart = this.index + 1
        } else if (c == CharCodes.SingleQuote.code) {
            this.state = State.InAttributeValueSq
            this.cursor.sectionStart = this.index + 1
        } else if (!isWhitespace(c)) {
            this.cursor.sectionStart = this.index
            this.state = State.InAttributeValueNq
            this.stateInAttributeValueNoQuotes(c) // Re-Consume token
        }
    }

    private fun handleInAttributeValue(c: Int, quote: Int) {
        if (
            c == quote ||
            (!this.decodeEntities && this.fastForwardTo(quote))
        ) {
            this.callbacks.onAttribData(this.cursor.sectionStart, this.index)
            this.cursor.sectionStart = -1
            this.callbacks.onAttribEnd(
                if (quote == CharCodes.DoubleQuote.code)
                    KsoupHtmlParser.QuoteType.Double
                else
                    KsoupHtmlParser.QuoteType.Single,
                this.index + 1
            )
            this.state = State.BeforeAttributeName
        } else if (this.decodeEntities && c == CharCodes.Amp.code) {
            this.startEntity()
        }
    }
    private fun stateInAttributeValueDoubleQuotes(c: Int) {
        this.handleInAttributeValue(c, CharCodes.DoubleQuote.code)
    }
    private fun stateInAttributeValueSingleQuotes(c: Int) {
        this.handleInAttributeValue(c, CharCodes.SingleQuote.code)
    }
    private fun stateInAttributeValueNoQuotes(c: Int) {
        if (isWhitespace(c) || c == CharCodes.Gt.code) {
            this.callbacks.onAttribData(this.cursor.sectionStart, this.index)
            this.cursor.sectionStart = -1
            this.callbacks.onAttribEnd(KsoupHtmlParser.QuoteType.Unquoted, this.index)
            this.state = State.BeforeAttributeName
            this.stateBeforeAttributeName(c)
        } else if (this.decodeEntities && c == CharCodes.Amp.code) {
            this.startEntity()
        }
    }

    private fun stateBeforeDeclaration(c: Int) {
        if (c == CharCodes.OpeningSquareBracket.code) {
            this.state = State.CDATASequence
            this.sequenceIndex = 0
        } else {
            this.state = if (c == CharCodes.Dash.code)
                State.BeforeComment
            else
                State.InDeclaration
        }
    }

    private fun stateInDeclaration(c: Int) {
        if (c == CharCodes.Gt.code || this.fastForwardTo(CharCodes.Gt.code)) {
            this.callbacks.onDeclaration(this.cursor.sectionStart, this.index)
            this.state = State.Text
            this.cursor.sectionStart = this.index + 1
        }
    }

    private fun stateInProcessingInstruction(c: Int) {
        if (c == CharCodes.Gt.code || this.fastForwardTo(CharCodes.Gt.code)) {
            this.callbacks.onProcessingInstruction(this.cursor.sectionStart, this.index)
            this.state = State.Text
            this.cursor.sectionStart = this.index + 1
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateBeforeComment(c: Int) {
        if (c == CharCodes.Dash.code) {
            this.state = State.InCommentLike
            this.currentSequence = Sequences.CommentEnd
            // Allow short comments (eg. <!-->)
            this.sequenceIndex = 2
            this.cursor.sectionStart = this.index + 1
        } else {
            this.state = State.InDeclaration
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateInSpecialComment(c: Int) {
        currentSequence?.let { currentSequence ->
            if (c == CharCodes.Gt.code) {
                if (sequenceIndex == currentSequence.size - 1) {
                    callbacks.onComment(cursor.sectionStart, index - currentSequence.size + 1, 3)
                    cursor.sectionStart = -1
                    state = State.Text
                }
            } else if (c != currentSequence[sequenceIndex].toInt()) {
                state = State.InTagName
                stateInTagName(c) // Re-Consume the character
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateBeforeSpecialS(c: Int) {
        when (c or 0x20) {
            Sequences.ScriptEnd[3].toInt() -> {
                this.startSpecial(Sequences.ScriptEnd, 4)
            }
            Sequences.StyleEnd[3].toInt() -> {
                this.startSpecial(Sequences.StyleEnd, 4)
            }
            else -> {
                this.state = State.InTagName
                this.stateInTagName(c) // Consume the token again
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun stateBeforeSpecialT(c: Int) {
        when (c or 0x20) {
            Sequences.TitleEnd[3].toInt() -> {
                this.startSpecial(Sequences.TitleEnd, 4)
            }
            Sequences.TextareaEnd[3].toInt() -> {
                this.startSpecial(Sequences.TextareaEnd, 4)
            }
            else -> {
                this.state = State.InTagName
                this.stateInTagName(c) // Consume the token again
            }
        }
    }

    private fun startEntity() {
        this.baseState = this.state
        this.state = State.InEntity
        this.entityStart = this.index
    }

    private fun stateInEntity(c: Int) {
        if (c == CharCodes.Semi.code) {
            val decoded = KsoupEntities.decodeHtml(
                this.cursor.slice(this.entityStart, this.index + 1)
            )

            if (decoded.isEmpty()) {
                this.cursor.setIndex(this.entityStart)
            } else {
                this.cursor.clearEntityMark()
                this.state = this.baseState
                emitCodePoint(decoded.first().code, this.index + 1 - this.entityStart)
                return
            }
        }

        if (
            this.index + 1 - this.entityStart > LONGEST_HTML_ENTITY_LENGTH ||
            !isInEntityChar(c)
        ) {
            this.state = this.baseState
            this.cursor.clearEntityMark()
            this.cursor.setIndex(this.entityStart)
        }
    }
