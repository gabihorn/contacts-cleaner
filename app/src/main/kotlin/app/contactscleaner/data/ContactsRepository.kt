package app.contactscleaner.data

import android.content.ContentResolver
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import app.contactscleaner.core.Contact
import app.contactscleaner.core.DataRow
import app.contactscleaner.core.EmailEntry
import app.contactscleaner.core.MimeTypes
import app.contactscleaner.core.NameEntry
import app.contactscleaner.core.PhoneEntry
import app.contactscleaner.core.RawContact

/** Reads the phone's address book into [Contact]s. */
class ContactsRepository(private val resolver: ContentResolver) {

    /**
     * Account types whose contacts belong to another app (WhatsApp, Telegram,
     * Signal …): their sync adapter can't upload, so edits would be lost or
     * overwritten. Everything from those accounts is skipped.
     */
    private val readOnlyAccountTypes: Set<String> by lazy {
        val adapters = ContentResolver.getSyncAdapterTypes().filter { it.authority == ContactsContract.AUTHORITY }
        val writable = adapters.filter { it.supportsUploading() }.map { it.accountType }.toSet()
        adapters.map { it.accountType }.toSet() - writable
    }

    private fun editable(accountType: String?) = accountType == null || accountType !in readOnlyAccountTypes

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
            RawContacts.ACCOUNT_TYPE,
        )
        val selection = "${Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})"

        val contacts = LinkedHashMap<Long, Builder>()
        resolver.query(Data.CONTENT_URI, projection, selection, mimeTypes, "${Data.CONTACT_ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (!editable(c.getString(7))) continue
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

    /**
     * Full copies of the given raw contacts: account and every standard data
     * row, photo included. Raw contacts that no longer exist are left out.
     */
    fun loadRaw(ids: Collection<Long>): List<RawContact> {
        if (ids.isEmpty()) return emptyList()
        val idList = ids.distinct().joinToString(",")

        val raws = LinkedHashMap<Long, RawContact>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.DATA_SET),
            "${RawContacts._ID} IN ($idList) AND ${RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                raws[c.getLong(0)] = RawContact(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))
            }
        }

        val rows = HashMap<Long, MutableList<DataRow>>()
        val dataColumns = (1..14).map { "data$it" }
        val mimeTypes = MimeTypes.STANDARD.toTypedArray()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA15) + dataColumns,
            "${Data.RAW_CONTACT_ID} IN ($idList) AND ${Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})",
            mimeTypes,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2)
                val values = dataColumns.withIndex().associate { (i, col) -> col to c.getString(4 + i) }
                val blob = if (mime == MimeTypes.PHOTO) c.getBlob(3) else null
                rows.getOrPut(c.getLong(1)) { mutableListOf() } += DataRow(c.getLong(0), mime, values, blob)
            }
        }
        return raws.values.map { it.copy(rows = rows[it.id].orEmpty()) }
    }
}
