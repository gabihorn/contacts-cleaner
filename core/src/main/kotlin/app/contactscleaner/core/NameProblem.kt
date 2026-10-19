package app.contactscleaner.core

enum class NameProblem {
    /** "א", "מ." */
    SINGLE_LETTER,

    /** "שליח גז", "אינסטלטור מומלץ", "ההוא מהאירוע". */
    GENERIC,

    /** A name with emoji in it; the fix removes them. */
    EMOJI,

    /** Nothing but emoji. */
    ONLY_EMOJI,
}
