package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactAnalyzerTest {
    private val analyzer = ContactAnalyzer(PhoneNormalizer("IL"))
    private var nextId = 1L

    private fun contact(
        name: String?,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
    ) = Contact(
        id = nextId++,
        name = name?.let { NameEntry(nextId++, it) },
        phones = phones.map { PhoneEntry(nextId++, it) },
        emails = emails.map { EmailEntry(nextId++, it) },
    )

    @Test
    fun `local number gets an international fix`() {
        val c = contact("משה כהן", phones = listOf("050-1234567"))
        val issue = analyzer.analyze(listOf(c)).ofType<Issue.PhoneFormat>().single()
        assertEquals("+972501234567", issue.suggested)
    }

    @Test
    fun `already canonical number is not reported`() {
        val c = contact("משה כהן", phones = listOf("+972501234567"))
        assertTrue(analyzer.analyze(listOf(c)).issues.isEmpty())
    }

    @Test
    fun `same number twice on one contact keeps the canonical copy`() {
        val c = contact("משה כהן", phones = listOf("050-1234567", "+972501234567"))
        val report = analyzer.analyze(listOf(c))
        val repeat = report.ofType<Issue.RepeatedEntries>().single()
        assertEquals(listOf("050-1234567"), repeat.repeatedPhones.map { it.number })
        // The copy being removed isn't also offered a format fix.
        assertTrue(report.ofType<Issue.PhoneFormat>().isEmpty())
    }

    @Test
    fun `contacts sharing a number are duplicates`() {
        val a = contact("משה כהן", phones = listOf("0501234567"))
        val b = contact("משה", phones = listOf("+972 50-123-4567"), emails = listOf("moshe@example.com"))
        val dup = analyzer.analyze(listOf(a, b)).ofType<Issue.Duplicates>().single()
        assertEquals(setOf(DuplicateReason.SAME_PHONE), dup.reasons)
        assertTrue(dup.confident)
        assertEquals("משה כהן", dup.merged.name)
        assertEquals(listOf("+972501234567"), dup.merged.phones)
        assertEquals(listOf("moshe@example.com"), dup.merged.emails)
    }

    @Test
    fun `family landline under two names is not a confident duplicate`() {
        val a = contact("משה כהן", phones = listOf("02-5801234"))
        val b = contact("רחל כהן", phones = listOf("025801234"))
        val dup = analyzer.analyze(listOf(a, b)).ofType<Issue.Duplicates>().single()
        assertFalse(dup.confident)
    }

    @Test
    fun `name match ignores word order and niqqud`() {
        val a = contact("משה כהן", phones = listOf("0501111111"))
        val b = contact("כֹּהֵן מֹשֶׁה", phones = listOf("0502222222"))
        val dup = analyzer.analyze(listOf(a, b)).ofType<Issue.Duplicates>().single()
        assertEquals(setOf(DuplicateReason.SAME_NAME), dup.reasons)
        assertFalse(dup.confident)
        assertEquals(2, dup.merged.phones.size)
    }

    @Test
    fun `duplicates chain through different links into one group`() {
        val a = contact("Moshe Cohen", phones = listOf("0501234567"))
        val b = contact("Moshe", phones = listOf("0501234567"), emails = listOf("m@example.com"))
        val c = contact(null, emails = listOf("M@example.com"))
        val dup = analyzer.analyze(listOf(a, b, c)).ofType<Issue.Duplicates>().single()
        assertEquals(3, dup.contacts.size)
        assertEquals(setOf(DuplicateReason.SAME_PHONE, DuplicateReason.SAME_EMAIL), dup.reasons)
    }

    @Test
    fun `number saved as the name counts as missing name, with email guess`() {
        val c = contact("0501234567", phones = listOf("+972501234567"), emails = listOf("david.levi@gmail.com"))
        val issue = analyzer.analyze(listOf(c)).ofType<Issue.MissingName>().single()
        assertEquals("David Levi", issue.suggested)
    }

    @Test
    fun `missing name without email has no guess`() {
        val c = contact(null, phones = listOf("+972501234567"))
        assertNull(analyzer.analyze(listOf(c)).ofType<Issue.MissingName>().single().suggested)
    }

    @Test
    fun `name cleanup`() {
        assertEquals("Moshe Cohen", Names.cleanup("  moshe   cohen "))
        assertEquals("Moshe Cohen", Names.cleanup("MOSHE COHEN"))
        assertEquals("משה כהן", Names.cleanup("משה  כהן"))
        assertNull(Names.cleanup("Moshe Cohen"))
        assertNull(Names.cleanup("McDonald"))
    }

    @Test
    fun `empty contact`() {
        val c = contact("משה כהן")
        assertEquals(1, analyzer.analyze(listOf(c)).ofType<Issue.EmptyContact>().size)
    }

    @Test
    fun `short code shared by two contacts is not a duplicate`() {
        val a = contact("מוקד א", phones = listOf("*2700"))
        val b = contact("מוקד ב", phones = listOf("*2700"))
        assertTrue(analyzer.analyze(listOf(a, b)).ofType<Issue.Duplicates>().isEmpty())
    }
}
