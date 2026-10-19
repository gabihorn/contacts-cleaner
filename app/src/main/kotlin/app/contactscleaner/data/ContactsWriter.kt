package app.contactscleaner.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import app.contactscleaner.core.Contact
import app.contactscleaner.core.Issue
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.PhoneResult

/** Writes the fixes the user approved back to the address book. */
class ContactsWriter(
    private val resolver: ContentResolver,
    private val normalizer: PhoneNormalizer,
) {
    /**
     * Applies [issues]. [names] holds the name the user typed for each
     * [Issue.MissingName], keyed by issue id. Returns how many issues were fixed.
     */
    fun apply(issues: List<Issue>, names: Map<String, String>): Int {
        val ops = ArrayList<ContentProviderOperation>()
        var fixed = 0

        for (issue in issues) {
            val before = ops.size
            when (issue) {
                is Issue.PhoneFormat -> ops += updatePhone(issue.phone.dataId, issue.suggested)
                is Issue.RepeatedEntries -> issue.dataIdsToRemove.forEach { ops += deleteData(it) }
                is Issue.NameCleanup -> ops += updateName(issue.name.dataId, issue.suggested)
                is Issue.MissingName -> {
                    val name = names[issue.id]?.trim().orEmpty()
                    if (name.isNotEmpty()) ops += setName(issue.contact, name)
                }
                is Issue.EmptyContact -> ops += ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(Contacts.CONTENT_URI, issue.contact.id))
                    .build()
                is Issue.Duplicates -> ops += join(issue.contacts)
                is Issue.InvalidPhone -> Unit // Nothing to apply; shown for review only.
            }
            if (ops.size > before) fixed++
        }

        // The provider caps a batch at a few hundred operations.
        ops.chunked(300).forEach { resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
        return fixed
    }

    private fun updatePhone(dataId: Long, number: String): ContentProviderOperation {
        val e164 = (normalizer.normalize(number) as? PhoneResult.Valid)?.key
        return ContentProviderOperation.newUpdate(dataUri(dataId))
            .withValue(Phone.NUMBER, number)
            .withValue(Phone.NORMALIZED_NUMBER, e164)
            .build()
    }

    /**
     * Sets the display name and clears the parts, so the provider splits the
     * new name into given/family itself rather than keeping stale ones.
     */
    private fun updateName(dataId: Long, displayName: String): ContentProviderOperation =
        ContentProviderOperation.newUpdate(dataUri(dataId))
            .withValue(StructuredName.DISPLAY_NAME, displayName)
            .withValue(StructuredName.GIVEN_NAME, null)
            .withValue(StructuredName.MIDDLE_NAME, null)
            .withValue(StructuredName.FAMILY_NAME, null)
            .withValue(StructuredName.PREFIX, null)
            .withValue(StructuredName.SUFFIX, null)
            .build()

    private fun setName(contact: Contact, displayName: String): ContentProviderOperation {
        contact.name?.let { return updateName(it.dataId, displayName) }
        return ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, contact.rawContactIds.first())
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, displayName)
            .build()
    }

    private fun deleteData(dataId: Long) = ContentProviderOperation.newDelete(dataUri(dataId)).build()

    /**
     * Joins the contacts the way the Contacts app's "merge" does: every raw
     * contact is told to stay together with every other. Nothing is deleted,
     * and the user can split them again from the Contacts app.
     */
    private fun join(contacts: List<Contact>): List<ContentProviderOperation> {
        val raw = contacts.flatMap { it.rawContactIds }.distinct()
        val ops = mutableListOf<ContentProviderOperation>()
        for (i in raw.indices) {
            for (j in i + 1 until raw.size) {
                ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                    .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(AggregationExceptions.RAW_CONTACT_ID1, raw[i])
                    .withValue(AggregationExceptions.RAW_CONTACT_ID2, raw[j])
                    .build()
            }
        }
        return ops
    }

    private fun dataUri(dataId: Long) = ContentUris.withAppendedId(Data.CONTENT_URI, dataId)
}
