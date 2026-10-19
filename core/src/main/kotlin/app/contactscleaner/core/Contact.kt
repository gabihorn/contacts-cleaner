package app.contactscleaner.core

/**
 * A contact as read from the phone, independent of Android types.
 *
 * [id] is the aggregated contact id; [rawContactIds] are the per-account raw
 * contacts it is made of. Every phone/email/name carries the id of the data row
 * it came from, so a fix can update exactly that row.
 */
data class Contact(
    val id: Long,
    val name: NameEntry?,
    val phones: List<PhoneEntry> = emptyList(),
    val emails: List<EmailEntry> = emptyList(),
    val organization: String? = null,
    val rawContactIds: List<Long> = emptyList(),
    /** WhatsApp keeps its own copy of every contact that is on WhatsApp; this says whether it has one. */
    val onWhatsApp: Boolean = false,
    /** Time (epoch ms) of the latest phone call with any of this contact's numbers; null if none in the call log. */
    val lastCall: Long? = null,
) {
    val displayName: String? get() = name?.displayName?.takeIf { it.isNotBlank() }

    /** Something to show in lists even when the contact has no name. */
    val title: String
        get() = displayName
            ?: phones.firstOrNull()?.number
            ?: emails.firstOrNull()?.address
            ?: "(ללא שם)"
}

data class NameEntry(
    val dataId: Long,
    val displayName: String?,
    val givenName: String? = null,
    val familyName: String? = null,
)

data class PhoneEntry(
    val dataId: Long,
    val number: String,
    /** Opaque type code (Android's Phone.TYPE_*), passed through untouched. */
    val type: Int = 0,
    val label: String? = null,
)

data class EmailEntry(
    val dataId: Long,
    val address: String,
)
