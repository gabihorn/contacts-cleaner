package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaCodeTest {
    private val il = PhoneNormalizer("IL")
    private val analyzer = ContactAnalyzer(il)
    private var nextId = 1L

    private fun contact(name: String, vararg phones: String) = Contact(
        id = nextId++,
        name = NameEntry(nextId++, name),
        phones = phones.map { PhoneEntry(nextId++, it) },
    )

    @Test
    fun `area code of a landline`() {
        assertEquals("02", il.israeliAreaCode("02-5801234"))
        assertEquals("03", il.israeliAreaCode("+97236123456"))
        assertNull(il.israeliAreaCode("050-1234567"))
        assertNull(il.israeliAreaCode("+12125551234"))
    }

    @Test
    fun `seven digits without area code`() {
        assertTrue(il.lacksIsraeliAreaCode("5801234"))
        assertTrue(il.lacksIsraeliAreaCode("580-1234"))
        assertEquals(false, il.lacksIsraeliAreaCode("0501234"))
        assertEquals(false, il.lacksIsraeliAreaCode("*580123"))
        assertEquals(false, PhoneNormalizer("US").lacksIsraeliAreaCode("5801234"))
    }

    @Test
    fun `adding the area code`() {
        assertEquals("+97225801234", il.withAreaCode("580-1234", "02"))
    }

    @Test
    fun `guess comes from the same contact first`() {
        val book = listOf(
            contact("א", "02-5801111"),
            contact("ב", "02-5802222"),
            contact("ג", "04-8123456", "6123456"),
        )
        val issue = analyzer.analyze(book).ofType<Issue.MissingAreaCode>().single()
        assertEquals("04", issue.guess)
    }

    @Test
    fun `otherwise the most common area code in the book`() {
        val book = listOf(
            contact("א", "02-5801111"),
            contact("ב", "02-5802222"),
            contact("ג", "03-6123456"),
            contact("ד", "5803333"),
        )
        val report = analyzer.analyze(book)
        assertEquals("02", report.ofType<Issue.MissingAreaCode>().single().guess)
        assertTrue(report.ofType<Issue.InvalidPhone>().isEmpty())
    }
}
