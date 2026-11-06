package app.contactscleaner.core.perf

import app.contactscleaner.core.Contact
import app.contactscleaner.core.ContactAnalyzer
import app.contactscleaner.core.EmailEntry
import app.contactscleaner.core.NameEntry
import app.contactscleaner.core.PhoneEntry
import app.contactscleaner.core.PhoneNormalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** An address book the size of a real heavy user's: 24,000 contacts. */
class BigBookTest {
    private val first = listOf("משה", "יוסף", "דוד", "שרה", "רחל", "יעקב", "אברהם", "חיים", "מרים", "לאה", "Moshe", "David", "Sarah")
    private val last = listOf("כהן", "לוי", "מזרחי", "פרץ", "ביטון", "אברהם", "פרידמן", "Cohen", "Levi", "Katz")

    fun book(size: Int): List<Contact> {
        val rnd = Random(1)
        var id = 1L
        return (1..size).map { i ->
            val raw = id++
            val number = "05${listOf(2, 3, 4, 5, 8).random(rnd)}${(1000000 + rnd.nextInt(8999999))}"
            val shown = if (rnd.nextBoolean()) number else "+972" + number.drop(1)
            val phones = buildList {
                add(PhoneEntry(id++, shown, type = 2, rawContactId = raw))
                // Same number kept in a second account's copy of the contact: not a repeat to fix.
                if (i % 3 == 0) add(PhoneEntry(id++, shown, type = 2, rawContactId = raw + 100_000_000))
                if (i % 7 == 0) add(PhoneEntry(id++, "02-${5000000 + rnd.nextInt(999999)}", type = 1, rawContactId = raw))
            }
            Contact(
                id = id++,
                name = NameEntry(id++, (if (i % 40 == 0) "ס005 " else "") + "${first.random(rnd)} ${last.random(rnd)}"),
                phones = phones,
                emails = if (i % 5 == 0) listOf(EmailEntry(id++, "user$i@example.com", rawContactId = raw)) else emptyList(),
                rawContactIds = listOf(raw),
                onWhatsApp = rnd.nextBoolean(),
            )
        }
    }

    @Test
    fun `24k contacts scan in reasonable time`() {
        val contacts = book(24_000)
        val analyzer = ContactAnalyzer(PhoneNormalizer("IL"))
        analyzer.analyze(contacts.take(500), withActivity = true) // warm up
        val start = System.nanoTime()
        val report = analyzer.analyze(contacts, withActivity = true)
        val seconds = (System.nanoTime() - start) / 1e9
        println("SCAN 24k: %.2f s, %d issues".format(seconds, report.issues.size))
        assertTrue(seconds < 20, "took $seconds s")
    }
}
