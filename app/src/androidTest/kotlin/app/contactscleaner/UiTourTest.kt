package app.contactscleaner

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.data.ContactsRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Uses the real app like a person would, on contacts like the ones seen in
 * real address books, and saves a screenshot of each screen along the way.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiTourTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
    )

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<Long>()
    private val shots = File(context.getExternalFilesDir(null), "screens").apply { mkdirs() }
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun insert(name: String, vararg phones: String, email: String? = null) {
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
        phones.forEach {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, it)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build()
        }
        if (email != null) {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email)
                .build()
        }
        created += ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!)
    }

    @Before
    fun seed() {
        // The same people saved twice, the way it happens over the years.
        insert("משה כהן", "052-8123456")
        insert("Moshe Cohen", "+972 52-812-3456", email = "moshe@example.com")
        insert("יוסי לוי", "054-4567890")
        insert("יוסף לוי", "+972544567890")
        // A tag many names share, and a number with a typo.
        insert("ס005 מרים אמסלי", "053-3123456")
        insert("ס005 גלית דהן", "055-6677889")
        insert("ס005 רות סאקס", "058-7654321")
        insert("דוד אברמוביץ", "052-65432", "052-6543210")
        insert(".אלחנן דוידוב", "052-7894561")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun cleanUp() {
        scenario.close()
        val all = ContactsRepository(resolver).loadAll().flatMap { it.rawContactIds }
        (created + all).distinct().forEach { id ->
            resolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                null, null,
            )
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, timeout: Long = 30_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun back() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun tour() {
        // Opens and scans by itself.
        waitForText("זה מה שאפשר לסדר")
        shot("01-home")

        // Duplicates.
        compose.onNodeWithText("אנשי קשר כפולים").performClick()
        waitForText("שם אחרי האיחוד")
        shot("02-duplicates")
        back()

        // Tags in names.
        waitForText("זה מה שאפשר לסדר")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("תוספות קבועות בשם"))
        compose.onNodeWithText("תוספות קבועות בשם").performClick()
        waitForText("סמן את כל")
        shot("03-tags")
        back()

        // Delete the invalid number: what once closed the app.
        waitForText("זה מה שאפשר לסדר")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("מספרים לא תקינים"))
        compose.onNodeWithText("מספרים לא תקינים").performClick()
        waitForText("סמן הכל")
        compose.onNodeWithText("סמן הכל").performClick()
        shot("04-invalid-selected")
        compose.onNodeWithText("מחיקת", substring = true).performClick()
        waitForText("ביצוע")
        shot("05-confirm")
        compose.onNodeWithText("ביצוע").performClick()
        waitForText("סיימת כאן")
        shot("06-after-delete")

        val numbers = ContactsRepository(resolver).loadAll().flatMap { c -> c.phones.map { PhoneNormalizer("IL").key(it.number) } }
        assertTrue("the typo number is gone", numbers.none { it == "05265432" })
        assertTrue("the good number stayed", "+972526543210" in numbers)

        // Change log after it.
        back()
        waitForText("זה מה שאפשר לסדר")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("יומן שינויים", substring = true))
        compose.onNodeWithText("יומן שינויים", substring = true).performClick()
        waitForText("אומתו")
        shot("07-log")
    }
}
