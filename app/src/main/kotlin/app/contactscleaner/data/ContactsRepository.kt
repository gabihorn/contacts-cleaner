package app.contactscleaner.data

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import app.contactscleaner.core.Contact
import app.contactscleaner.core.EmailEntry
import app.contactscleaner.core.NameEntry
import app.contactscleaner.core.PhoneEntry

/** Reads the phone's address book into [Contact]s. */
class ContactsRepository(private val resolver: ContentResolver) {

    private class Builder(val id: Long) {
        var name: NameEntry? = null
        val phones = mutableListOf<PhoneEntry>()
        val emails = mutableListOf<EmailEntry>()
        var organization: String? = null
        val rawContactIds = linkedSetOf<Long>()

        fun build() = Contact(id, name, phones, emails, organization, rawContactIds.toList())
    }

    fun loadAll(): List<Contact> {
        val mimeTypes = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE,
        )
        // All four kinds keep their main value in DATA1, and type/label (or
        // given/family name) in DATA2/DATA3, so one projection reads them all.
        val projection = arrayOf(
            Data._ID,
            Data.CONTACT_ID,
            Data.RAW_CONTACT_ID,
            Data.MIMETYPE,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
        )
        val selection = "${Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})"

        val contacts = LinkedHashMap<Long, Builder>()
        resolver.query(Data.CONTENT_URI, projection, selection, mimeTypes, "${Data.CONTACT_ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val dataId = c.getLong(0)
                val contactId = c.getLong(1)
                val b = contacts.getOrPut(contactId) { Builder(contactId) }
                b.rawContactIds += c.getLong(2)
                val data1 = c.getString(4)
                when (c.getString(3)) {
                    StructuredName.CONTENT_ITEM_TYPE ->
                        // A contact joined from several accounts has a name row in each; keep the first real one.
                        if (b.name?.displayName.isNullOrBlank()) {
                            b.name = NameEntry(dataId, data1, givenName = c.getString(5), familyName = c.getString(6))
                        }
                    Phone.CONTENT_ITEM_TYPE ->
                        if (!data1.isNullOrBlank()) {
                            b.phones += PhoneEntry(dataId, data1, type = c.getInt(5), label = c.getString(6))
                        }
                    Email.CONTENT_ITEM_TYPE ->
                        if (!data1.isNullOrBlank()) b.emails += EmailEntry(dataId, data1)
                    Organization.CONTENT_ITEM_TYPE ->
                        if (b.organization.isNullOrBlank()) b.organization = data1
                }
            }
        }
        return contacts.values.map { it.build() }
    }
}
