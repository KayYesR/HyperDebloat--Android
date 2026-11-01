package io.github.kayyesr.hyperdebloat.core

/**
 * Keeps the last check's results, so leaving the app (or Android closing it in the background) does not
 * throw them away. Saved per phone and OS build; dropped after anything is removed or restored.
 */
object ScanCache {
    private const val FILE = "last_scan.json"
    /** Older results are checked again, since apps may have been installed or updated since. */
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    class Saved(val deviceKey: String, val time: Long, val shownTime: String, val rows: List<Row>)

    fun save(storage: Storage, deviceKey: String, time: Long, shownTime: String, rows: List<Row>) {
        val sb = StringBuilder()
        sb.append("{\"deviceKey\":").append(Json.quote(deviceKey))
            .append(",\"time\":").append(Json.quote(time.toString()))
            .append(",\"shown\":").append(Json.quote(shownTime))
            .append(",\"rows\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{")
            val fields = listOf(
                "tier" to r.tier.code.toString(), "risk" to r.risk, "pkg" to r.pkg, "description" to r.description,
                "phone" to r.phone, "lists" to r.lists, "reason" to r.reason, "userApp" to r.userApp.toString(),
                "category" to r.category, "ordinary" to r.ordinary.toString(), "atSetup" to r.atSetup.toString(),
                "locked" to r.locked.toString(), "note" to r.note,
            )
            fields.forEachIndexed { j, (k, v) ->
                if (j > 0) sb.append(',')
                sb.append(Json.quote(k)).append(':').append(Json.quote(v))
            }
            sb.append("}")
        }
        sb.append("]}")
        storage.writeText(FILE, sb.toString())
    }

    /** The saved results for [deviceKey], or null if there are none, they are for another phone, or too old. */
    fun load(storage: Storage, deviceKey: String, now: Long = System.currentTimeMillis()): Saved? {
        return try {
            val text = storage.readText(FILE) ?: return null
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(text) as? Map<String, Any?> ?: return null
            if (m["deviceKey"] != deviceKey) return null
            val time = (m["time"] as? String)?.toLongOrNull() ?: return null
            if (now - time !in 0..MAX_AGE_MS) return null
            @Suppress("UNCHECKED_CAST")
            val rows = (m["rows"] as? List<Map<String, Any?>> ?: return null).map { o ->
                fun s(k: String) = o[k] as? String ?: ""
                fun b(k: String) = s(k) == "true"
                Row(
                    tier = Tier.of(s("tier").toIntOrNull() ?: 0), risk = s("risk"), pkg = s("pkg"),
                    description = s("description"), phone = s("phone"), lists = s("lists"), reason = s("reason"),
                    userApp = b("userApp"), category = s("category"), ordinary = b("ordinary"), atSetup = b("atSetup"),
                    locked = b("locked"), note = s("note"),
                )
            }
            if (rows.isEmpty()) null else Saved(deviceKey, time, m["shown"] as? String ?: "", rows)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(storage: Storage) = storage.delete(FILE)
}
