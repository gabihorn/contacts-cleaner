package app.contactscleaner.core.me

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// All numbers and codes here are made up.
class ActivationParserTest {
    private val parser = ActivationParser()

    @Test
    fun `full multi-line message`() {
        val message = """
            Welcome to Me!
            Tap to activate:
            https://app.mobile.me.app/auth/activate-app?phone_number=972501234567&amp;activation_code=123456

            Code valid for 10 minutes.
        """.trimIndent()
        assertEquals(ActivationInput.Ready("972501234567", "123456"), parser.parse(message))
    }

    @Test
    fun `link broken by spaces and line breaks`() {
        val link = "https://app.mobile.me.app/auth/activate-app?phone_number=9725012 34567&activation_code=123\n456"
        assertEquals(ActivationInput.Ready("972501234567", "123456"), parser.parse(link))
    }

    @Test
    fun `code only needs a phone`() {
        assertEquals(ActivationInput.NeedsPhone("654321"), parser.parse(" 654321 "))
        assertEquals(ActivationInput.Ready("972501234567", "654321"), parser.parse("654321", manualPhone = "050-1234567"))
    }

    @Test
    fun `typed number must match the link`() {
        val link = "phone_number=972501234567&activation_code=123456"
        assertIs<ActivationInput.Ready>(parser.parse(link, manualPhone = "+972 50-123-4567"))
        assertIs<ActivationInput.Invalid>(parser.parse(link, manualPhone = "0527654321"))
    }

    @Test
    fun `bad codes`() {
        assertIs<ActivationInput.Invalid>(parser.parse("phone_number=972501234567&activation_code=12345"))
        assertIs<ActivationInput.Invalid>(parser.parse("no code here"))
        assertIs<ActivationInput.Invalid>(parser.parse("111111 or 222222"))
    }
}

class MeResponseParserTest {
    @Test
    fun `groups with saved, unsaved and missing flag`() {
        val body = """
            {"groups": [
              {"name": "משה מהעבודה", "contacts": [
                {"in_contact_list": false, "user": {"phone_number": 972509876543, "first_name": "ישראל", "last_name": "ישראלי"}},
                {"in_contact_list": true, "user": {"phone_number": 972521111111, "first_name": "דוד"}},
                {"user": {"phone_number": "972522222222"}},
                {"in_contact_list": false, "user": {"first_name": "בלי מספר"}}
              ]}
            ]}
        """
        val result = assertIs<MeListResult.Ok>(MeResponseParser.parseGroups(body))
        assertEquals(3, result.entries.size)
        assertEquals(1, result.skipped)
        assertFalse(result.maybePartial)
        val first = result.entries[0]
        assertEquals("972509876543", first.phone)
        assertEquals("משה מהעבודה", first.savedYouAs)
        assertEquals(false, first.inContactList)
        assertNull(result.entries[2].inContactList, "missing flag stays unknown, not false")
    }

    @Test
    fun `empty list is a valid answer`() {
        val result = assertIs<MeListResult.Ok>(MeResponseParser.parseGroups("""{"groups": []}"""))
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `unknown shapes are errors, not zero contacts`() {
        assertIs<MeListResult.UnknownSchema>(MeResponseParser.parseGroups("""{"results": []}"""))
        assertIs<MeListResult.UnknownSchema>(MeResponseParser.parseGroups("""{"groups": [{"name": "x"}]}"""))
        assertIs<MeListResult.UnknownSchema>(MeResponseParser.parseGroups("<html>error</html>"))
        assertIs<MeListResult.UnknownSchema>(MeResponseParser.parseGroups("""[]"""))
    }

    @Test
    fun `old names key and paging hint`() {
        val body = """{"names": [{"name": "x", "contacts": []}], "next": "https://example.invalid/?page=2"}"""
        val result = assertIs<MeListResult.Ok>(MeResponseParser.parseGroups(body))
        assertTrue(result.maybePartial)
    }

    @Test
    fun `token and error code`() {
        assertEquals("abc", MeResponseParser.parseAccessToken("""{"access": "abc", "refresh": "r"}"""))
        assertNull(MeResponseParser.parseAccessToken("not json"))
        assertEquals("api_phone_number_doesnt_exists", MeResponseParser.parseErrorCode("""{"detail": "api_phone_number_doesnt_exists"}"""))
    }
}

class MeImportPlannerTest {
    private val planner = MeImportPlanner()

    private fun entry(phone: String, first: String?, savedAs: String?, inList: Boolean?) =
        MeEntry(phone, first, null, savedAs, inList)

    @Test
    fun `plan import`() {
        val entries = listOf(
            entry("972509876543", "ישראל", "גבי", false),
            entry("972509876543", null, "גבי מהקהילה", false), // same person in two groups
            entry("972521111111", "דוד", "גבי", true),
            entry("972522222222", "שרה", "גבי", null),
            entry("972523333333", "רחל", "גבי", false), // already in my phone
            entry("123", "רע", "גבי", false),
        )
        val summary = planner.summarize(entries, myNumbers = setOf("+972523333333"))
        assertEquals(6, summary.received)
        assertEquals(1, summary.rejected)
        assertEquals(4, summary.people.size)
        assertEquals(listOf("+972509876543"), summary.toImport.map { it.phone })
        assertEquals(1, summary.alreadyInPhone)
        val person = summary.toImport.single()
        assertEquals(listOf("גבי", "גבי מהקהילה"), person.savedYouAs)
        assertEquals("ME - ישראל", person.importName("ME - "))
    }

    @Test
    fun `no name falls back to the number, never to saved-you-as`() {
        val p = MePerson("+972509876543", null, null, listOf("גבי"), true)
        assertEquals("ME - +972509876543", p.importName("ME - "))
    }

    @Test
    fun `saved in one group wins over unsaved in another`() {
        val summary = planner.summarize(
            listOf(entry("972509876543", "א", "x", false), entry("972509876543", "א", "y", true)),
            emptySet(),
        )
        assertTrue(summary.toImport.isEmpty())
    }
}
