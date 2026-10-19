package app.contactscleaner.core

/** Android's Phone.TYPE_* codes, which [PhoneEntry.type] carries. */
object PhoneTypes {
    const val CUSTOM = 0
    const val HOME = 1
    const val MOBILE = 2
    const val WORK = 3
    const val FAX_WORK = 4
    const val FAX_HOME = 5
    const val OTHER = 7

    /**
     * A better label for a number whose label contradicts it, or null if it's
     * fine. Only clear mistakes: a mobile saved as home or fax, a landline
     * saved as mobile. "Work" fits either, so it's never touched.
     */
    fun suggest(type: Int, line: LineType?): Int? = when {
        line == LineType.MOBILE && type in setOf(HOME, FAX_HOME, FAX_WORK) -> MOBILE
        line == LineType.LANDLINE && type == MOBILE -> HOME
        else -> null
    }
}
