package app.contactscleaner.core

/**
 * One account's copy of a contact (Android's "raw contact") with every data
 * row it holds. Used to merge contacts for real and to snapshot them so a
 * change can be undone.
 */
data class RawContact(
    val id: Long,
    val contactId: Long,
    val accountType: String?,
    val accountName: String?,
    val dataSet: String? = null,
    val rows: List<DataRow> = emptyList(),
)

/**
 * A data row: [values] holds data1..data14 by column name ("data1" …), and
 * [blob] holds data15 (the photo, for photo rows).
 */
data class DataRow(
    val id: Long,
    val mimeType: String,
    val values: Map<String, String?>,
    val blob: ByteArray? = null,
) {
    val data1: String? get() = values["data1"]

    override fun equals(other: Any?) = other is DataRow && id == other.id && mimeType == other.mimeType &&
        values == other.values && blob.contentEquals(other.blob)

    override fun hashCode() = id.hashCode()
}

/** Android's CommonDataKinds mime types. Only these are copied or restored; app-specific rows are left alone. */
object MimeTypes {
    const val NAME = "vnd.android.cursor.item/name"
    const val PHONE = "vnd.android.cursor.item/phone_v2"
    const val EMAIL = "vnd.android.cursor.item/email_v2"
    const val PHOTO = "vnd.android.cursor.item/photo"
    const val ORGANIZATION = "vnd.android.cursor.item/organization"
    const val GROUP = "vnd.android.cursor.item/group_membership"
    const val NICKNAME = "vnd.android.cursor.item/nickname"
    const val NOTE = "vnd.android.cursor.item/note"
    const val ADDRESS = "vnd.android.cursor.item/postal-address_v2"
    const val WEBSITE = "vnd.android.cursor.item/website"
    const val EVENT = "vnd.android.cursor.item/contact_event"
    const val RELATION = "vnd.android.cursor.item/relation"
    const val IM = "vnd.android.cursor.item/im"
    const val SIP = "vnd.android.cursor.item/sip_address"

    val STANDARD = setOf(NAME, PHONE, EMAIL, PHOTO, ORGANIZATION, GROUP, NICKNAME, NOTE, ADDRESS, WEBSITE, EVENT, RELATION, IM, SIP)

    const val GOOGLE_ACCOUNT = "com.google"
}
