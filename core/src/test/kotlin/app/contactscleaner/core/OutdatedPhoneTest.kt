package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutdatedPhoneTest {
    private val il = PhoneNormalizer("IL")

    @Test
    fun `old six-digit mobile`() {
        assertEquals(OutdatedReason.OLD_MOBILE, il.outdatedReason("050-123456"))
        assertEquals(OutdatedReason.OLD_MOBILE, il.outdatedReason("+972 52 123456"))
    }

    @Test
    fun `old six-digit landline`() {
        assertEquals(OutdatedReason.OLD_LANDLINE, il.outdatedReason("02-123456"))
        assertEquals(OutdatedReason.OLD_LANDLINE, il.outdatedReason("00972 3 123456"))
    }

    @Test
    fun `unallocated range`() {
        assertEquals(OutdatedReason.UNALLOCATED, il.outdatedReason("050-1234567"))
    }

    @Test
    fun `live numbers are not outdated`() {
        assertNull(il.outdatedReason("052-8123456"))
        assertNull(il.outdatedReason("02-5801234"))
        assertNull(il.outdatedReason("+12125551234"))
        assertNull(il.outdatedReason("*2700"))
    }

    @Test
    fun `analyzer reports outdated instead of invalid`() {
        val c = Contact(1, NameEntry(2, "משה"), phones = listOf(PhoneEntry(3, "050-123456")))
        val report = ContactAnalyzer(il).analyze(listOf(c))
        assertEquals(OutdatedReason.OLD_MOBILE, report.ofType<Issue.OutdatedPhone>().single().reason)
        assertTrue(report.ofType<Issue.InvalidPhone>().isEmpty())
    }
}

class TitlesTest {
    @Test
    fun `hebrew titles get one spelling`() {
        assertEquals("ר' משה כהן", Names.cleanup("ר. משה כהן"))
        assertEquals("ר' משה כהן", Names.cleanup("ר׳ משה כהן"))
        assertEquals("ר' משה כהן", Names.cleanup("ר משה כהן"))
        assertEquals("הרב משה כהן", Names.cleanup("הר' משה כהן"))
        assertEquals("הרב משה כהן", Names.cleanup("רב משה כהן"))
        assertEquals("ד\"ר משה כהן", Names.cleanup("דוקטור משה כהן"))
        assertEquals("ד\"ר משה כהן", Names.cleanup("ד״ר משה כהן"))
        assertEquals("עו\"ד משה כהן", Names.cleanup("עורך דין משה כהן"))
        assertEquals("משה כהן עו\"ד", Names.cleanup("משה כהן עו״ד"))
        assertEquals("משה כהן רו\"ח", Names.cleanup("משה כהן רואה חשבון"))
        assertEquals("הרב ד\"ר משה כהן", Names.cleanup("הר' דר' משה כהן"))
        assertEquals("גב' שרה לוי", Names.cleanup("גברת שרה לוי"))
    }

    @Test
    fun `english titles stay english`() {
        assertEquals("Rabbi Moshe Cohen", Names.cleanup("rav moshe cohen"))
        assertEquals("Dr. Moshe Cohen", Names.cleanup("Doctor Moshe Cohen"))
    }

    @Test
    fun `already canonical or not a title is left alone`() {
        assertNull(Names.cleanup("הרב משה כהן"))
        assertNull(Names.cleanup("ר' משה"))
        assertNull(Names.cleanup("רב"))
        assertNull(Names.cleanup("משה רב"))
        assertNull(Names.cleanup("משה עוד"))
    }

    @Test
    fun `titles don't stop a duplicate match`() {
        assertEquals(Names.matchKey("משה כהן"), Names.matchKey("הרב משה כהן"))
        assertEquals(Names.matchKey("משה כהן"), Names.matchKey("ר. משה כהן"))
    }
}
