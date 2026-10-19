package app.contactscleaner.data

import app.contactscleaner.core.DataRow
import app.contactscleaner.core.RawContact
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A raw contact snapshot as JSON, for the change log and the archive. */
object RawContactJson {
    fun toJson(raw: RawContact) = JSONObject()
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

    fun fromJson(o: JSONObject) = RawContact(
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
}
