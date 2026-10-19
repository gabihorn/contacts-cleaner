package app.contactscleaner.core

/**
 * A rough "sounds like" key for names, the same for Hebrew and Latin
 * spelling: "משה כהן", "Moshe Cohen" and "Moishe Kohen" all become "KN MS".
 * Vowels (and the letters Hebrew uses as vowels) are dropped and similar
 * consonants share a class. Common nicknames map to the full name first, so
 * "יוסי" and "יוסף" match.
 */
object Phonetic {
    private val niqqud = Regex("[\\u0591-\\u05C7]")

    /** Nickname -> full name. Matched per word, before the sound key is taken. */
    private val nicknames: Map<String, String> = buildMap {
        fun nick(full: String, vararg short: String) = short.forEach { put(it, full) }
        nick("יוסף", "יוסי", "יוסל'ה", "יוסלה", "yossi", "yosi", "joseph", "joe")
        nick("משה", "מוישי", "מויש", "מוישה", "moishe", "moishy", "moshe")
        nick("יצחק", "איצי", "איציק", "איצ'ה", "itzik", "itzi", "yitzchak", "isaac")
        nick("שרה", "שרי", "שרל'ה", "sarah", "sari")
        nick("דוד", "דודי", "dudi", "david")
        nick("שמואל", "שמוליק", "שמולי", "shmuli", "shmulik", "samuel")
        nick("יעקב", "יענקי", "יענקל", "קובי", "yanky", "yankel", "kobi", "jacob")
        nick("רבקה", "רבקי", "רבקל'ה", "rivky", "rivki")
        nick("חיים", "חיימי", "chaim", "haim", "chaimi")
        nick("מרדכי", "מוטי", "מוטל", "moti", "motti")
        nick("בנימין", "בני", "בנצי", "benny", "beni", "binyamin", "benjamin")
        nick("ציפורה", "ציפי", "tzipi", "tzippy")
        nick("רחל", "רחלי", "ruchi", "rachel")
        nick("מנחם", "מנדי", "mendy", "mendi", "menachem")
        nick("שלמה", "שלומי", "shlomi", "shlomo")
        nick("אליהו", "אלי", "eli", "eliyahu")
        nick("אברהם", "אברימי", "אברמי", "avrumi", "avrami", "avraham", "abraham")
        nick("ישראל", "שרוליק", "שרולי", "srully", "sruli", "yisrael")
        nick("נחמן", "נחמי", "nachmi")
        nick("מיכאל", "מיקי", "מיכי", "miki", "michael")
        nick("אהרן", "אהרון", "ארל'ה", "aharon", "aaron")
        nick("זאב", "וולוולי", "velvel", "zev", "zeev")
    }

    /**
     * Sound key of a whole name: per-word keys, sorted so word order doesn't
     * matter. Null if too thin to compare. With [loose], B-sounds are dropped
     * too, since Hebrew ו is sometimes a v and sometimes a vowel ("לוי" / "Levi").
     */
    fun key(name: String?, loose: Boolean = false): String? {
        if (name.isNullOrBlank()) return null
        val words = niqqud.replace(name, "")
            .lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.isNotBlank() }
        val keys = words.map { word -> wordKey(nicknames[word] ?: nicknames[word.trimEnd('\'')] ?: word) }
            .map { if (loose) it.replace("B", "") else it }
            .filter { it.isNotEmpty() }
        // A single word ("אבא", "Moshe") is too weak to call two contacts the same person.
        if (keys.size < 2) return null
        return keys.sorted().joinToString(" ")
    }

    /**
     * Keys one dropped consonant away from [key] ("BB KN" -> "B KN", "BB N"),
     * so "אבי כהן" and "אביב כהן" meet. Only for keys long enough not to
     * collide by accident.
     */
    fun nearKeys(key: String): List<String> {
        if (key.count { it != ' ' } < 4) return emptyList()
        return key.indices
            .filter { key[it] != ' ' }
            .map { key.removeRange(it, it + 1) }
            .filterNot { it.startsWith(' ') || it.endsWith(' ') || "  " in it }
            .map { it.split(' ').sorted().joinToString(" ") }
            .distinct()
    }

    private fun wordKey(word: String): String {
        val out = StringBuilder()
        var previous: Char? = null
        var i = 0
        while (i < word.length) {
            val two = if (i + 1 < word.length) word.substring(i, i + 2) else ""
            val (cls, len) = when (two) {
                "ch", "kh" -> 'K' to 2
                "sh" -> 'S' to 2
                "ph" -> 'P' to 2
                "th" -> 'T' to 2
                "tz", "ts" -> 'C' to 2
                else -> single(word[i]) to 1
            }
            // Doubled letters ("tt", "mm") are one sound; the same letter after a vowel is a new one.
            if (cls != null && cls != previous) out.append(cls)
            previous = cls
            i += len
        }
        return out.toString()
    }

    /** Consonant class of one letter, or null for vowels and silent letters. */
    private fun single(c: Char): Char? = when (c) {
        'ב', 'b', 'v', 'w' -> 'B'
        'ג', 'g', 'j' -> 'G'
        'ד', 'd' -> 'D'
        'ז', 'z' -> 'Z'
        'ח', 'כ', 'ך', 'ק', 'k', 'q', 'c', 'x' -> 'K'
        'ט', 'ת', 't' -> 'T'
        'ל', 'l' -> 'L'
        'מ', 'ם', 'm' -> 'M'
        'נ', 'ן', 'n' -> 'N'
        'ס', 'ש', 's' -> 'S'
        'צ', 'ץ' -> 'C'
        'פ', 'ף', 'p', 'f' -> 'P'
        'ר', 'r' -> 'R'
        else -> null // a e i o u y h, א ה ו י ע, and anything else
    }
}
