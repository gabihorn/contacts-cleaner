package app.contactscleaner.core

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

enum class PhoneFormat {
    /** +972501234567 — compact, what WhatsApp and most apps match on. */
    E164,

    /** +972 50-123-4567 — easier to read. */
    INTERNATIONAL,
}

sealed interface PhoneResult {
    /** A real number. [formatted] is its canonical form; [key] identifies it for comparisons. */
    data class Valid(val formatted: String, val key: String, val regionCode: String?) : PhoneResult

    /** Short codes, star codes and the like (e.g. *2700). Left alone. */
    data class Special(val key: String) : PhoneResult

    /** Looks like a phone number but isn't valid in any region we tried. */
    data class Invalid(val key: String) : PhoneResult
}

/**
 * Turns whatever was typed into a contact (050-123-4567, 00972 50 123 4567,
 * 972501234567, +972-50-1234567 …) into one canonical international form.
 *
 * Numbers without a country code are read as local to [defaultRegion].
 */
class PhoneNormalizer(
    val defaultRegion: String = "IL",
    val format: PhoneFormat = PhoneFormat.E164,
) {
    private val util = PhoneNumberUtil.getInstance()

    // Lengths are checked per type: a mobile number missing a digit should not
    // pass just because some short service number happens to be that long.
    private val plausibleTypes = listOf(
        PhoneNumberUtil.PhoneNumberType.MOBILE,
        PhoneNumberUtil.PhoneNumberType.FIXED_LINE,
    )

    fun normalize(raw: String): PhoneResult {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return PhoneResult.Invalid(trimmed)
        if (trimmed.any { it == '*' || it == '#' } || digits.length < 7) {
            return PhoneResult.Special(digits)
        }

        // "972501234567" / "447911123456": a country code typed without the
        // leading + or 00. Only trusted when the result is a fully valid number.
        val missingPlus = !trimmed.startsWith("+") && !digits.startsWith("0")
        val parsed = parse(trimmed, defaultRegion, strict = true)
            ?: (if (missingPlus) parse("+$digits", null, strict = true) else null)
            ?: parse(trimmed, defaultRegion, strict = false)

        if (parsed == null) return PhoneResult.Invalid(digits)

        val e164 = util.format(parsed, PhoneNumberFormat.E164)
        val formatted = when (format) {
            PhoneFormat.E164 -> e164
            PhoneFormat.INTERNATIONAL -> util.format(parsed, PhoneNumberFormat.INTERNATIONAL)
        }
        return PhoneResult.Valid(formatted, e164, util.getRegionCodeForNumber(parsed))
    }

    /**
     * For Israel only: the area code ("02") a landline uses, or null if [raw]
     * isn't an Israeli landline.
     */
    fun israeliAreaCode(raw: String): String? {
        val key = (normalize(raw) as? PhoneResult.Valid)?.key ?: return null
        // +972 + area digit + 7 digits; 5x and 7x are mobile/VoIP, not areas.
        if (!key.startsWith("+972") || key.length != 12) return null
        return "0${key[4]}".takeIf { it in Issue.MissingAreaCode.AREA_CODES }
    }

    /** True for a bare 7-digit local landline number, like 5801234 or 580-1234. */
    fun lacksIsraeliAreaCode(raw: String): Boolean {
        if (defaultRegion != "IL" || raw.trim().startsWith("+")) return false
        val digits = raw.filter { it.isDigit() }
        return digits.length == 7 && digits[0] in '2'..'9' && raw.none { it == '*' || it == '#' }
    }

    /** [raw] with [areaCode] in front, formatted; null if that still isn't a valid number. */
    fun withAreaCode(raw: String, areaCode: String): String? =
        (normalize(areaCode + raw.filter { it.isDigit() }) as? PhoneResult.Valid)?.formatted

    /** Comparison key: E.164 when valid, the bare digits otherwise. */
    fun key(raw: String): String = when (val r = normalize(raw)) {
        is PhoneResult.Valid -> r.key
        is PhoneResult.Special -> r.key
        is PhoneResult.Invalid -> r.key
    }

    /**
     * libphonenumber's "valid" means "in a range known to be allocated", which
     * lags reality and rejects real numbers in new ranges. Unless [strict], a
     * number of the right length for its country is good enough.
     */
    private fun parse(text: String, region: String?, strict: Boolean): PhoneNumber? = try {
        util.parse(text, region ?: "ZZ").takeIf { number ->
            util.isValidNumber(number) || (!strict && !number.isItalianLeadingZero && plausibleTypes.any { type ->
                util.isPossibleNumberForTypeWithReason(number, type) == PhoneNumberUtil.ValidationResult.IS_POSSIBLE
            })
        }
    } catch (_: NumberParseException) {
        null
    }
}
