package app.contactscleaner.me

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.MimeTypes
import app.contactscleaner.data.ChangeLog
import app.contactscleaner.data.ChangeRun
import app.contactscleaner.data.ContactsRepository
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Adds the next batch from the ME queue as new contacts. Numbers that
 * reached the phone some other way since they were queued are skipped.
 * Each batch is a change-log run, so it can be undone.
 */
class MeImporter(
    private val resolver: ContentResolver,
    private val store: MeStore,
    private val changeLog: ChangeLog,
) {
    private val normalizer = PhoneNormalizer("IL")

    /** Adds up to [count] contacts (default: the batch size); returns how many were added. */
    fun importNext(count: Int? = null): Int {
        val data = store.load() ?: return 0
        val take = count ?: data.settings.batchSize
        if (data.queue.isEmpty() || take <= 0) return 0

        val existing = ContactsRepository(resolver).loadAll()
            .flatMap { c -> c.phones.map { normalizer.key(it.number) } }
            .toSet()
        val people = data.people.associateBy { it.phone }
        val batch = data.queue.take(take)
        val (account, accountType) = googleAccount()

        val created = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        for (phone in batch) {
            val person = people[phone] ?: continue
            if (phone in existing) continue
            val name = person.importName(data.settings.prefix)
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                    .withValue(RawContacts.ACCOUNT_NAME, account)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.NORMALIZED_NUMBER, phone)
                    // Not every number is a mobile: landlines are saved as "other".
                    .withValue(Phone.TYPE, if (isMobile(phone)) Phone.TYPE_MOBILE else Phone.TYPE_OTHER)
                    .build(),
            )
            if (data.settings.addNote && person.savedYouAs.isNotEmpty()) {
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, "שמר/ה אותי בשם: " + person.savedYouAs.joinToString(", "))
                    .build()
            }
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            results.firstOrNull()?.uri?.let { created += ContentUris.parseId(it) }
            lines += "נוסף: $name (⁦$phone⁩)"
        }

        val now = System.currentTimeMillis()
        if (created.isNotEmpty()) {
            changeLog.add(ChangeRun(id = now, time = now, title = "ייבוא מ-ME", lines = lines, before = emptyList(), created = created))
        }
        store.update { it.copy(queue = it.queue.drop(batch.size), lastBatchAt = now, imported = it.imported + created.size) }
        return created.size
    }

    /** The Google account most contacts are in, so new ones sync like the rest; null means phone-only. */
    private fun googleAccount(): Pair<String?, String?> {
        val counts = HashMap<String, Int>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_NAME),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(MimeTypes.GOOGLE_ACCOUNT),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { counts.merge(it, 1, Int::plus) } }
        val account = counts.maxByOrNull { it.value }?.key ?: return null to null
        return account to MimeTypes.GOOGLE_ACCOUNT
    }

    private fun isMobile(e164: String): Boolean = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val type = util.getNumberType(util.parse(e164, null))
        type == PhoneNumberUtil.PhoneNumberType.MOBILE || type == PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE
    }.getOrDefault(true)
}
