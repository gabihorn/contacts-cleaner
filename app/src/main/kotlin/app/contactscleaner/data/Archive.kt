package app.contactscleaner.data

import android.content.Context
import app.contactscleaner.core.MimeTypes
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A contact moved out of the phone's address book into the app. [raws] is
 * everything it held, so restoring brings it back whole; items from
 * [Archive.all] come without it, and [Archive.withRaws] loads it. [runId]
 * links it to the change-log run that archived it.
 */
data class ArchivedContact(
    val id: Long,
    val title: String,
    val archivedAt: Long,
    val runId: Long,
    /** For the list on screen, so it needs no snapshot. */
    val phones: List<String> = emptyList(),
    val raws: List<RawContact> = emptyList(),
) {
    internal val key get() = "$runId-$id"
}

/** The archive: a small index file, and each contact's snapshot in its own file. */
class Archive(context: Context) {
    private val dir = File(context.filesDir, "archive")
    private val index = File(dir, "index.json")
    private val snapshots = SnapshotStore(File(dir, "snapshots"))

    init {
        migrate(File(context.filesDir, "archive.json"))
    }

    @Synchronized
    fun all(): List<ArchivedContact> {
        if (!index.exists()) return emptyList()
        val array = runCatching { JSONArray(index.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    fun add(items: List<ArchivedContact>) {
        val withPhones = items.map { it.copy(phones = phonesOf(it.raws)) }
        withPhones.forEach { snapshots.put(it.key, it.raws) }
        save(withPhones + all())
    }

    @Synchronized
    fun remove(ids: Set<Long>) = save(all().filterNot { it.id in ids })

    /** Undoing the run that archived them puts them back in the phone, so they leave the archive. */
    @Synchronized
    fun removeRun(runId: Long) = save(all().filterNot { it.runId == runId })

    /** [items] with their snapshots, for restoring. */
    fun withRaws(items: List<ArchivedContact>) = items.map { it.copy(raws = snapshots.get(it.key)) }

    private fun save(items: List<ArchivedContact>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        dir.mkdirs()
        writeAtomically(index, array.toString())
        snapshots.retainOnly(items.map { it.key }.toSet())
    }

    /** Moves an older version's single-file archive into this layout, one contact at a time. */
    private fun migrate(legacy: File) {
        if (!legacy.exists()) return
        try {
            val items = mutableListOf<ArchivedContact>()
            JsonStream.forEachObject(legacy) { o ->
                val raws = o.optJSONArray("raws")?.let { a -> (0 until a.length()).map { RawContactJson.fromJson(a.getJSONObject(it)) } }.orEmpty()
                val item = fromJson(o).copy(phones = phonesOf(raws))
                snapshots.put(item.key, raws)
                items += item
            }
            save(items)
            legacy.delete()
        } catch (_: Throwable) {
            legacy.renameTo(File(legacy.parentFile, legacy.name + ".old"))
        }
    }

    private fun phonesOf(raws: List<RawContact>) =
        raws.flatMap { r -> r.rows.filter { it.mimeType == MimeTypes.PHONE }.mapNotNull { it.data1 } }.distinct()

    private fun toJson(a: ArchivedContact) = JSONObject()
        .put("id", a.id)
        .put("title", a.title)
        .put("archivedAt", a.archivedAt)
        .put("runId", a.runId)
        .put("phones", JSONArray(a.phones))

    private fun fromJson(o: JSONObject) = ArchivedContact(
        id = o.getLong("id"),
        title = o.getString("title"),
        archivedAt = o.getLong("archivedAt"),
        runId = o.getLong("runId"),
        phones = o.optJSONArray("phones")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
    )
}
