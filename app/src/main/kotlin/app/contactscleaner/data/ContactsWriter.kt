package app.contactscleaner.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import app.contactscleaner.core.Contact
import app.contactscleaner.core.DataRow
import app.contactscleaner.core.Issue
import app.contactscleaner.core.MergePlanner
import app.contactscleaner.core.MimeTypes
import app.contactscleaner.core.PhoneEntry
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.PhoneResult
import app.contactscleaner.core.RawContact

/** What to do with the chosen issues. */
enum class Action {
    FIX,

    /** Delete the phone number each issue is about, instead of fixing it. */
    DELETE_NUMBERS,

    /** Delete the contact each issue is about. */
    DELETE_CONTACTS,
}

/** Writes the fixes the user approved back to the address book, and undoes them. */
class ContactsWriter(
    private val resolver: ContentResolver,
    private val normalizer: PhoneNormalizer,
    private val repository: ContactsRepository = ContactsRepository(resolver),
) {
    /**
     * Applies [issues] and returns the run to record in the change log, or
     * null if there was nothing to do. [inputs] holds what the user typed or
     * picked, keyed by issue id: the name for [Issue.MissingName], the area
     * code for [Issue.MissingAreaCode].
     */
    fun apply(title: String, issues: List<Issue>, inputs: Map<String, String>, action: Action = Action.FIX): ChangeRun? {
        // Snapshot everything we may touch before touching anything.
        val before = repository.loadRaw(issues.flatMap { touchedContacts(it) }.flatMap { it.rawContactIds })
        val rawById = before.associateBy { it.id }

        val ops = ArrayList<ContentProviderOperation>()
        val lines = mutableListOf<String>()

        for (issue in issues) {
            val opsBefore = ops.size
            var line: String? = null
            if (action == Action.DELETE_CONTACTS) {
                val contact = issue.contactOrNull() ?: continue
                contact.rawContactIds.forEach { ops += deleteRaw(it) }
                lines += "נמחק: ${contact.title}"
                continue
            }
            if (action == Action.DELETE_NUMBERS) {
                val phone = phoneOf(issue) ?: continue
                ops += deleteData(phone.dataId)
                lines += "${issue.contactOrNull()?.title}: נמחק ${ltr(phone.number)}"
                continue
            }
            when (issue) {
                is Issue.PhoneFormat -> {
                    ops += updatePhone(issue.phone.dataId, issue.suggested)
                    line = "${issue.contact.title}: ${ltr(issue.phone.number)} ← ${ltr(issue.suggested)}"
                }
                is Issue.MissingAreaCode -> {
                    val area = inputs[issue.id] ?: issue.guess
                    val fixed = area?.let { normalizer.withAreaCode(issue.phone.number, it) }
                    if (fixed != null) {
                        ops += updatePhone(issue.phone.dataId, fixed)
                        line = "${issue.contact.title}: ${ltr(issue.phone.number)} ← ${ltr(fixed)}"
                    }
                }
                is Issue.RepeatedEntries -> {
                    issue.dataIdsToRemove.forEach { ops += deleteData(it) }
                    val removed = issue.repeatedPhones.map { it.number } + issue.repeatedEmails.map { it.address }
                    line = "${issue.contact.title}: נמחקו עותקים של ${removed.joinToString(", ") { ltr(it) }}"
                }
                is Issue.NameCleanup -> {
                    ops += updateName(issue.name.dataId, issue.suggested)
                    line = "\"${issue.name.displayName}\" ← \"${issue.suggested}\""
                }
                is Issue.MissingName -> {
                    val name = (inputs[issue.id] ?: issue.suggested)?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        ops += setName(issue.contact, name)
                        line = "${issue.contact.title} ← \"$name\""
                    }
                }
                is Issue.EmptyContact -> {
                    issue.contact.rawContactIds.forEach { ops += deleteRaw(it) }
                    line = "נמחק: ${issue.contact.title}"
                }
                is Issue.Duplicates -> {
                    val raws = issue.contacts.flatMap { it.rawContactIds }.mapNotNull { rawById[it] }
                    if (raws.size >= 2) {
                        val name = inputs[issue.id]?.trim()?.takeIf { it.isNotEmpty() } ?: issue.merged.name
                        ops += merge(raws, name)
                        line = "אוחדו: ${issue.contacts.joinToString(" + ") { it.title }}" + (name?.let { " ← \"$it\"" } ?: "")
                    }
                }
                // Nothing to fix; these can only be deleted (Action.DELETE_NUMBERS).
                is Issue.OutdatedPhone, is Issue.InvalidPhone -> Unit
                // Nothing to fix either; only Action.DELETE_CONTACTS applies.
                is Issue.Inactive -> Unit
            }
            if (ops.size > opsBefore && line != null) lines += line
        }
        if (ops.isEmpty()) return null

        applyInBatches(ops)
        val now = System.currentTimeMillis()
        return ChangeRun(id = now, time = now, title = title, lines = lines, before = before)
    }

    /**
     * Puts every raw contact in [run] back the way it was: ones that still
     * exist get their data rows replaced, deleted ones are recreated in the
     * same account. Changes made to those contacts since the run are lost.
     */
    fun undo(run: ChangeRun) {
        val stillThere = repository.loadRaw(run.before.map { it.id }).map { it.id }.toSet()
        val restoredIds = mutableMapOf<Long, Long>() // new raw id -> original contact id

        for (raw in run.before) {
            val ops = ArrayList<ContentProviderOperation>()
            if (raw.id in stillThere) {
                val mimeTypes = MimeTypes.STANDARD.toTypedArray()
                ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(
                        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})",
                        arrayOf(raw.id.toString()) + mimeTypes,
                    )
                    .build()
                raw.rows.forEach { ops += insertRow(it, rawContactId = raw.id) }
                applyInBatches(ops)
                restoredIds[raw.id] = raw.contactId
            } else {
                ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, raw.accountType)
                    .withValue(RawContacts.ACCOUNT_NAME, raw.accountName)
                    .withValue(RawContacts.DATA_SET, raw.dataSet)
                    .build()
                raw.rows.forEach { ops += insertRow(it, rawContactId = null) }
                val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                val newId = results.firstOrNull()?.uri?.let(ContentUris::parseId) ?: continue
                restoredIds[newId] = raw.contactId
            }
        }

        // They were separate contacts before; stop Android from re-joining them
        // just because they share a number again.
        val ids = restoredIds.keys.toList()
        val separate = ArrayList<ContentProviderOperation>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            if (restoredIds[ids[i]] != restoredIds[ids[j]]) separate += aggregation(ids[i], ids[j], AggregationExceptions.TYPE_KEEP_SEPARATE)
        }
        applyInBatches(separate)
    }

    private fun touchedContacts(issue: Issue): List<Contact> = when (issue) {
        is Issue.Duplicates -> issue.contacts
        is Issue.PhoneFormat -> listOf(issue.contact)
        is Issue.MissingAreaCode -> listOf(issue.contact)
        is Issue.RepeatedEntries -> listOf(issue.contact)
        is Issue.NameCleanup -> listOf(issue.contact)
        is Issue.MissingName -> listOf(issue.contact)
        is Issue.EmptyContact -> listOf(issue.contact)
        is Issue.Inactive -> listOf(issue.contact)
        is Issue.OutdatedPhone -> listOf(issue.contact)
        is Issue.InvalidPhone -> listOf(issue.contact)
    }

    private fun Issue.contactOrNull(): Contact? = touchedContacts(this).singleOrNull()

    /** The phone number an issue is about, for issues that are about one. */
    private fun phoneOf(issue: Issue): PhoneEntry? = when (issue) {
        is Issue.PhoneFormat -> issue.phone
        is Issue.MissingAreaCode -> issue.phone
        is Issue.OutdatedPhone -> issue.phone
        is Issue.InvalidPhone -> issue.phone
        else -> null
    }

    /**
     * Folds the raw contacts into one: the survivor (a Google one when there
     * is one, so the merge syncs) gets everything it lacks, the rest are deleted.
     */
    private fun merge(raws: List<RawContact>, name: String?): List<ContentProviderOperation> {
        val plan = MergePlanner(normalizer).plan(raws, name)
        val ops = mutableListOf<ContentProviderOperation>()
        plan.rowsToCopy.forEach { ops += insertRow(it, plan.survivor.id) }
        plan.newName?.let { newName ->
            val nameRow = plan.survivor.rows.firstOrNull { it.mimeType == MimeTypes.NAME }
            ops += if (nameRow != null) updateName(nameRow.id, newName) else insertName(plan.survivor.id, newName)
        }
        plan.toDelete.forEach { ops += deleteRaw(it.id) }
        return ops
    }

    /** Inserts a copy of [row]; with a null [rawContactId] it attaches to the raw contact inserted first in the batch. */
    private fun insertRow(row: DataRow, rawContactId: Long?): ContentProviderOperation {
        val values = ContentValues()
        row.values.forEach { (column, value) -> values.put(column, value) }
        values.put(Data.MIMETYPE, row.mimeType)
        row.blob?.let { values.put(Data.DATA15, it) }
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(values)
        if (rawContactId != null) builder.withValue(Data.RAW_CONTACT_ID, rawContactId)
        else builder.withValueBackReference(Data.RAW_CONTACT_ID, 0)
        return builder.build()
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

    private fun insertName(rawContactId: Long, displayName: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, displayName)
            .build()

    private fun setName(contact: Contact, displayName: String): ContentProviderOperation =
        contact.name?.let { updateName(it.dataId, displayName) }
            ?: insertName(contact.rawContactIds.first(), displayName)

    private fun deleteData(dataId: Long) = ContentProviderOperation.newDelete(dataUri(dataId)).build()

    /** For synced accounts this marks the contact deleted and the deletion syncs to Google. */
    private fun deleteRaw(rawContactId: Long) =
        ContentProviderOperation.newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId)).build()

    private fun aggregation(raw1: Long, raw2: Long, type: Int) =
        ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
            .withValue(AggregationExceptions.TYPE, type)
            .withValue(AggregationExceptions.RAW_CONTACT_ID1, raw1)
            .withValue(AggregationExceptions.RAW_CONTACT_ID2, raw2)
            .build()

    /** The provider caps a batch at a few hundred operations. */
    private fun applyInBatches(ops: List<ContentProviderOperation>) {
        ops.chunked(300).forEach { resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
    }

    private fun dataUri(dataId: Long) = ContentUris.withAppendedId(Data.CONTENT_URI, dataId)

    /** Keeps numbers left-to-right inside Hebrew text. */
    private fun ltr(text: String) = "⁦$text⁩"
}
