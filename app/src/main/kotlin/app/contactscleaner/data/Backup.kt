package app.contactscleaner.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract.Contacts

/**
 * Exports every contact as one .vcf file. Importing that file in the Contacts
 * app restores them.
 */
class Backup(private val resolver: ContentResolver) {

    /** Writes the backup to [target] and returns how many contacts it holds. */
    fun exportTo(target: Uri): Int {
        val lookupKeys = mutableListOf<String>()
        resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { lookupKeys += it }
        }

        val out = resolver.openOutputStream(target) ?: error("Can't write to $target")
        var count = 0
        out.use { stream ->
            for (key in lookupKeys) {
                val vcard = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, Uri.encode(key))
                // A contact can vanish between the two queries; skip it.
                val input = runCatching { resolver.openInputStream(vcard) }.getOrNull() ?: continue
                input.use { it.copyTo(stream) }
                count++
            }
        }
        return count
    }
}
