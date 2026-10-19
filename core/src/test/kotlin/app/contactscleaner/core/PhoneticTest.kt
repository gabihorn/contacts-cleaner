package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneticTest {
    private fun same(a: String, b: String) = assertEquals(Phonetic.key(a), Phonetic.key(b), "$a / $b")

    @Test
    fun `hebrew and english spellings match`() {
        same("משה כהן", "Moshe Cohen")
        same("משה כהן", "Moishe Kohen")
        same("יעקב כץ", "Yaakov Katz")
        assertEquals(Phonetic.key("יעקב לוי", loose = true), Phonetic.key("Yaakov Levi", loose = true))
        same("Chaim Katz", "חיים כץ")
    }

    @Test
    fun `nicknames and typos`() {
        same("יוסי כהן", "יוסף כהן")
        same("איציק לוי", "יצחק לוי")
        same("מושה כהן", "משה כהן")
        same("כהן משה", "משה כהן")
    }

    @Test
    fun `different people stay different`() {
        assertNotEquals(Phonetic.key("משה כהן"), Phonetic.key("משה לוי"))
        assertNotEquals(Phonetic.key("דוד כהן"), Phonetic.key("רחל כהן"))
    }

    @Test
    fun `single words are not compared`() {
        assertNull(Phonetic.key("משה"))
        assertNull(Phonetic.key("אבא"))
    }

    @Test
    fun `one dropped letter is near`() {
        val avi = Phonetic.key("אבי כהן")!!
        val aviv = Phonetic.key("אביב כהן")!!
        assertNotEquals(avi, aviv)
        assertTrue(avi in Phonetic.nearKeys(aviv))
    }
}
