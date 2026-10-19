package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Names here are made up, in the shapes seen in real address books.
class AffixesTest {
    private val book = listOf(
        "ס005 מרים לוי עבודה ובריאות",
        "ס005 גלית כהן צילומי ילדים",
        "ס005 רות ישראלי",
        "ס002 יהודית מזרחי",
        "ס002 שרה גולן",
        "ס002 דנה בר",
        "ס16 Lusso",
        "Tz +972 50-000-0001",
        "Tz+972 55-000-0002",
        "Tz +972 54-000-0003",
        "Dr Moshe Cohen",
        "Dr Avi Levi",
        "Dr Dan Bar",
        "משה כהן",
        "משה לוי",
        "משה בר",
        "Moshe 2",
    )

    @Test
    fun `finds only tags shared by several names`() {
        val tags = Affixes.find(book)
        assertEquals(setOf("ס005", "ס002", "Tz"), tags.keys)
        assertEquals(3, tags["ס005"])
    }

    @Test
    fun `strips the tag and tidies`() {
        val tags = setOf("ס005", "Tz")
        assertEquals("מרים לוי עבודה ובריאות", Affixes.strip("ס005 מרים לוי עבודה ובריאות", tags))
        assertEquals("+972 50-000-0001", Affixes.strip("Tz +972 50-000-0001", tags))
        assertEquals("+972 55-000-0002", Affixes.strip("Tz+972 55-000-0002", tags))
        assertEquals("משה כהן", Affixes.strip("משה כהן", tags))
    }

    @Test
    fun `analyzer suggests the clean name, or nothing when only a number is left`() {
        var id = 1L
        val contacts = book.map { Contact(id++, NameEntry(id++, it), phones = listOf(PhoneEntry(id++, "+97252${(1000000 + id)}"))) }
        val issues = ContactAnalyzer(PhoneNormalizer("IL")).analyze(contacts).ofType<Issue.NameTag>()
        assertEquals(9, issues.size)
        assertEquals("גלית כהן צילומי ילדים", issues.first { it.name.displayName!!.startsWith("ס005 גלית") }.suggested)
        assertTrue(issues.filter { "Tz" in it.tags }.all { it.suggested.isEmpty() })
    }
}
