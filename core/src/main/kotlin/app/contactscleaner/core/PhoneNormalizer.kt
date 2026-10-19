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

enum class LineType { MOBILE, LANDLINE }

enum class OutdatedReason {
    /** 05X + 6 digits: mobile numbers got a 7th digit in 1999. */
    OLD_MOBILE,

    /** 0X + 6 digits: local numbers got a 7th digit decades ago. */
    OLD_LANDLINE,

    /** The right length, but in a range never handed to any operator. */
    UNALLOCATED,
}

sealed interface PhoneResult {
    /**
     * A real number. [formatted] is its canonical form; [key] identifies it for
     * comparisons. [allocated] is false when it has the right shape but sits in
     * a range no operator has been given, so it most likely doesn't exist.
     */
    data class Valid(
        val formatted: String,
        val key: String,
        val regionCode: String?,
        val allocated: Boolean = true,
    ) : PhoneResult

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
        return PhoneResult.Valid(formatted, e164, util.getRegionCodeForNumber(parsed), util.isValidNumber(parsed))
    }

    /**
     * Why [raw] is probably out of service, or null if there's no sign of it.
     * Israeli numbers in a pre-1990s/1999 format (6-digit local landline,
     * 6-digit mobile) no longer exist as written; any number in an
     * unallocated range likely never did.
     */
    fun outdatedReason(raw: String): OutdatedReason? {
        when (val r = normalize(raw)) {
            is PhoneResult.Valid -> return OutdatedReason.UNALLOCATED.takeIf { !r.allocated }
            is PhoneResult.Special -> return null
            is PhoneResult.Invalid -> Unit
        }
        if (defaultRegion != "IL" && !raw.trim().startsWith("+972")) return null
        val local = israeliLocalDigits(raw) ?: return null
        return when {
            local.length == 9 && local.startsWith("05") -> OutdatedReason.OLD_MOBILE
            local.length == 8 && local[1] in "23489" -> OutdatedReason.OLD_LANDLINE
            else -> null
        }
    }

    /** "+972 2-123456" / "00972..." / "02-123456" as local digits with the leading 0, or null if not Israeli. */
    private fun israeliLocalDigits(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val plus = raw.trim().startsWith("+")
        return when {
            digits.startsWith("00972") -> "0" + digits.removePrefix("00972")
            digits.startsWith("972") && (plus || defaultRegion == "IL") -> "0" + digits.removePrefix("972")
            plus -> null
            defaultRegion == "IL" && digits.startsWith("0") -> digits
            else -> null
        }
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

    /** Mobile or landline, when libphonenumber can tell; null otherwise. */
    fun lineType(raw: String): LineType? {
        val key = (normalize(raw) as? PhoneResult.Valid)?.key ?: return null
        val type = try {
            util.getNumberType(util.parse(key, null))
        } catch (_: NumberParseException) {
            return null
        }
        return when (type) {
            PhoneNumberUtil.PhoneNumberType.MOBILE -> LineType.MOBILE
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> LineType.LANDLINE
            else -> null
        }
    }

    /** Comparison key: E.164 when valid, the bare digits otherwise. */
    fun key(raw: String): String = when (val r = normalize(raw)) {
        is PhoneResult.Valid -> r.key
        is PhoneResult.Special -> r.key
        is PhoneResult.Invalid -> r.key
    }

    /**
     * True if some valid number of the same length starts with the same two
     * digits. "50-555…" passes (050 is a mobile prefix) even when this exact
     * range isn't allocated yet; "50-12345" (an old 6-digit mobile, now
     * landline-length) doesn't, since no landline area starts with 5.
     */
    private fun prefixInUse(number: PhoneNumber): Boolean {
        val national = number.nationalNumber.toString()
        if (national.length < 3) return false
        return ('0'..'9').any { d ->
            val candidate = PhoneNumber()
                .setCountryCode(number.countryCode)
                .setNationalNumber((national.take(2) + d.toString().repeat(national.length - 2)).toLong())
            util.isValidNumber(candidate)
        }
    }

    /**
     * libphonenumber's "valid" means "in a range known to be allocated", which
     * lags reality and rejects real numbers in new ranges. Unless [strict], a
     * number of the right length for its country is good enough.
     */
    private fun parse(text: String, region: String?, strict: Boolean): PhoneNumber? = try {
        util.parse(text, region ?: "ZZ").takeIf { number ->
            util.isValidNumber(number) || (!strict && !number.isItalianLeadingZero && prefixInUse(number) &&
                plausibleTypes.any { type ->
                    util.isPossibleNumberForTypeWithReason(number, type) == PhoneNumberUtil.ValidationResult.IS_POSSIBLE
                })
        }
    } catch (_: NumberParseException) {
        null
    }
}
