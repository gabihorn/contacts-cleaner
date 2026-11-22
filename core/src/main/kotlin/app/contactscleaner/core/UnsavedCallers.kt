package app.contactscleaner.core

/** One call from the phone's call log. */
data class CallEntry(
    val number: String,
    val date: Long,
    /** True for calls that were answered or made and actually talked. */
    val talked: Boolean,
    /** The name the phone shows for the number, if it knows one (e.g. from caller ID). */
    val cachedName: String? = null,
)

/**
 * Numbers the user keeps talking to but never saved: a real conversation at
 * least [MIN_CALLS] times in the last [DAYS] days, with a number no contact
 * holds. Missed and unanswered calls don't count, so a pushy caller who is
 * never picked up stays off the list.
 */
object UnsavedCallers {
    const val MIN_CALLS = 4
    const val DAYS = 90L

    fun find(calls: List<CallEntry>, contacts: List<Contact>, normalizer: PhoneNormalizer, now: Long): List<Issue.FrequentUnsaved> {
        val saved = contacts.flatMap { c -> c.phones.map { normalizer.key(it.number) } }.toSet()
        val since = now - DAYS * 24 * 60 * 60 * 1000
        return calls.asSequence()
            .filter { it.talked && it.date >= since }
            .mapNotNull { call -> (normalizer.normalize(call.number) as? PhoneResult.Valid)?.let { it to call } }
            .filter { (valid, _) -> valid.key !in saved }
            .groupBy({ it.first.key }, { it })
            .values
            .filter { it.size >= MIN_CALLS }
            .map { group ->
                val latest = group.maxBy { it.second.date }
                Issue.FrequentUnsaved(
                    number = latest.first.formatted,
                    key = latest.first.key,
                    calls = group.size,
                    lastCall = latest.second.date,
                    knownName = group.firstNotNullOfOrNull { it.second.cachedName?.takeIf(String::isNotBlank) },
                )
            }
            .sortedByDescending { it.calls }
    }
}
