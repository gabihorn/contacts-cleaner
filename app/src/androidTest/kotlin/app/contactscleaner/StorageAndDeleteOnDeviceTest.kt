package app.contactscleaner

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.contactscleaner.core.ContactAnalyzer
import app.contactscleaner.core.DataRow
import app.contactscleaner.core.Issue
import app.contactscleaner.core.MimeTypes
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.RawContact
import app.contactscleaner.data.Action
import app.contactscleaner.data.ChangeLog
import app.contactscleaner.data.ChangeRun
import app.contactscleaner.data.ContactsRepository
import app.contactscleaner.data.ContactsWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageAndDeleteOnDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val normalizer = PhoneNormalizer("IL")
    private val created = mutableListOf<Long>()

    private fun insert(name: String, vararg phones: String): Long {
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
        )
        phones.forEach { phone ->
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone)
                .build()
        }
        return ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!).also { created += it }
    }

    @After
    fun cleanUp() {
        created.forEach { id ->
            resolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                null, null,
            )
        }
    }

    /** What crashed for a user: deleting many invalid numbers, then the app reading its change log. */
    @Test
    fun deletesInvalidNumbersInBulk() {
        val ids = (1..60).map { i -> insert("בדיקה $i", "+972528${100000 + i}", "12345${i % 10}9") }
        val repository = ContactsRepository(resolver)
        val invalid = ContactAnalyzer(normalizer).analyze(repository.loadAll())
            .ofType<Issue.InvalidPhone>()
            .filter { issue -> issue.contact.rawContactIds.any { it in ids } }
        assertEquals(60, invalid.size)

        val run = ContactsWriter(resolver, normalizer).apply("מחיקה", invalid, emptyMap(), Action.DELETE_NUMBERS)!!
        val log = ChangeLog(context)
        log.add(run)

        val after = repository.loadAll().filter { c -> c.rawContactIds.any { it in ids } }
        assertEquals("every contact kept", 60, after.size)
        assertTrue("invalid numbers gone, good ones kept", after.all { c -> c.phones.size == 1 && c.phones[0].number.startsWith("+972528") })

        // Undo through the stored snapshot puts the numbers back.
        ContactsWriter(resolver, normalizer).undo(log.withSnapshot(log.all().first { it.id == run.id }))
        assertTrue(repository.loadAll().filter { c -> c.rawContactIds.any { it in ids } }.all { it.phones.size == 2 })
    }

    /** One person kept in two accounts shows as one contact; the number in both copies is not a repeat. */
    @Test
    fun sameNumberInTwoAccountsIsNotARepeat() {
        fun insertIn(account: String): Long {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, "app.contactscleaner.test")
                    .withValue(RawContacts.ACCOUNT_NAME, account)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, "רבקה גולדשטיין")
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, "+972533456789")
                    .build(),
            )
            return ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!).also { created += it }
        }
        val a = insertIn("first")
        val b = insertIn("second")
        // Android joins them into one contact, as it does for one person synced from two accounts.
        resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, a)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, b)
                    .build()
            ),
        )
        val contact = ContactsRepository(resolver).loadAll().single { a in it.rawContactIds }
        assertEquals("one contact made of both copies", setOf(a, b), contact.rawContactIds.toSet())
        assertEquals(2, contact.phones.size)
        val repeats = ContactAnalyzer(normalizer).analyze(listOf(contact)).ofType<Issue.RepeatedEntries>()
        assertTrue("not a repeat", repeats.isEmpty())
    }

    /** A log full of big snapshots (photos) loads its index without loading them. */
    @Test
    fun bigChangeLogStaysLight() {
        File(context.filesDir, "changelog").deleteRecursively()
        val log = ChangeLog(context)
        val photo = ByteArray(300_000) { it.toByte() }
        repeat(40) { i ->
            val raws = (1..5).map { r ->
                RawContact(r.toLong(), r.toLong(), null, null, rows = listOf(DataRow(r.toLong(), MimeTypes.PHOTO, emptyMap(), photo)))
            }
            log.add(ChangeRun(id = i.toLong() + 1, time = i.toLong(), title = "ריצה $i", lines = listOf("שורה"), before = raws))
        }
        val runs = log.all()
        assertEquals("keeps all 40 (the limit is 200)", 40, runs.size)
        assertTrue("the list carries no snapshots", runs.all { it.before.isEmpty() })
        val newest = log.withSnapshot(runs.first())
        assertEquals(5, newest.before.size)
        assertEquals(300_000, newest.before.first().rows.first().blob!!.size)
        File(context.filesDir, "changelog").deleteRecursively()
    }
}
