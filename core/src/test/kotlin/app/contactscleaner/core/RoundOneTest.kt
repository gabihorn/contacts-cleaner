package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimilarNameDuplicatesTest {
    private val analyzer = ContactAnalyzer(PhoneNormalizer("IL"))
    private var nextId = 1L

    private fun contact(name: String, phone: String) = Contact(
        id = nextId++,
        name = NameEntry(nextId++, name),
        phones = listOf(PhoneEntry(nextId++, phone)),
    )

    private fun groups(vararg c: Contact) = analyzer.analyze(c.toList()).ofType<Issue.Duplicates>()

    @Test
    fun `hebrew and english spelling are one group, never preselected`() {
        val dup = groups(contact("משה כהן", "0528111111"), contact("Moshe Cohen", "0528222222")).single()
        assertEquals(setOf(DuplicateReason.SIMILAR_NAME), dup.reasons)
        assertFalse(dup.confident)
    }

    @Test
    fun `nickname and one-letter difference`() {
        assertEquals(1, groups(contact("יוסי לוי", "0528111111"), contact("יוסף לוי", "0528222222")).size)
        assertEquals(1, groups(contact("אבי כהן", "0528111111"), contact("אביב כהן", "0528222222")).size)
    }

    @Test
    fun `two different one-letter changes don't meet`() {
        assertTrue(groups(contact("אבי דן", "0528111111"), contact("אבי רן", "0528222222")).isEmpty())
    }

    @Test
    fun `same name doesn't also say similar`() {
        val dup = groups(contact("משה כהן", "0528111111"), contact("משה כהן", "0528222222")).single()
        assertEquals(setOf(DuplicateReason.SAME_NAME), dup.reasons)
    }
}

class SuspiciousNameTest {
    @Test
    fun `patterns`() {
        assertEquals(NameProblem.SINGLE_LETTER, Names.suspiciousReason("א"))
        assertEquals(NameProblem.SINGLE_LETTER, Names.suspiciousReason("מ."))
        assertEquals(NameProblem.GENERIC, Names.suspiciousReason("שליח גז"))
        assertEquals(NameProblem.GENERIC, Names.suspiciousReason("אינסטלטור מומלץ"))
        assertEquals(NameProblem.GENERIC, Names.suspiciousReason("ההוא מהאירוע"))
        assertEquals(NameProblem.EMOJI, Names.suspiciousReason("משה ❤️"))
        assertEquals(NameProblem.ONLY_EMOJI, Names.suspiciousReason("🔥🔥"))
    }

    @Test
    fun `real names are fine`() {
        assertNull(Names.suspiciousReason("משה חשמלאי"))
        assertNull(Names.suspiciousReason("משה כהן"))
        assertNull(Names.suspiciousReason("Moshe"))
        assertNull(Names.suspiciousReason("מה"))
    }

    @Test
    fun `emoji fix keeps the name`() {
        assertEquals("משה כהן", Names.stripEmoji("משה ❤️ כהן 👍🏽"))
        val c = Contact(1, NameEntry(2, "אמא ❤️"), phones = listOf(PhoneEntry(3, "+972528123456")))
        val issue = ContactAnalyzer(PhoneNormalizer("IL")).analyze(listOf(c)).ofType<Issue.SuspiciousName>().single()
        assertEquals("אמא", issue.suggested)
    }
}

class PhoneLabelTest {
    private val il = PhoneNormalizer("IL")

    @Test
    fun `line types`() {
        assertEquals(LineType.MOBILE, il.lineType("052-8123456"))
        assertEquals(LineType.LANDLINE, il.lineType("02-5801234"))
        assertNull(il.lineType("*2700"))
    }

    @Test
    fun `only clear mistakes`() {
        assertEquals(PhoneTypes.MOBILE, PhoneTypes.suggest(PhoneTypes.HOME, LineType.MOBILE))
        assertEquals(PhoneTypes.MOBILE, PhoneTypes.suggest(PhoneTypes.FAX_HOME, LineType.MOBILE))
        assertEquals(PhoneTypes.HOME, PhoneTypes.suggest(PhoneTypes.MOBILE, LineType.LANDLINE))
        assertNull(PhoneTypes.suggest(PhoneTypes.WORK, LineType.MOBILE))
        assertNull(PhoneTypes.suggest(PhoneTypes.MOBILE, LineType.MOBILE))
        assertNull(PhoneTypes.suggest(PhoneTypes.CUSTOM, LineType.LANDLINE))
    }

    @Test
    fun `analyzer reports a mobile saved as home`() {
        val c = Contact(1, NameEntry(2, "משה"), phones = listOf(PhoneEntry(3, "+972528123456", type = PhoneTypes.HOME)))
        val issue = ContactAnalyzer(il).analyze(listOf(c)).ofType<Issue.PhoneLabel>().single()
        assertEquals(PhoneTypes.MOBILE, issue.suggestedType)
    }
}

class CsvExporterTest {
    @Test
    fun `bom, quoting, columns`() {
        val contacts = listOf(
            Contact(1, NameEntry(2, "כהן, משה \"הגדול\""), phones = listOf(PhoneEntry(3, "+972528123456"), PhoneEntry(4, "025801234"))),
            Contact(5, NameEntry(6, "דוד"), emails = listOf(EmailEntry(7, "d@example.com")), onWhatsApp = true),
        )
        val csv = CsvExporter.export(contacts)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("שם,טלפון 1,טלפון 2,אימייל 1,ארגון,וואטסאפ,שיחה אחרונה", lines[0])
        assertEquals("\"כהן, משה \"\"הגדול\"\"\",+972528123456,025801234,,,,", lines[1])
        assertEquals("דוד,,,d@example.com,,כן,", lines[2])
    }

    @Test
    fun `formula injection`() {
        assertEquals("'=HYPERLINK(\"x\")", CsvExporter.text("=HYPERLINK(\"x\")"))
        assertEquals("'@cmd", CsvExporter.text("@cmd"))
        assertEquals("משה", CsvExporter.text("משה"))
        assertEquals("+972528123456", CsvExporter.phone("+972528123456"))
        assertEquals("'+1+1", CsvExporter.phone("+1+1"))
        assertEquals("'=1+1", CsvExporter.phone("=1+1"))
    }
}
