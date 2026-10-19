package app.contactscleaner.data

import android.content.Context
import app.contactscleaner.core.DataRow
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * One "fix" run: what the user saw ([lines]) and the state of every raw
 * contact it touched just before ([before]), which is what undo restores.
 */
data class ChangeRun(
    val id: Long,
    val time: Long,
    val title: String,
    val lines: List<String>,
    val before: List<RawContact>,
    val undone: Boolean = false,
    /** Raw contacts this run added; undo deletes them. */
    val created: List<Long> = emptyList(),
)

/** Keeps the latest runs in a JSON file in the app's private storage. */
class ChangeLog(context: Context) {
    private val file = File(context.filesDir, "changelog.json")

    @Synchronized
    fun all(): List<ChangeRun> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { runFromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    fun add(run: ChangeRun) = save(listOf(run) + all())

    @Synchronized
    fun markUndone(id: Long) = save(all().map { if (it.id == id) it.copy(undone = true) else it })

    /** Undo goes one step at a time, newest first, so a run never overwrites a later one. */
    fun undoable(): ChangeRun? = all().firstOrNull { !it.undone }

    private fun save(runs: List<ChangeRun>) {
        val array = JSONArray()
        runs.take(MAX_RUNS).forEach { array.put(runToJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun runToJson(run: ChangeRun) = JSONObject()
        .put("id", run.id)
        .put("time", run.time)
        .put("title", run.title)
        .put("lines", JSONArray(run.lines))
        .put("undone", run.undone)
        .put("before", JSONArray().apply { run.before.forEach { put(rawToJson(it)) } })
        .put("created", JSONArray(run.created))

    private fun runFromJson(o: JSONObject) = ChangeRun(
        id = o.getLong("id"),
        time = o.getLong("time"),
        title = o.getString("title"),
        lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } },
        before = o.getJSONArray("before").let { a -> (0 until a.length()).map { rawFromJson(a.getJSONObject(it)) } },
        undone = o.optBoolean("undone"),
        created = o.optJSONArray("created")?.let { a -> (0 until a.length()).map(a::getLong) }.orEmpty(),
    )

    private fun rawToJson(raw: RawContact) = JSONObject()
        .put("id", raw.id)
        .put("contactId", raw.contactId)
        .put("accountType", raw.accountType ?: JSONObject.NULL)
        .put("accountName", raw.accountName ?: JSONObject.NULL)
        .put("dataSet", raw.dataSet ?: JSONObject.NULL)
        .put("rows", JSONArray().apply {
            raw.rows.forEach { row ->
                put(JSONObject()
                    .put("id", row.id)
                    .put("mime", row.mimeType)
                    .put("values", JSONObject().apply { row.values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
                    .put("blob", row.blob?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL))
            }
        })

    private fun rawFromJson(o: JSONObject) = RawContact(
        id = o.getLong("id"),
        contactId = o.getLong("contactId"),
        accountType = o.optStringOrNull("accountType"),
        accountName = o.optStringOrNull("accountName"),
        dataSet = o.optStringOrNull("dataSet"),
        rows = o.getJSONArray("rows").let { a ->
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                val values = r.getJSONObject("values")
                DataRow(
                    id = r.getLong("id"),
                    mimeType = r.getString("mime"),
                    values = values.keys().asSequence().associateWith { values.optStringOrNull(it) },
                    blob = r.optStringOrNull("blob")?.let { Base64.getDecoder().decode(it) },
                )
            }
        },
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private companion object {
        const val MAX_RUNS = 30
    }
}
