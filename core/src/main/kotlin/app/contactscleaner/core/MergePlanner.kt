package app.contactscleaner.core

/**
 * How to fold several raw contacts into one: [survivor] keeps living, gets
 * [rowsToCopy] added and its name set to [newName] (if not null), and
 * [toDelete] are removed.
 */
data class MergePlan(
    val survivor: RawContact,
    val rowsToCopy: List<DataRow>,
    val newName: String?,
    val toDelete: List<RawContact>,
)

class MergePlanner(private val normalizer: PhoneNormalizer = PhoneNormalizer()) {

    /**
     * Picks the copy that will survive (a Google account one if any, so the
     * merge syncs to Google Contacts), then copies over everything the others
     * have that it lacks. Names are not copied; [preferredName] replaces the
     * survivor's name when it differs.
     */
    fun plan(raws: List<RawContact>, preferredName: String?): MergePlan {
        require(raws.size >= 2) { "Need at least two contacts to merge" }
        val survivor = raws.sortedWith(
            compareByDescending<RawContact> { it.accountType == MimeTypes.GOOGLE_ACCOUNT }
                .thenByDescending { it.rows.size }
                .thenBy { it.id }
        ).first()
        val others = raws - survivor

        val seen = survivor.rows.mapNotNull { dedupKey(it) }.toMutableSet()
        var hasPhoto = survivor.rows.any { it.mimeType == MimeTypes.PHOTO && it.blob != null }
        val toCopy = mutableListOf<DataRow>()

        for (row in others.flatMap { raw -> raw.rows.map { raw to it } }) {
            val (raw, data) = row
            when {
                data.mimeType !in MimeTypes.STANDARD || data.mimeType == MimeTypes.NAME -> continue
                data.mimeType == MimeTypes.PHOTO -> {
                    if (hasPhoto || data.blob == null) continue
                    hasPhoto = true
                    toCopy += data
                }
                // Group ids belong to one account; they mean nothing in another.
                data.mimeType == MimeTypes.GROUP && !sameAccount(raw, survivor) -> continue
                else -> {
                    val key = dedupKey(data) ?: continue
                    if (seen.add(key)) toCopy += data
                }
            }
        }

        val currentName = survivor.rows.firstOrNull { it.mimeType == MimeTypes.NAME }?.data1
        val newName = preferredName?.takeIf { it.isNotBlank() && it != currentName }
        return MergePlan(survivor, toCopy, newName, others)
    }

    private fun sameAccount(a: RawContact, b: RawContact) =
        a.accountType == b.accountType && a.accountName == b.accountName && a.dataSet == b.dataSet

    /** Two rows with the same key are the same information. Null means nothing worth keeping. */
    private fun dedupKey(row: DataRow): String? {
        val value = row.data1?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = when (row.mimeType) {
            MimeTypes.PHONE -> normalizer.key(value)
            else -> value.lowercase()
        }
        return "${row.mimeType}|$normalized"
    }
}
