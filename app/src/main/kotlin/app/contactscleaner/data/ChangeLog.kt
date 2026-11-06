package app.contactscleaner.data

import android.content.Context
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One "fix" run: what the user saw ([lines]) and the state of every raw
 * contact it touched just before ([before]), which is what undo restores.
 * Runs from [ChangeLog.all] come without [before]; [ChangeLog.withSnapshot]
 * loads it.
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

/**
 * The latest runs, in the app's private storage: a small index of what each
 * run did, and each run's snapshot in its own file. Snapshots hold whole
 * contacts with photos, so a big run can be many megabytes; keeping them out
 * of the index means the app never loads more than one at a time.
 */
class ChangeLog(context: Context) {
    private val dir = File(context.filesDir, "changelog")
    private val index = File(dir, "index.json")
    private val snapshots = SnapshotStore(File(dir, "snapshots"))

    init {
        migrate(File(context.filesDir, "changelog.json"))
    }

    @Synchronized
    fun all(): List<ChangeRun> {
        if (!index.exists()) return emptyList()
        val array = runCatching { JSONArray(index.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { runFromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    fun add(run: ChangeRun) {
        snapshots.put(run.id.toString(), run.before)
        save(listOf(run) + all())
    }

    /** Updates what the run says (e.g. its verification); its snapshot stays as it was. */
    @Synchronized
    fun update(run: ChangeRun) = save(all().map { if (it.id == run.id) run else it })

    @Synchronized
    fun markUndone(id: Long) = save(all().map { if (it.id == id) it.copy(undone = true) else it })

    /** [run] with its snapshot, for undo. */
    fun withSnapshot(run: ChangeRun): ChangeRun = run.copy(before = snapshots.get(run.id.toString()))

    private fun save(runs: List<ChangeRun>) {
        // Newest first, as many as fit the count and the space for snapshots.
        var bytes = 0L
        val kept = runs.take(MAX_RUNS).takeWhile { run ->
            bytes += snapshots.size(run.id.toString())
            bytes <= MAX_SNAPSHOT_BYTES || run === runs.first()
        }
        val array = JSONArray()
        kept.forEach { array.put(runToJson(it)) }
        dir.mkdirs()
        writeAtomically(index, array.toString())
        snapshots.retainOnly(kept.map { it.id.toString() }.toSet())
    }

    /** Moves an older version's single-file log into this layout, one run at a time. */
    private fun migrate(legacy: File) {
        if (!legacy.exists()) return
        try {
            val runs = mutableListOf<ChangeRun>()
            JsonStream.forEachObject(legacy) { o ->
                val run = runFromJson(o)
                val before = o.optJSONArray("before")
                snapshots.put(run.id.toString(), before?.let { a -> (0 until a.length()).map { RawContactJson.fromJson(a.getJSONObject(it)) } }.orEmpty())
                runs += run
            }
            save(runs)
            legacy.delete()
        } catch (_: Throwable) {
            // Unreadable or too big even one run at a time: set it aside rather than fail every start.
            legacy.renameTo(File(legacy.parentFile, legacy.name + ".old"))
        }
    }

    private fun runToJson(run: ChangeRun) = JSONObject()
        .put("id", run.id)
        .put("time", run.time)
        .put("title", run.title)
        .put("lines", JSONArray(run.lines))
        .put("undone", run.undone)
        .put("created", JSONArray(run.created))
        .put("verification", run.verification ?: JSONObject.NULL)

    private fun runFromJson(o: JSONObject) = ChangeRun(
        id = o.getLong("id"),
        time = o.getLong("time"),
        title = o.getString("title"),
        lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } },
        before = emptyList(),
        undone = o.optBoolean("undone"),
        created = o.optJSONArray("created")?.let { a -> (0 until a.length()).map(a::getLong) }.orEmpty(),
        verification = if (o.isNull("verification")) null else o.optString("verification"),
    )

    private companion object {
        /** A fix over thousands of items is saved in parts of 200; this keeps all of them undoable. */
        const val MAX_RUNS = 200
        const val MAX_SNAPSHOT_BYTES = 300L * 1024 * 1024
    }
}
