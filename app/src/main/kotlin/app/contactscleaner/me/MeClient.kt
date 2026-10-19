package app.contactscleaner.me

import app.contactscleaner.core.me.MeListResult
import app.contactscleaner.core.me.MeResponseParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An error with a message ready to show the user, in Hebrew. */
class MeException(message: String) : Exception(message)

/**
 * A minimal client for ME's undocumented API: activate with the one-time
 * code, then read who saved the user. Requests go straight from the phone
 * to ME over HTTPS. Nothing is logged, and the code is never retried
 * automatically since it can be used only once.
 */
class MeClient(private val baseUrl: String = MeConfig.API_BASE) {

    /** Trades the activation code for an access token. */
    fun activate(phone: String, code: String): String {
        require(phone.all { it.isDigit() } && code.all { it.isDigit() })
        // ME expects the phone number as a JSON number; both values are digits only, so this is safe JSON.
        val body = """{"activation_code": "$code", "activation_type": "sms", "phone_number": $phone}"""
        val (status, text) = request("POST", "/auth/authorization/activate/", body = body)
        if (status in 200..299) {
            return MeResponseParser.parseAccessToken(text)
                ?: throw MeException("התשובה של ME לא במבנה המוכר. ייתכן שהממשק שלהם השתנה.")
        }
        val errorCode = MeResponseParser.parseErrorCode(text)
        throw MeException(
            when {
                errorCode == "api_phone_number_doesnt_exists" ->
                    "ME לא זיהתה חשבון למספר הזה. בדקו באפליקציה הרשמית של ME שהמספר רשום ושהוא המספר הנכון."
                status == 400 -> "ME לא קיבלה את הקוד. ייתכן שפג תוקפו או שכבר נוצל. בקשו קוד חדש מהבוט."
                else -> statusMessage(status)
            }
        )
    }

    /** Everyone who saved the user, as ME lists them. */
    fun fetchSavedMe(token: String): MeListResult.Ok {
        val (status, text) = request("GET", "/main/names/groups/", token = token)
        if (status !in 200..299) {
            throw MeException(
                if (status == 401 || status == 403) "ההתחברות ל-ME פגה או נדחתה. התחברו מחדש עם קוד חדש."
                else statusMessage(status)
            )
        }
        return when (val result = MeResponseParser.parseGroups(text)) {
            is MeListResult.Ok -> result
            is MeListResult.UnknownSchema ->
                throw MeException("הרשימה מ-ME הגיעה במבנה לא מוכר, אז לא נציג אותה כדי לא להטעות. ייתכן שהממשק של ME השתנה.")
        }
    }

    private fun statusMessage(status: Int) = when (status) {
        401, 403 -> "ME סירבה לבקשה. בקשו קוד חדש ונסו שוב."
        429 -> "יותר מדי בקשות ל-ME. חכו כמה דקות ונסו שוב."
        in 500..599 -> "תקלה בצד של ME. נסו שוב בעוד כמה דקות, עם קוד חדש."
        else -> "ME החזירה שגיאה ($status)."
    }

    private fun request(method: String, path: String, body: String? = null, token: String? = null): Pair<Int, String> {
        val connection = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw MeException("אין חיבור ל-ME. בדקו את האינטרנט ונסו שוב.")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            // ME took the token as-is in this header, without "Bearer".
            token?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } catch (e: IOException) {
            throw MeException("אין חיבור ל-ME. בדקו את האינטרנט ונסו שוב.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}

object MeConfig {
    const val API_BASE = "https://app.mobile.me.app"

    /** ME's WhatsApp bot that sends the activation link. Worked in the prototype; may change. */
    const val ACTIVATION_BOT = "https://wa.me/447427928793?text=Connectme"
}
