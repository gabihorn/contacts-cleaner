package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhoneNormalizerTest {
    private val il = PhoneNormalizer("IL")

    private fun formatted(raw: String, n: PhoneNormalizer = il) =
        assertIs<PhoneResult.Valid>(n.normalize(raw), raw).formatted

    @Test
    fun `israeli mobile in every common spelling`() {
        listOf(
            "0501234567",
            "050-123-4567",
            "050 1234567",
            "(050) 123-4567",
            "+972501234567",
            "+972 50-123-4567",
            "+972-0501234567",
            "00972501234567",
            "972501234567",
            " 050.123.4567 ",
        ).forEach { assertEquals("+972501234567", formatted(it), it) }
    }

    @Test
    fun `israeli landline`() {
        assertEquals("+97225801234", formatted("02-5801234"))
        assertEquals("+97225801234", formatted("025801234"))
    }

    @Test
    fun `foreign numbers keep their country`() {
        assertEquals("+12125551234", formatted("+1 (212) 555-1234"))
        assertEquals("+12125551234", formatted("001 212 555 1234"))
        assertEquals("+447911123456", formatted("+44 7911 123456"))
        assertEquals("+447911123456", formatted("447911123456"))
    }

    @Test
    fun `international display format`() {
        val n = PhoneNormalizer("IL", PhoneFormat.INTERNATIONAL)
        assertEquals("+972 50-123-4567", formatted("0501234567", n))
    }

    @Test
    fun `short and star codes are left alone`() {
        assertIs<PhoneResult.Special>(il.normalize("*2700"))
        assertIs<PhoneResult.Special>(il.normalize("100"))
        assertIs<PhoneResult.Special>(il.normalize("*9848#"))
    }

    @Test
    fun `garbage is invalid`() {
        assertIs<PhoneResult.Invalid>(il.normalize("0501234"))
        assertIs<PhoneResult.Invalid>(il.normalize("05012345678901"))
        assertIs<PhoneResult.Invalid>(il.normalize("abc"))
        assertIs<PhoneResult.Invalid>(il.normalize("0001234567"))
    }

    @Test
    fun `same number spelled differently has the same key`() {
        assertEquals(il.key("050-1234567"), il.key("+972 50 123 4567"))
    }
}
