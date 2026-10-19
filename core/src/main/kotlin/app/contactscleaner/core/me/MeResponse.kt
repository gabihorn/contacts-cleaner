package app.contactscleaner.core.me

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One person who saved the user, as ME reports it. Phone numbers stay strings throughout. */
data class MeEntry(
    val phone: String,
    val firstName: String?,
    val lastName: String?,
    /** The name this person saved the user under. Not their own name. */
    val savedYouAs: String?,
    /** ME's idea of whether the user saved them back; null when ME didn't say. */
    val inContactList: Boolean?,
)

sealed interface MeListResult {
    /**
     * [skipped] counts entries without a usable phone number. [maybePartial]
     * is set when the response hints at more pages we didn't get.
     */
    data class Ok(val entries: List<MeEntry>, val skipped: Int, val maybePartial: Boolean) : MeListResult

    /** The response isn't shaped like anything we know; better to stop than to report 0 contacts. */
    data class UnknownSchema(val detail: String) : MeListResult
}

/** Parses the response of GET /main/names/groups/. */
object MeResponseParser {
    private val json = Json { isLenient = false }

    fun parseGroups(body: String): MeListResult {
        val root = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return MeListResult.UnknownSchema("not JSON")
        }
        if (root !is JsonObject) return MeListResult.UnknownSchema("root is not an object")

        // Older responses used "names" with the same group shape.
        val groups = (root["groups"] ?: root["names"]) as? JsonArray
            ?: return MeListResult.UnknownSchema("no groups array")

        val entries = mutableListOf<MeEntry>()
        var skipped = 0
        for (group in groups) {
            if (group !is JsonObject) return MeListResult.UnknownSchema("group is not an object")
            val groupName = group.string("name")
            val contacts = group["contacts"] as? JsonArray ?: return MeListResult.UnknownSchema("group without contacts")
            for (contact in contacts) {
                if (contact !is JsonObject) return MeListResult.UnknownSchema("contact is not an object")
                val user = contact["user"] as? JsonObject
                val phone = user?.get("phone_number")?.let(::digits)
                if (user == null || phone == null) {
                    skipped++
                    continue
                }
                entries += MeEntry(
                    phone = phone,
                    firstName = user.string("first_name"),
                    lastName = user.string("last_name"),
                    savedYouAs = groupName,
                    inContactList = (contact["in_contact_list"] as? JsonPrimitive)?.booleanOrNull,
                )
            }
        }
        val next = root["next"]
        val maybePartial = next != null && next !is JsonNull
        return MeListResult.Ok(entries, skipped, maybePartial)
    }

    /** The access token from POST /auth/authorization/activate/, or null if there isn't one. */
    fun parseAccessToken(body: String): String? = try {
        (json.parseToJsonElement(body) as? JsonObject)?.string("access")
    } catch (_: Exception) {
        null
    }

    /** ME's error code ("api_phone_number_doesnt_exists" …) from an error body, if it has one. */
    fun parseErrorCode(body: String): String? = try {
        val o = json.parseToJsonElement(body) as? JsonObject
        o?.string("detail") ?: o?.string("error_code") ?: o?.string("code") ?: o?.string("message")
    } catch (_: Exception) {
        null
    }

    /** A phone number that may arrive as a JSON number or a string; kept as its digits, never as a number. */
    private fun digits(e: JsonElement): String? =
        (e as? JsonPrimitive)?.content?.filter { it.isDigit() }?.takeIf { it.length >= 7 }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
