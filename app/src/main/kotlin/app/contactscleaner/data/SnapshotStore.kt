package app.contactscleaner.data

import android.util.JsonReader
import android.util.JsonToken
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Full copies of raw contacts (photos included), one file per key. They can
 * be large, so they are kept out of the lists the app loads all the time
 * and read only when needed: to undo a run or restore from the archive.
 */
class SnapshotStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun put(key: String, raws: List<RawContact>) {
        val array = JSONArray()
        raws.forEach { array.put(RawContactJson.toJson(it)) }
        writeAtomically(File(dir, "$key.json"), array.toString())
    }

    fun get(key: String): List<RawContact> {
        val file = File(dir, "$key.json")
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { RawContactJson.fromJson(array.getJSONObject(it)) }
    }

    fun size(key: String): Long = File(dir, "$key.json").length()

    fun delete(key: String) {
        File(dir, "$key.json").delete()
    }

    /** Deletes every snapshot whose key isn't in [keep]. */
    fun retainOnly(keep: Set<String>) {
        dir.listFiles()?.filter { it.name.removeSuffix(".json") !in keep }?.forEach { it.delete() }
    }
}

internal fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    tmp.renameTo(file)
}

/**
 * Reads a JSON array of objects one object at a time, so a large file from an
 * older version can be converted without loading all of it at once.
 */
internal object JsonStream {
    fun forEachObject(file: File, block: (JSONObject) -> Unit) {
        JsonReader(file.bufferedReader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) block(readObject(reader))
            reader.endArray()
        }
    }

    private fun readObject(reader: JsonReader): JSONObject {
        val o = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) o.put(reader.nextName(), readValue(reader))
        reader.endObject()
        return o
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        JsonToken.BEGIN_ARRAY -> JSONArray().also { a ->
            reader.beginArray()
            while (reader.hasNext()) a.put(readValue(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> JSONObject.NULL.also { reader.nextNull() }
        else -> error("Unexpected JSON token ${reader.peek()}")
    }
}
