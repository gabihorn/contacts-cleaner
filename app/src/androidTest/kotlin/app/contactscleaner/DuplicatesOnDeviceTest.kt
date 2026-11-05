package app.contactscleaner

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.contactscleaner.core.ContactAnalyzer
import app.contactscleaner.core.Issue
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.data.ContactsRepository
import app.contactscleaner.data.ContactsWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real merge against Android's contacts database on an emulator:
 * two saved copies of one person go in, the app finds and merges them, and
 * the result is read back from the phone. Then undo is checked too.
 */
@RunWith(AndroidJUnit4::class)
class DuplicatesOnDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val normalizer = PhoneNormalizer("IL")
    private val repository = ContactsRepository(resolver)
    private val writer = ContactsWriter(resolver, normalizer)
    private val created = mutableListOf<Long>()

    /** Saves a contact the way the Contacts app would, in no account (phone only). */
    private fun insert(name: String, phone: String, email: String? = null): Long {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
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
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build(),
        )
        if (email != null) {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email)
                .build()
        }
        val id = ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!)
        created += id
        return id
    }

    /** Stops Android from joining the copies by itself, so the app has a duplicate to find. */
    private fun keepSeparate(a: Long, b: Long) {
        resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                    .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_SEPARATE)
                    .withValue(AggregationExceptions.RAW_CONTACT_ID1, a)
                    .withValue(AggregationExceptions.RAW_CONTACT_ID2, b)
                    .build()
            ),
        )
    }

    private fun contactsWithNumber(e164: String) =
        repository.loadAll().filter { c -> c.phones.any { normalizer.key(it.number) == e164 } }

    @After
    fun cleanUp() {
        // Raw contacts made here, and any the undo recreated with the test number.
        val ids = created + contactsWithNumber(NUMBER).flatMap { it.rawContactIds }
        ids.distinct().forEach { id ->
            resolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                null, null,
            )
        }
    }

    @Test
    fun findsMergesAndUndoesDuplicates() {
        // One person saved twice: different spelling, different number format, email on one copy only.
        val a = insert("Moshe Cohen", "052-8123456")
        val b = insert("משה כהן", "+972 52-812-3456", email = "moshe@example.com")
        keepSeparate(a, b)
        assertEquals("starts as two contacts", 2, contactsWithNumber(NUMBER).size)

        // 1. The scan finds them as one duplicate group.
        val report = ContactAnalyzer(normalizer).analyze(repository.loadAll())
        val group = report.ofType<Issue.Duplicates>().single { dup ->
            dup.contacts.any { a in it.rawContactIds } && dup.contacts.any { b in it.rawContactIds }
        }
        assertEquals(2, group.contacts.size)

        // 2. Merging, with the name the user picked.
        val run = writer.apply("בדיקה", listOf(group), mapOf(group.id to "משה כהן"))
        assertTrue("the merge wrote something", run != null)

        // 3. The phone now holds one contact, with the chosen name and both copies' details.
        val merged = contactsWithNumber(NUMBER)
        assertEquals("merged into one contact", 1, merged.size)
        assertEquals("משה כהן", merged.single().displayName)
        assertTrue("kept the email", merged.single().emails.any { it.address == "moshe@example.com" })
        assertEquals("one copy of the number", 1, merged.single().phones.map { normalizer.key(it.number) }.distinct().size)

        // 4. A fresh scan finds no duplicate left for this person.
        val again = ContactAnalyzer(normalizer).analyze(repository.loadAll()).ofType<Issue.Duplicates>()
        assertTrue("no duplicate left", again.none { dup -> dup.contacts.any { c -> c.phones.any { normalizer.key(it.number) == NUMBER } } })

        // 5. Undo brings back both separate contacts.
        writer.undo(run!!)
        assertEquals("undo restores two contacts", 2, contactsWithNumber(NUMBER).size)
    }

    @Test
    fun fixesNumberFormat() {
        insert("דוד לוי", "052-8765432")
        val issue = ContactAnalyzer(normalizer).analyze(repository.loadAll())
            .ofType<Issue.PhoneFormat>().single { it.phone.number == "052-8765432" }
        writer.apply("בדיקה", listOf(issue), emptyMap())
        val numbers = repository.loadAll().flatMap { c -> c.phones.map { it.number } }
        assertTrue("number is now international", "+972528765432" in numbers)
        // Clean up this one too.
        created += repository.loadAll().filter { c -> c.phones.any { it.number == "+972528765432" } }.flatMap { it.rawContactIds }
    }

    private companion object {
        const val NUMBER = "+972528123456"
    }
}
