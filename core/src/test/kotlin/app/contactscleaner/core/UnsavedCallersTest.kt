package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnsavedCallersTest {
    private val normalizer = PhoneNormalizer("IL")
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun calls(number: String, n: Int, talked: Boolean = true, daysAgo: Long = 1) =
        (1..n).map { CallEntry(number, now - daysAgo * day - it, talked) }

    @Test
    fun `a number called often and never saved is listed once, in any spelling`() {
        val found = UnsavedCallers.find(
            calls("052-8123456", 2) + calls("+972528123456", 3),
            contacts = emptyList(), normalizer, now,
        )
        assertEquals(1, found.size)
        assertEquals(5, found.single().calls)
        assertEquals("+972528123456", found.single().number)
    }

    @Test
    fun `saved numbers, missed calls, rare and old calls are left out`() {
        val saved = Contact(1, NameEntry(2, "משה"), listOf(PhoneEntry(3, "0528123456")))
        val found = UnsavedCallers.find(
            calls("0528123456", 9) +            // saved, in another spelling
                calls("0541111111", 9, talked = false) + // never answered
                calls("0542222222", 3) +        // too few
                calls("0543333333", 9, daysAgo = 200), // too long ago
            contacts = listOf(saved), normalizer, now,
        )
        assertTrue(found.isEmpty(), "found $found")
    }

    @Test
    fun `most called comes first`() {
        val found = UnsavedCallers.find(calls("0541111111", 4) + calls("0542222222", 8), emptyList(), normalizer, now)
        assertEquals(listOf(8, 4), found.map { it.calls })
    }
}
