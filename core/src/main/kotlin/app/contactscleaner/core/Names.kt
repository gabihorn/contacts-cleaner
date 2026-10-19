package app.contactscleaner.core

object Names {
    private val niqqud = Regex("[\\u0591-\\u05C7]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    /**
     * Key for "same person?" comparisons: case, niqqud, punctuation and word
     * order are ignored, so "Cohen, Moshe" and "משה כהן" vs "כהן משה" match.
     */
    fun matchKey(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val tokens = niqqud.replace(name, "")
            .lowercase()
            .split(nonWord)
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val key = tokens.sorted().joinToString(" ")
        // A one- or two-letter name ("א", "Me") is too weak to call a duplicate.
        return key.takeIf { it.length >= 3 }
    }

    /**
     * A tidier spelling of [name], or null if it is already fine: trims and
     * collapses spaces, and fixes all-lowercase / ALL-CAPS Latin names.
     */
    fun cleanup(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var fixed = spaces.replace(name.trim(), " ")
        val letters = fixed.filter { it.isLetter() }
        val latinOnly = letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (latinOnly && letters.length > 1 && (fixed == fixed.lowercase() || fixed == fixed.uppercase())) {
            fixed = fixed.split(' ').joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
        }
        return fixed.takeIf { it != name }
    }

    /** True if the "name" is really just a phone number or an email address. */
    fun looksLikePhoneOrEmail(name: String): Boolean {
        val n = name.trim()
        if ('@' in n) return true
        val digits = n.count { it.isDigit() }
        return digits >= 5 && n.all { it.isDigit() || it in "+-() .*#" }
    }

    /** "moshe.cohen85@gmail.com" -> "Moshe Cohen". Null if nothing name-like is there. */
    fun fromEmail(address: String): String? {
        val local = address.substringBefore('@')
        val parts = local.split('.', '_', '-')
            .map { part -> part.filter { it.isLetter() } }
            .filter { it.length >= 2 }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.joinToString(" ") { p -> p.lowercase().replaceFirstChar { it.uppercaseChar() } }
    }
}
