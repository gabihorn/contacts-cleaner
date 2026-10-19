package app.contactscleaner.me

import android.content.Context
import app.contactscleaner.core.me.MePerson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How the queued ME contacts are added to the phone. */
data class ImportSettings(
    val prefix: String = "ME - ",
    /** Adds "saved you as …" as a note on the new contact. Off unless the user asks. */
    val addNote: Boolean = false,
    /** Contacts added per daily batch. */
    val batchSize: Int = 20,
    val paused: Boolean = false,
)

/**
 * The last ME fetch and the import queue. Kept in the app's private storage
 * only; no tokens are ever stored.
 */
data class MeData(
    val fetchedAt: Long,
    val received: Int,
    val rejected: Int,
    val maybePartial: Boolean,
    val people: List<MePerson>,
    /** Phones (E.164) waiting to be added, in order. */
    val queue: List<String> = emptyList(),
    val settings: ImportSettings = ImportSettings(),
    val lastBatchAt: Long? = null,
    val imported: Int = 0,
)

class MeStore(context: Context) {
    private val file = File(context.filesDir, "me.json")

    @Synchronized
    fun load(): MeData? {
        if (!file.exists()) return null
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    fun save(data: MeData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(data).toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun update(change: (MeData) -> MeData): MeData? = load()?.let(change)?.also(::save)

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun toJson(d: MeData) = JSONObject()
        .put("fetchedAt", d.fetchedAt)
        .put("received", d.received)
        .put("rejected", d.rejected)
        .put("maybePartial", d.maybePartial)
        .put("people", JSONArray().apply {
            d.people.forEach { p ->
                put(JSONObject()
                    .put("phone", p.phone)
                    .put("first", p.firstName ?: JSONObject.NULL)
                    .put("last", p.lastName ?: JSONObject.NULL)
                    .put("savedYouAs", JSONArray(p.savedYouAs))
                    .put("unsaved", p.unsavedInMe))
            }
        })
        .put("queue", JSONArray(d.queue))
        .put("settings", JSONObject()
            .put("prefix", d.settings.prefix)
            .put("addNote", d.settings.addNote)
            .put("batchSize", d.settings.batchSize)
            .put("paused", d.settings.paused))
        .put("lastBatchAt", d.lastBatchAt ?: JSONObject.NULL)
        .put("imported", d.imported)

    private fun fromJson(o: JSONObject): MeData {
        val s = o.optJSONObject("settings") ?: JSONObject()
        return MeData(
            fetchedAt = o.getLong("fetchedAt"),
            received = o.optInt("received"),
            rejected = o.optInt("rejected"),
            maybePartial = o.optBoolean("maybePartial"),
            people = o.getJSONArray("people").let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    MePerson(
                        phone = p.getString("phone"),
                        firstName = p.optStringOrNull("first"),
                        lastName = p.optStringOrNull("last"),
                        savedYouAs = p.getJSONArray("savedYouAs").let { s -> (0 until s.length()).map(s::getString) },
                        unsavedInMe = p.getBoolean("unsaved"),
                    )
                }
            },
            queue = o.optJSONArray("queue")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            settings = ImportSettings(
                prefix = s.optString("prefix", "ME - "),
                addNote = s.optBoolean("addNote"),
                batchSize = s.optInt("batchSize", 20),
                paused = s.optBoolean("paused"),
            ),
            lastBatchAt = if (o.isNull("lastBatchAt")) null else o.optLong("lastBatchAt"),
            imported = o.optInt("imported"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
