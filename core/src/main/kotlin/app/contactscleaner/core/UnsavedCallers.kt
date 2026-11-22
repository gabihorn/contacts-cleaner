package app.contactscleaner.core

import java.time.Instant
import java.time.ZoneId

/** One call from the phone's call log. */
data class CallEntry(
    val number: String,
    val date: Long,
    /** True for calls that were answered or made and actually talked. */
    val talked: Boolean,
    /** The name the phone shows for the number, if it knows one (e.g. from caller ID). */
    val cachedName: String? = null,
    val incoming: Boolean = false,
    /** How long the call lasted. */
    val seconds: Long = 0,
)

/** A text message with a number, shown as a clue to who it is. */
data class TextMessage(val body: String, val date: Long, val incoming: Boolean)

/** When in the day calls with a number usually happen. */
enum class DayPart { MORNING, NOON, EVENING, NIGHT }

/**
 * Numbers the user keeps talking to but never saved: a real conversation at
 * least [MIN_CALLS] times in the last [DAYS] days, with a number no contact
 * holds. Missed and unanswered calls don't count, so a pushy caller who is
 * never picked up stays off the list.
 */
object UnsavedCallers {
    const val MIN_CALLS = 4
    const val DAYS = 90L

    fun find(
        calls: List<CallEntry>,
        contacts: List<Contact>,
        normalizer: PhoneNormalizer,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Issue.FrequentUnsaved> {
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
                val entries = group.map { it.second }
                Issue.FrequentUnsaved(
                    number = latest.first.formatted,
                    key = latest.first.key,
                    calls = group.size,
                    lastCall = latest.second.date,
                    knownName = group.firstNotNullOfOrNull { it.second.cachedName?.takeIf(String::isNotBlank) },
                    talkSeconds = entries.sumOf { it.seconds },
                    incoming = entries.count { it.incoming },
                    outgoing = entries.count { !it.incoming },
                    usualTime = usualTime(entries, zone),
                )
            }
            .sortedByDescending { it.calls }
    }

    /** The part of the day most calls fall in, if most of them (60%+) do. */
    fun usualTime(calls: List<CallEntry>, zone: ZoneId): DayPart? {
        if (calls.isEmpty()) return null
        val (part, count) = calls
            .groupingBy { partOf(Instant.ofEpochMilli(it.date).atZone(zone).hour) }
            .eachCount()
            .maxBy { it.value }
        return part.takeIf { count * 10 >= calls.size * 6 }
    }

    private fun partOf(hour: Int) = when (hour) {
        in 6..11 -> DayPart.MORNING
        in 12..16 -> DayPart.NOON
        in 17..22 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }
}
