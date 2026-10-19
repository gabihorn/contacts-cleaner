package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergePlannerTest {
    private val planner = MergePlanner(PhoneNormalizer("IL"))
    private var nextId = 1L

    private fun row(mime: String, data1: String, blob: ByteArray? = null) =
        DataRow(nextId++, mime, mapOf("data1" to data1), blob)

    private fun raw(accountType: String?, vararg rows: DataRow, account: String? = "me") =
        RawContact(nextId++, nextId++, accountType, account, rows = rows.toList())

    @Test
    fun `google copy survives and gets what the others have`() {
        val local = raw(
            "vnd.sec.contact.phone",
            row(MimeTypes.NAME, "Moshe"),
            row(MimeTypes.PHONE, "050-1234567"),
            row(MimeTypes.PHONE, "02-5801234"),
            row(MimeTypes.EMAIL, "Moshe@Example.com"),
            row(MimeTypes.EVENT, "1980-01-01"),
        )
        val google = raw(
            MimeTypes.GOOGLE_ACCOUNT,
            row(MimeTypes.NAME, "Moshe Cohen"),
            row(MimeTypes.PHONE, "+972501234567"),
            row(MimeTypes.EMAIL, "moshe@example.com"),
        )
        val plan = planner.plan(listOf(local, google), preferredName = "Moshe Cohen")

        assertEquals(google, plan.survivor)
        assertEquals(listOf(local), plan.toDelete)
        assertEquals(listOf("02-5801234", "1980-01-01"), plan.rowsToCopy.map { it.data1 })
        assertNull(plan.newName, "survivor already has the preferred name")
    }

    @Test
    fun `name is replaced when the preferred one differs`() {
        val a = raw(MimeTypes.GOOGLE_ACCOUNT, row(MimeTypes.NAME, "Moshe"))
        val b = raw(MimeTypes.GOOGLE_ACCOUNT, row(MimeTypes.NAME, "Moshe Cohen"))
        assertEquals("Moshe Cohen", planner.plan(listOf(a, b), "Moshe Cohen").newName)
    }

    @Test
    fun `photo copied only when survivor has none, groups only within an account`() {
        val photo = byteArrayOf(1, 2, 3)
        val google = raw(
            MimeTypes.GOOGLE_ACCOUNT,
            row(MimeTypes.NAME, "Moshe"),
            row(MimeTypes.PHONE, "0501234567"),
            row(MimeTypes.EMAIL, "m@example.com"),
        )
        val sameAccount = raw(
            MimeTypes.GOOGLE_ACCOUNT,
            row(MimeTypes.PHOTO, "", photo),
            row(MimeTypes.GROUP, "7"),
        )
        val otherAccount = raw(
            MimeTypes.GOOGLE_ACCOUNT,
            row(MimeTypes.PHOTO, "", byteArrayOf(9)),
            row(MimeTypes.GROUP, "8"),
            account = "work",
        )
        val plan = planner.plan(listOf(google, sameAccount, otherAccount), null)
        assertEquals(listOf(MimeTypes.PHOTO, MimeTypes.GROUP), plan.rowsToCopy.map { it.mimeType })
        assertEquals("7", plan.rowsToCopy.last().data1)
    }

    @Test
    fun `app-specific rows are never copied`() {
        val a = raw(MimeTypes.GOOGLE_ACCOUNT, row(MimeTypes.PHONE, "0501234567"))
        val b = raw(null, row("vnd.android.cursor.item/vnd.com.whatsapp.profile", "x"))
        assertEquals(emptyList(), planner.plan(listOf(a, b), null).rowsToCopy)
    }
}
