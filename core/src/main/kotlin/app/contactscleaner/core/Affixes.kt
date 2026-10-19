package app.contactscleaner.core

/**
 * Tags that many names share at the start or end: codes like "ס005" or
 * "ס16", short Latin tags like "Tz", bare numbers. They usually come from
 * a bulk import or a status-sharing scheme, and they bury the real name.
 * Only tags found on several contacts count, so a single "Moshe 2" stays.
 */
object Affixes {
    // A code: letters then digits ("ס005", "AB12"), digits alone ("005"), or a short Latin tag ("Tz").
    private const val CODE = """(?:\p{L}{1,3}\d{1,5}|\d{1,5}|[A-Za-z]{1,3})"""
    private val leading = Regex("""^\s*($CODE)(?=[\s+~\-_.:,|/]|$)""")
    private val trailing = Regex("""(?<=[\s~\-_.:,|/])($CODE)\s*$""")

    /** Titles and words that look like tags but are part of real names. */
    private val notTags = setOf("dr", "mr", "ms", "mrs", "rav", "rab", "rbi", "jr", "sr", "ii", "iii", "iv", "bar", "ben", "bat", "bin", "de", "da", "di", "le", "la", "van", "von", "el", "al", "abu", "mc")

    /** The tags that appear on at least [minCount] names, as (tag, how many names). */
    fun find(names: List<String>, minCount: Int = 3): Map<String, Int> =
        names.flatMap(::candidates).groupingBy { it }.eachCount().filterValues { it >= minCount }

    /** [name] without any of [tags] at its start or end, tidied; empty if nothing is left. */
    fun strip(name: String, tags: Set<String>): String {
        var result = name
        leading.find(result)?.groupValues?.get(1)?.takeIf { it in tags }?.let {
            result = result.replaceFirst(it, "")
        }
        trailing.find(result)?.groupValues?.get(1)?.takeIf { it in tags }?.let {
            result = result.substring(0, result.lastIndexOf(it))
        }
        val stripped = result.trim()
        // A bare number left behind keeps its "+"; name tidying is for names.
        if (Names.looksLikePhoneOrEmail(stripped)) return stripped
        return Names.cleanup(stripped) ?: stripped
    }

    /** The tags of [tags] that [name] carries. */
    fun tagsIn(name: String, tags: Set<String>): List<String> = candidates(name).filter { it in tags }

    private fun candidates(name: String): List<String> = listOfNotNull(
        leading.find(name)?.groupValues?.get(1),
        trailing.find(name)?.groupValues?.get(1),
    ).filter { it.lowercase() !in notTags }.distinct()
}
