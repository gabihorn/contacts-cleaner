package app.contactscleaner.core

enum class WhatsAppFilter { ANY, ON_WHATSAPP, NOT_ON_WHATSAPP }

/** How long since the last phone call. Every range above NEVER also includes "never". */
enum class IdleFilter(val years: Int?) {
    ANY(null),
    NEVER(null),
    YEAR(1),
    TWO_YEARS(2),
    THREE_YEARS(3),
}

/**
 * Which [Issue.Inactive] contacts to show. The default, "on WhatsApp but
 * never called", is the typical contact saved only to see their status.
 */
data class InactiveFilter(
    val whatsApp: WhatsAppFilter = WhatsAppFilter.ON_WHATSAPP,
    val idle: IdleFilter = IdleFilter.NEVER,
) {
    fun matches(issue: Issue.Inactive, now: Long): Boolean {
        val whatsAppOk = when (whatsApp) {
            WhatsAppFilter.ANY -> true
            WhatsAppFilter.ON_WHATSAPP -> issue.onWhatsApp
            WhatsAppFilter.NOT_ON_WHATSAPP -> !issue.onWhatsApp
        }
        val idleOk = when (idle) {
            IdleFilter.ANY -> true
            IdleFilter.NEVER -> issue.lastCall == null
            else -> issue.lastCall == null || now - issue.lastCall >= idle.years!! * YEAR_MS
        }
        return whatsAppOk && idleOk
    }

    private companion object {
        const val YEAR_MS = 365L * 24 * 60 * 60 * 1000
    }
}
