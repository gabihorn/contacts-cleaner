package app.contactscleaner.data

import android.content.Context
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    /** What a fresh scan found right after the run, e.g. "אומתו 12 מתוך 12". */
    val verification: String? = null,
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
    fun update(run: ChangeRun) = save(all().map { if (it.id == run.id) run else it })

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
        .put("before", JSONArray().apply { run.before.forEach { put(RawContactJson.toJson(it)) } })
        .put("created", JSONArray(run.created))
        .put("verification", run.verification ?: JSONObject.NULL)

    private fun runFromJson(o: JSONObject) = ChangeRun(
        id = o.getLong("id"),
        time = o.getLong("time"),
        title = o.getString("title"),
        lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } },
        before = o.getJSONArray("before").let { a -> (0 until a.length()).map { RawContactJson.fromJson(a.getJSONObject(it)) } },
        undone = o.optBoolean("undone"),
        created = o.optJSONArray("created")?.let { a -> (0 until a.length()).map(a::getLong) }.orEmpty(),
        verification = if (o.isNull("verification")) null else o.optString("verification"),
    )

    private companion object {
        const val MAX_RUNS = 30
    }
}
