package app.contactscleaner.core

/**
 * Which country the address book's local numbers (no "+" or "00") belong
 * to. The SIM alone can mislead: a foreign SIM, no SIM, or a phone bought
 * abroad. The country under which most of those numbers are valid wins;
 * ties go to the earlier candidate.
 */
object RegionGuess {
    fun best(numbers: List<String>, candidates: List<String>): String {
        val local = numbers.map { it.trim() }
            .filter { n -> !n.startsWith("+") && !n.filter(Char::isDigit).startsWith("00") }
            .take(SAMPLE)
        val regions = candidates.distinct()
        if (local.isEmpty()) return regions.first()
        return regions.maxByOrNull { region ->
            val normalizer = PhoneNormalizer(region)
            local.count { normalizer.normalize(it) is PhoneResult.Valid }
        } ?: regions.first()
    }

    /** Enough numbers to tell, without slowing a big address book down. */
    private const val SAMPLE = 300
}
