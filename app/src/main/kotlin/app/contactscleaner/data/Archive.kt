package app.contactscleaner.data

import android.content.Context
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A contact moved out of the phone's address book into the app. [raws] is
 * everything it held, so restoring brings it back whole. [runId] links it to
 * the change-log run that archived it.
 */
data class ArchivedContact(
    val id: Long,
    val title: String,
    val archivedAt: Long,
    val runId: Long,
    val raws: List<RawContact>,
)

/** The archive, in a JSON file in the app's private storage. */
class Archive(context: Context) {
    private val file = File(context.filesDir, "archive.json")

    @Synchronized
    fun all(): List<ArchivedContact> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    fun add(items: List<ArchivedContact>) = save(items + all())

    @Synchronized
    fun remove(ids: Set<Long>) = save(all().filterNot { it.id in ids })

    /** Undoing the run that archived them puts them back in the phone, so they leave the archive. */
    @Synchronized
    fun removeRun(runId: Long) = save(all().filterNot { it.runId == runId })

    private fun save(items: List<ArchivedContact>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun toJson(a: ArchivedContact) = JSONObject()
        .put("id", a.id)
        .put("title", a.title)
        .put("archivedAt", a.archivedAt)
        .put("runId", a.runId)
        .put("raws", JSONArray().apply { a.raws.forEach { put(RawContactJson.toJson(it)) } })

    private fun fromJson(o: JSONObject) = ArchivedContact(
        id = o.getLong("id"),
        title = o.getString("title"),
        archivedAt = o.getLong("archivedAt"),
        runId = o.getLong("runId"),
        raws = o.getJSONArray("raws").let { a -> (0 until a.length()).map { RawContactJson.fromJson(a.getJSONObject(it)) } },
    )
}
