package com.unciv.ui.components.fonts

import com.unciv.utils.Log

/**
 *  ## An engine to support languages with heavy diacritic usage through Gdx Scene2D
 *
 *  ### Concepts
 *  - This is not needed for diacritics where Unicode already defines the combined glyphs as individual codepoints
 *  - Gdx text rendering assumes one Char one Glyph (and left-to-right)
 *  - The underlying OS **does** have the capability to render glyphs created by combining diacritic joiners with other characters (if not, this fails with ugly output but hopefully no exceptions).
 *  - We'll deal with one glyph at a time arranges left to right, and expect a finite number of combination glyphs (all fit into the Unicode Private Use Area **together** with [FontRulesetIcons]).
 *  - We'll recognize these combos in the translated texts at translation loading time and map each combo into a fake alphabet, which fulfills the "one Char one Glyph" tenet.
 *  - Conversely, the loader will build a map of distinct combinations -codepoint sequences- that map into a single glyph and correlate each with their fake alphabet codepoint.
 *  - At render time, only the map of fake alphabet codepoints to their original codepoint sequences is needed.
 *        * Remember, NativeBitmapFontData renders and caches glyphs on demand
 *        * GlyphLayout (a Gdx class) needs a Glyph that's not yet cached, then:
 *        * If it's in the fake alphabet, we'll ask the OS to render the original codepoint sequence instead
 *        * Otherwise render the single Char as before
 *
 *  ### Usage
 *  - Call [reset] when translation loading starts over
 *  - Call [prepareTranslationData] once a translation file is read (their map of key (left of =) to translation (right of =) is in memory, pass that as argument)
 *  - Check [noDiacritics] - if true, the rest of that language load need not bother with diacritics
 *  - Call [remapDiacritics] on each translation and store the result instead of the original value
 *  - If you wish to save some memory, call [freeTranslationData] after all required languages are done
 *  - Later, [NativeBitmapFontData.createAndCacheGlyph] will use [getStringFor] to map the fake alphabet back to codepoint sequences
 *
 *  ### Notes
 *  - [FontRulesetIcons] initialize ***after*** Translation loading. If this ever changes, this might need some tweaking.
 */
object DiacriticSupport {
    /** Start at end of Unicode Private Use Area and go down from there: UShort is the preferred Char() constructor parameter! */
    private const val startingReplacementCodepoint: UShort = 63743u // 0xF8FF

    private object TranslationKeys {
        const val range = "diacritics_joinable_range"
        const val left = "left_joining_diacritics"
        const val right = "right_joining_diacritics"
        const val joiner = "left_and_right_joiners"
    }

    //region The following fields can persist over loading multiple languages
    private var nextFreeDiacriticReplacementCodepoint = startingReplacementCodepoint
    private val fakeAlphabet = mutableMapOf<Char, String>()
    private val inverseMap = mutableMapOf<String, Char>()
    //endregion

    //region These fields will only persist during one language load
    private var noDiacritics = true
    private val charClassMap = mutableMapOf<Char, CharClass>()
    private var defaultCharClass = CharClass.None

    private class LineData(capacity: Int) {
        val output = StringBuilder(capacity)
        val accumulator = StringBuilder(8)
        fun expectsJoin() = accumulator.isNotEmpty() && getCharClass(accumulator.last()).expectsRightJoin
        fun flush() {
            if (accumulator.length <= 1) output.append(accumulator)
            else output.append(getReplacementChar(accumulator.toString()))
            accumulator.clear()
        }
        fun accumulate(char: Char) {
            accumulator.append(char)
        }
        fun flushAccumulate(char: Char) {
            if (!expectsJoin()) flush()
            accumulator.append(char)
        }
        fun flushAppend(char: Char) {
            flush()
            output.append(char)
        }
        fun result(): String {
            flush()
            return output.toString()
        }
    }

    private enum class CharClass(val expectsRightJoin: Boolean = false) {
        None {
            override fun process(data: LineData, char: Char) = data.flushAppend(char)
        },
        Base {
            override fun process(data: LineData, char: Char) = data.flushAccumulate(char)
        },
        LeftJoiner {
            override fun process(data: LineData, char: Char) = data.accumulate(char)
        },
        RightJoiner(true) {
            override fun process(data: LineData, char: Char) = data.flushAccumulate(char)
        },
        LeftRightJoiner(true) {
            override fun process(data: LineData, char: Char) = data.accumulate(char)
        };
        abstract fun process(data: LineData, char: Char)
    }
    //endregion

