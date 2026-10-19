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
        // "הרב משה כהן" and "משה כהן" are the same person.
        val withoutTitles = spaces.split(normalizeTitles(spaces.replace(name.trim(), " ")))
            .filterNot { it in canonicalTitles }
            .joinToString(" ")
        val tokens = niqqud.replace(withoutTitles, "")
            .lowercase()
            .split(nonWord)
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val key = tokens.sorted().joinToString(" ")
        // A one- or two-letter name ("א", "Me") is too weak to call a duplicate.
        return key.takeIf { it.length >= 3 }
    }

    // Stray marks at the start (".משה", ",Moshe", "- דוד"). Brackets are kept: "(עבודה)" is meaningful.
    private val leadingJunk = Regex("^[\\s.,;:\\-_*~!?#/\\\\|+=`'\"׳״]+")

    // At the end, quotes are kept (ג', בע"מ) and a single dot after a letter is
    // kept too ("Jr."); only commas, dashes, spaced or repeated dots and the like go.
    private val trailingJunk = Regex("(?:[\\s,;\\-_*~#/\\\\|+=`]|\\.{2,}|(?<=\\s)\\.)+$")

    /**
     * A tidier spelling of [name], or null if it is already fine: removes
     * stray punctuation at the start and end, trims and collapses spaces,
     * spells titles one way, and fixes all-lowercase / ALL-CAPS Latin names.
     */
    fun cleanup(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val stripped = trailingJunk.replace(leadingJunk.replace(name, ""), "")
        // A "name" that is nothing but punctuation is left for the user to rename.
        if (stripped.none { it.isLetterOrDigit() }) return null
        var fixed = spaces.replace(stripped.trim(), " ")
        val letters = fixed.filter { it.isLetter() }
        val latinOnly = letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (latinOnly && letters.length > 1 && (fixed == fixed.lowercase() || fixed == fixed.uppercase())) {
            fixed = fixed.split(' ').joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
        }
        fixed = normalizeTitles(fixed)
        return fixed.takeIf { it != name }
    }

    /**
     * Each title's spellings (compared after [titleKey]) and the one form they
     * all become. Hebrew and English stay in their own language.
     */
    private val leadingTitles: Map<String, String> = buildMap {
        fun title(canonical: String, vararg variants: String) = variants.forEach { put(titleKey(it), canonical) }
        title("הרב", "הרב", "הר'", "הר", "רב")
        title("Rabbi", "rabbi", "rav", "rab")
        title("ר'", "ר'", "ר")
        title("ד\"ר", "ד\"ר", "ד'ר", "דר'", "דר", "דוקטור")
        title("Dr.", "dr", "doctor")
        title("פרופ'", "פרופ'", "פרופ", "פרופסור")
        title("Prof.", "prof", "professor")
        title("גב'", "גב'", "גב", "גברת")
        title("עו\"ד", "עו\"ד", "עו'ד", "עוה\"ד", "עורך דין", "עורכת דין")
        title("רו\"ח", "רו\"ח", "רו'ח", "רואה חשבון", "רואת חשבון")
    }

    /** Professional titles that also come after the name ("משה כהן עו\"ד"). */
    private val trailingTitles = setOf("עו\"ד", "רו\"ח")

    private val canonicalTitles = leadingTitles.values.toSet()

    /** Hebrew geresh/gershayim and curly quotes as plain ' and ", no trailing dot, lowercase. */
    private fun titleKey(token: String) = token
        .replace('״', '"').replace('”', '"').replace('“', '"')
        .replace('׳', '\'').replace('’', '\'').replace('‘', '\'')
        .trimEnd('.')
        .lowercase()

    /**
     * Spells titles one way: "ר.", "ר׳" and "ר" become "ר'", "דוקטור" and
     * "דר'" become "ד\"ר", "עורך דין" becomes "עו\"ד" and so on. Only up to two
     * titles at the start (and a professional one at the end) are touched, and
     * never the only word, so a person named "רב" keeps their name.
     */
    fun normalizeTitles(name: String): String {
        val tokens = name.split(' ').toMutableList()

        fun canonicalAt(i: Int, words: Int, allowed: Collection<String>? = null): String? {
            if (i < 0 || i + words > tokens.size) return null
            val canonical = leadingTitles[titleKey(tokens.subList(i, i + words).joinToString(" "))] ?: return null
            return canonical.takeIf { allowed == null || it in allowed }
        }
        fun replace(i: Int, words: Int, canonical: String) {
            repeat(words - 1) { tokens.removeAt(i + 1) }
            tokens[i] = canonical
        }

        var i = 0
        while (i < 2) {
            // Leave at least one word that isn't a title.
            val two = canonicalAt(i, 2)?.takeIf { tokens.size - i > 2 }
            val one = canonicalAt(i, 1)?.takeIf { tokens.size - i > 1 }
            when {
                two != null -> replace(i, 2, two)
                one != null -> replace(i, 1, one)
                else -> break
            }
            i++
        }
        if (tokens.size - i >= 3) canonicalAt(tokens.size - 2, 2, trailingTitles)?.let { replace(tokens.size - 2, 2, it) }
        if (tokens.size - i >= 2) canonicalAt(tokens.size - 1, 1, trailingTitles)?.let { replace(tokens.size - 1, 1, it) }
        return tokens.joinToString(" ")
    }

    /** Removes emoji and pictographs ("משה ❤️" -> "משה"); letters, digits and punctuation stay. */
    fun stripEmoji(name: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (!isEmoji(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return spaces.replace(out.toString(), " ").trim()
    }

    fun hasEmoji(name: String): Boolean = name.codePoints().anyMatch(::isEmoji)

    private fun isEmoji(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt()

    /** Words that describe a role or a place, not a person ("שליח", "אינסטלטור", "מהעבודה"). */
    private val genericWords = setOf(
        "שליח", "שליחויות", "משלוחים", "אינסטלטור", "חשמלאי", "מונית", "מוניות", "נהג", "טכנאי", "מנעולן",
        "גז", "מוסך", "הובלות", "מוביל", "שיפוצניק", "שיפוצים", "צבעי", "גנן", "ספק", "מוכר", "מוכרת",
        "מתווך", "תיווך", "דירה", "שכן", "שכנה", "ההוא", "ההיא", "מישהו", "מישהי", "לא", "לענות", "ספאם",
        "פרסומת", "חסום", "חדש", "חדשה", "מומלץ", "מומלצת", "טוב", "של", "את", "עם", "מספר", "טלפון",
        "שרברב", "מזגנים", "מזגן", "הסעות", "קייטרינג", "צלם", "דיג'יי", "אולם", "בנק", "קופה", "שירות", "לקוחות",
        "spam", "delivery", "taxi", "plumber", "electrician", "unknown", "new", "number", "dont", "answer",
    )

    /**
     * Why [name] doesn't look like a person's name, or null if it does.
     * Only whole-name patterns count: "משה חשמלאי" is fine, "חשמלאי מומלץ" isn't.
     */
    fun suspiciousReason(name: String?): NameProblem? {
        if (name.isNullOrBlank() || looksLikePhoneOrEmail(name)) return null
        val letters = name.count { it.isLetter() }
        if (letters == 0) return if (hasEmoji(name)) NameProblem.ONLY_EMOJI else null
        if (letters == 1) return NameProblem.SINGLE_LETTER
        val words = name.lowercase().split(nonWord).filter { it.isNotEmpty() }
        val generic = words.all { w ->
            w in genericWords || w.all(Char::isDigit) ||
                // "מהאירוע", "מהעבודה", "שלהגן": where you met them, not who they are.
                (w.length > 3 && (w.startsWith("מה") || w.startsWith("של")))
        }
        if (words.isNotEmpty() && generic) return NameProblem.GENERIC
        if (hasEmoji(name)) return NameProblem.EMOJI
        return null
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
