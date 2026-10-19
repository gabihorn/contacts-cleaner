package app.contactscleaner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InactiveFilterTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10_000 * day

    private fun issue(onWhatsApp: Boolean, daysAgo: Int?) = Issue.Inactive(
        Contact(1, NameEntry(2, "משה"), phones = listOf(PhoneEntry(3, "+972528123456"))),
        onWhatsApp,
        daysAgo?.let { now - it * day },
    )

    @Test
    fun `default is on whatsapp and never called`() {
        val f = InactiveFilter()
        assertTrue(f.matches(issue(onWhatsApp = true, daysAgo = null), now))
        assertFalse(f.matches(issue(onWhatsApp = true, daysAgo = 900), now))
        assertFalse(f.matches(issue(onWhatsApp = false, daysAgo = null), now))
    }

    @Test
    fun `idle ranges include never`() {
        val f = InactiveFilter(WhatsAppFilter.ANY, IdleFilter.TWO_YEARS)
        assertTrue(f.matches(issue(false, null), now))
        assertTrue(f.matches(issue(false, 800), now))
        assertFalse(f.matches(issue(false, 500), now))
    }

    @Test
    fun `not on whatsapp`() {
        val f = InactiveFilter(WhatsAppFilter.NOT_ON_WHATSAPP, IdleFilter.ANY)
        assertTrue(f.matches(issue(false, 3), now))
        assertFalse(f.matches(issue(true, null), now))
    }

    @Test
    fun `analyzer adds one per contact with a phone, only when asked`() {
        val withPhone = Contact(1, NameEntry(2, "משה"), phones = listOf(PhoneEntry(3, "+972528123456")), onWhatsApp = true)
        val noPhone = Contact(4, NameEntry(5, "דוד"), emails = listOf(EmailEntry(6, "d@example.com")))
        val analyzer = ContactAnalyzer(PhoneNormalizer("IL"))
        assertEquals(0, analyzer.analyze(listOf(withPhone, noPhone)).ofType<Issue.Inactive>().size)
        val inactive = analyzer.analyze(listOf(withPhone, noPhone), withActivity = true).ofType<Issue.Inactive>()
        assertEquals(listOf(1L), inactive.map { it.contact.id })
        assertTrue(inactive.single().onWhatsApp)
    }
}
