package app.contactscleaner.core.me

import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.PhoneResult

/** A person who saved the user, merged across ME's groups and keyed by normalized number. */
data class MePerson(
    /** E.164, e.g. +972501234567. */
    val phone: String,
    val firstName: String?,
    val lastName: String?,
    val savedYouAs: List<String>,
    /** True only when ME said "not in contact list" and never said otherwise. */
    val unsavedInMe: Boolean,
) {
    val personName: String? get() = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifEmpty { null }

    /** "ME - Moshe Cohen", or "ME - +972…" when ME has no name. Never [savedYouAs]. */
    fun importName(prefix: String) = prefix + (personName ?: phone)
}

data class MeSummary(
    /** Everything ME returned, before merging. */
    val received: Int,
    val people: List<MePerson>,
    /** Entries with no usable number. */
    val rejected: Int,
    /** Saved the user, not saved back (per ME) and not in the phone's contacts either. */
    val toImport: List<MePerson>,
    /** ME thinks they aren't saved, but the phone has their number. */
    val alreadyInPhone: Int,
)

class MeImportPlanner(private val normalizer: PhoneNormalizer = PhoneNormalizer("IL")) {

    /** [myNumbers] are the E.164 keys of every number in the phone's contacts. */
    fun summarize(entries: List<MeEntry>, myNumbers: Set<String>): MeSummary {
        var rejected = 0
        val byKey = LinkedHashMap<String, MutableList<MeEntry>>()
        for (entry in entries) {
            val key = (normalizer.normalize("+" + entry.phone) as? PhoneResult.Valid)?.key
                ?: (normalizer.normalize(entry.phone) as? PhoneResult.Valid)?.key
            if (key == null) {
                rejected++
                continue
            }
            byKey.getOrPut(key) { mutableListOf() } += entry
        }

        val people = byKey.map { (key, same) ->
            MePerson(
                phone = key,
                firstName = same.firstNotNullOfOrNull { it.firstName },
                lastName = same.firstNotNullOfOrNull { it.lastName },
                savedYouAs = same.mapNotNull { it.savedYouAs }.distinct(),
                unsavedInMe = same.any { it.inContactList == false } && same.none { it.inContactList == true },
            )
        }
        val unsaved = people.filter { it.unsavedInMe }
        return MeSummary(
            received = entries.size,
            people = people,
            rejected = rejected,
            toImport = unsaved.filter { it.phone !in myNumbers },
            alreadyInPhone = unsaved.count { it.phone in myNumbers },
        )
    }
}
