package app.contactscleaner.core

/** Something the scan found that the user can choose to fix. */
sealed interface Issue {
    /** Stable id, so the UI can keep track of what the user selected. */
    val id: String

    /** A number not in canonical international form, e.g. 050-1234567 -> +972501234567. */
    data class PhoneFormat(
        val contact: Contact,
        val phone: PhoneEntry,
        val suggested: String,
    ) : Issue {
        override val id get() = "format:${phone.dataId}"
    }

    /**
     * A 7-digit Israeli landline saved without its area code (5801234).
     * [guess] is the likeliest code: one the same contact already uses, else
     * the most common one in the address book. The user picks the final one.
     */
    data class MissingAreaCode(
        val contact: Contact,
        val phone: PhoneEntry,
        val guess: String?,
    ) : Issue {
        override val id get() = "area:${phone.dataId}"

        companion object {
            val AREA_CODES = listOf("02", "03", "04", "08", "09")
        }
    }

    /** A number whose label contradicts it, like a mobile saved as "home". */
    data class PhoneLabel(
        val contact: Contact,
        val phone: PhoneEntry,
        val line: LineType,
        val suggestedType: Int,
    ) : Issue {
        override val id get() = "label:${phone.dataId}"
    }

    /**
     * A name carrying a tag that many other names share ("ס005 מרים כהן",
     * "Tz +972…"). [suggested] is the name without it; empty when nothing
     * but a number is left.
     */
    data class NameTag(
        val contact: Contact,
        val name: NameEntry,
        val tags: List<String>,
        val suggested: String,
    ) : Issue {
        override val id get() = "tag:${name.dataId}"
    }

    /**
     * A name that doesn't look like a person's. [suggested] is a replacement
     * when there's an obvious one (emoji removed, or the name from ME).
     */
    data class SuspiciousName(
        val contact: Contact,
        val name: NameEntry,
        val problem: NameProblem,
        val suggested: String?,
    ) : Issue {
        override val id get() = "suspicious:${name.dataId}"
    }

    /** A number that is most likely out of service; the only fix is deleting it. */
    data class OutdatedPhone(
        val contact: Contact,
        val phone: PhoneEntry,
        val reason: OutdatedReason,
    ) : Issue {
        override val id get() = "outdated:${phone.dataId}"
    }

    /** A number that isn't valid anywhere. There is no fix but deleting it. */
    data class InvalidPhone(
        val contact: Contact,
        val phone: PhoneEntry,
    ) : Issue {
        override val id get() = "invalid:${phone.dataId}"
    }

    /** The same number or email saved more than once on one contact. */
    data class RepeatedEntries(
        val contact: Contact,
        val repeatedPhones: List<PhoneEntry>,
        val repeatedEmails: List<EmailEntry>,
    ) : Issue {
        override val id get() = "repeat:${contact.id}"
        val dataIdsToRemove get() = repeatedPhones.map { it.dataId } + repeatedEmails.map { it.dataId }
    }

    /** Extra spaces, "moshe cohen", "MOSHE COHEN" and so on. */
    data class NameCleanup(
        val contact: Contact,
        val name: NameEntry,
        val suggested: String,
    ) : Issue {
        override val id get() = "name:${name.dataId}"
    }

    /**
     * No name, or the "name" is just a number. [suggested] is a guess (e.g.
     * from the email address) when there is one; otherwise the user types it.
     */
    data class MissingName(
        val contact: Contact,
        val suggested: String?,
    ) : Issue {
        override val id get() = "noname:${contact.id}"
    }

    /** A contact with no phone number and no email. */
    data class EmptyContact(val contact: Contact) : Issue {
        override val id get() = "empty:${contact.id}"
    }

    /**
     * A contact with a phone number, with what the phone knows of how much it
     * is used. Every such contact gets one; [InactiveFilter] picks which to show.
     */
    data class Inactive(
        val contact: Contact,
        val onWhatsApp: Boolean,
        val lastCall: Long?,
    ) : Issue {
        override val id get() = "inactive:${contact.id}"
    }

    /** Several contacts that look like the same person. */
    data class Duplicates(
        val contacts: List<Contact>,
        val reasons: Set<DuplicateReason>,
        val merged: MergePreview,
        /**
         * A shared phone or email *and* names that don't contradict each other.
         * A shared name alone, or a family landline saved under two different
         * people, is left for the user to judge.
         */
        val confident: Boolean,
    ) : Issue {
        override val id get() = "dup:" + contacts.map { it.id }.sorted().joinToString(",")
    }
}

enum class DuplicateReason {
    SAME_PHONE,
    SAME_EMAIL,
    SAME_NAME,

    /** Sounds alike: other spelling, other language, a nickname or a typo. Always worth a look. */
    SIMILAR_NAME,
}

/** What a group of duplicates would look like after merging. */
data class MergePreview(
    val name: String?,
    val phones: List<String>,
    val emails: List<String>,
    val organization: String?,
)

data class AnalysisReport(
    val contactCount: Int,
    val issues: List<Issue>,
) {
    inline fun <reified T : Issue> ofType(): List<T> = issues.filterIsInstance<T>()
}