    /** Prepares this for a complete start-over, expecting a language load calling [prepareTranslationData] next */
    fun reset() {
        fakeAlphabet.clear()
        freeTranslationData()
        nextFreeDiacriticReplacementCodepoint = startingReplacementCodepoint
        defaultCharClass = CharClass.None
    }

    /** This is the main engine for rendering text glyphs after the translation loader has filled up this `object`
     *  @param  char The real or "fake alphabet" char stored by [remapDiacritics] to render
     *  @return The one to many (probably 8 max) codepoint string to be rendered into a single glyph by native font services
     */
    fun getStringFor(char: Char) = fakeAlphabet[char] ?: char.toString()

    /** Call when use of [remapDiacritics] is finished to save some memory */
    fun freeTranslationData() {
        for ((length, examples) in inverseMap.keys.groupBy { it.length }.toSortedMap()) {
            Log.debug("Length %d - example %s", length, examples.first())
        }
        inverseMap.clear()
        charClassMap.clear()
        noDiacritics = true
    }

    /** Other "fake" alphabets can use Unicode Private Use Areas from U+E000 up to including... */
    fun getNextFreeCode() = Char(nextFreeDiacriticReplacementCodepoint)

    /** If this is true, no need to bother [remapping chars at render time][getStringFor] */
    fun isEmpty() = fakeAlphabet.isEmpty()

    //region Methods used during translation file loading

    /** Set at [prepareTranslationData], if true the translation loader need not bother passing stuff through [remapDiacritics]. */
    fun noDiacritics() = noDiacritics

    private fun getCharClass(char: Char) = charClassMap[char] ?: defaultCharClass

    private fun getReplacementChar(joined: String) = inverseMap[joined] ?: createReplacementChar(joined)

    private fun createReplacementChar(joined: String): Char {
        val char = getNextFreeCode()
        nextFreeDiacriticReplacementCodepoint--
        fakeAlphabet[char] = joined
        inverseMap[joined] = char
        return char
    }

    /** Set up for a series of [remapDiacritics] calls for a specific language.
     *  Extracts the translation entries for [TranslationKeys] and sets up [CharClass] [mappings][charClassMap] using them. */
    fun prepareTranslationData(translations: HashMap<String, String>) {
        noDiacritics = true
        fun String?.sanitizeDiacriticEntry() = this?.replace(" ", "")?.removeSurrounding("\"") ?: ""
        val range = translations[TranslationKeys.range].sanitizeDiacriticEntry()
            .takeIf { it.length == 2 }
            ?.let { it.first()..it.last() }
            ?: CharRange.EMPTY
        val leftDiacritics = translations[TranslationKeys.left].sanitizeDiacriticEntry()
        val rightDiacritics = translations[TranslationKeys.right].sanitizeDiacriticEntry()
        val joinerDiacritics = translations[TranslationKeys.joiner].sanitizeDiacriticEntry()
        if (leftDiacritics.isNotEmpty() || rightDiacritics.isNotEmpty() || joinerDiacritics.isNotEmpty())
            prepareTranslationData(range, leftDiacritics, rightDiacritics, joinerDiacritics)
    }

    private fun prepareTranslationData(range: CharRange, leftDiacritics: String, rightDiacritics: String, joinerDiacritics: String) {
        charClassMap.clear()
        if (range.isEmpty()) {
            defaultCharClass = CharClass.Base
            charClassMap[' '] = CharClass.None
        } else {
            defaultCharClass = CharClass.None
            for (char in range) charClassMap[char] = CharClass.Base
        }
        for (char in leftDiacritics) charClassMap[char] = CharClass.LeftJoiner
        for (char in rightDiacritics) charClassMap[char] = CharClass.RightJoiner
        for (char in joinerDiacritics) charClassMap[char] = CharClass.LeftRightJoiner
        noDiacritics = false
    }

    /** Replaces the combos of diacritics/joiners with their affected characters with a "fake" alphabet */
    fun remapDiacritics(value: String): String {
        if (noDiacritics)
            throw IllegalStateException("DiacriticSupport not set up properly for translation processing")

        val data = LineData(value.length)
        for (char in value) {
            getCharClass(char).process(data, char)
        }
        return data.result()
    }

    //endregion
}
