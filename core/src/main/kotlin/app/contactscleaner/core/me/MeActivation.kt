package app.contactscleaner.core.me

import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.PhoneResult

/** What was pulled out of the text the user pasted from ME's WhatsApp bot. */
sealed interface ActivationInput {
    /** [phone] is international digits without "+", as ME expects it (972501234567). */
    data class Ready(val phone: String, val code: String) : ActivationInput

    /** Only a code was pasted; the phone number has to be typed. */
    data class NeedsPhone(val code: String) : ActivationInput

    data class Invalid(val message: String) : ActivationInput
}

/**
 * Reads ME's activation message, the bare activation link, or just the
 * 6-digit code. Copying from WhatsApp can add "&amp;", spaces and line
 * breaks, so those are tolerated.
 */
class ActivationParser(private val normalizer: PhoneNormalizer = PhoneNormalizer("IL")) {

    fun parse(pasted: String, manualPhone: String? = null): ActivationInput {
        val text = pasted.replace("&amp;", "&")
        val linkPhone = valueAfter(text, "phone_number")
        val linkCode = valueAfter(text, "activation_code")

        val code = when {
            linkCode != null -> linkCode.takeIf { it.length == 6 }
                ?: return ActivationInput.Invalid("קוד ההפעלה בקישור לא תקין. הוא צריך להיות בן 6 ספרות.")
            else -> {
                val codes = sixDigits.findAll(text).map { it.value }.distinct().toList()
                when (codes.size) {
                    0 -> return ActivationInput.Invalid("לא נמצא קוד הפעלה. הדביקו את ההודעה מ-ME, את הקישור או את הקוד בן 6 הספרות.")
                    1 -> codes.single()
                    else -> return ActivationInput.Invalid("נמצאו כמה קודים בטקסט. הדביקו רק את הודעת ME האחרונה.")
                }
            }
        }

        val fromLink = linkPhone?.let { toMePhone(it) }
        if (linkPhone != null && fromLink == null) {
            return ActivationInput.Invalid("מספר הטלפון בקישור לא תקין.")
        }
        val typed = manualPhone?.takeIf { it.isNotBlank() }?.let {
            toMePhone(it) ?: return ActivationInput.Invalid("מספר הטלפון שהוקלד לא תקין.")
        }
        if (fromLink != null && typed != null && fromLink != typed) {
            return ActivationInput.Invalid("המספר שהוקלד לא תואם למספר בקישור של ME. בדקו ששניהם של אותו חשבון.")
        }
        val phone = fromLink ?: typed ?: return ActivationInput.NeedsPhone(code)
        return ActivationInput.Ready(phone, code)
    }

    /** "+972 50-123-4567", "0501234567", "972501234567" -> "972501234567". */
    fun toMePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // Digits from ME's own link are already international; local input goes through the normalizer.
        val asTyped = if (raw.trim().startsWith("+") || digits.startsWith("0")) raw else "+$digits"
        return (normalizer.normalize(asTyped) as? PhoneResult.Valid)?.key?.removePrefix("+")
    }

    /** Digits after "name=", ignoring spaces and line breaks a copy may have inserted. */
    private fun valueAfter(text: String, name: String): String? {
        val match = Regex("""$name\s*=\s*([\d\s]+)""").find(text) ?: return null
        return match.groupValues[1].filter { it.isDigit() }.takeIf { it.isNotEmpty() }
    }

    private companion object {
        val sixDigits = Regex("""(?<!\d)\d{6}(?!\d)""")
    }
}
