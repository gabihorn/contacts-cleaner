package app.contactscleaner.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Contacts as a CSV that Excel opens correctly in Hebrew: UTF-8 with a BOM,
 * RFC 4180 quoting, and one column per phone/email up to the most any
 * contact has. Phone numbers stay text, so no digits or leading zeros are lost.
 */
object CsvExporter {
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun export(contacts: List<Contact>): String {
        val phoneColumns = contacts.maxOfOrNull { it.phones.size }?.coerceAtLeast(1) ?: 1
        val emailColumns = contacts.maxOfOrNull { it.emails.size }?.coerceAtLeast(1) ?: 1
        val header = listOf("שם") +
            (1..phoneColumns).map { "טלפון $it" } +
            (1..emailColumns).map { "אימייל $it" } +
            listOf("ארגון", "וואטסאפ", "שיחה אחרונה")

        val sb = StringBuilder("﻿")
        sb.appendRow(header)
        for (c in contacts) {
            sb.appendRow(
                listOf(text(c.displayName.orEmpty())) +
                    (0 until phoneColumns).map { c.phones.getOrNull(it)?.number?.let(::phone).orEmpty() } +
                    (0 until emailColumns).map { text(c.emails.getOrNull(it)?.address.orEmpty()) } +
                    listOf(
                        text(c.organization.orEmpty()),
                        if (c.onWhatsApp) "כן" else "",
                        c.lastCall?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(date) }.orEmpty(),
                    )
            )
        }
        return sb.toString()
    }

    /**
     * Free text from contacts is untrusted: a cell starting with = + - @ (or
     * a tab/CR) would run as a formula in Excel, so it gets a leading quote.
     */
    internal fun text(value: String): String =
        if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

    /** Numbers keep only digits, spaces, dashes, parentheses and a leading +; anything else is treated as text. */
    internal fun phone(value: String): String =
        if (value.all { it.isDigit() || it in " -()+" } && value.indexOf('+', 1) < 0) value else text(value)

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.joinTo(this, ",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
        }
        append("\r\n")
    }
}
