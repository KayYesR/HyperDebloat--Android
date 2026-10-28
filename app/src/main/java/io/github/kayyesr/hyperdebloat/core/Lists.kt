package io.github.kayyesr.hyperdebloat.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Community lists: descriptions and cross-check only (Get-CommunityLists / Build-Index). */
object CommunityLists {

    val URLS = linkedMapOf(
        "uad" to "https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json",
        "fadeltd" to "https://raw.githubusercontent.com/fadeltd/xiaomi-debloater/main/bloatware.json",
        "ovsky" to "https://raw.githubusercontent.com/ovsky/hyperos-debloater/main/data/config/services.json",
    )

    /** Downloads each list fresh, keeping the last saved copy when the download fails. */
    fun load(storage: Storage, fetcher: Fetcher, log: Log): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((name, url) in URLS) {
            val file = "cache/$name.json"
            try {
                val text = fetcher.fetch(url)
                Json.parse(text) // only replace the cache with valid JSON
                storage.writeText(file, text)
                log.line("$name list: updated")
            } catch (e: Exception) {
                if (storage.exists(file)) {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(storage.lastModified(file)))
                    log.line("! $name list: could not update, using saved copy from $date")
                } else {
                    log.line("! $name list: not available - fewer descriptions and cross-checks")
                    continue
                }
            }
            try {
                result[name] = Json.parse(storage.readText(file) ?: continue)
            } catch (e: Exception) {
                log.line("! $name list: saved copy is damaged, skipped")
            }
        }
        if (result.isEmpty()) log.line("! No community list could be loaded. Ratings will rely on the phone only and nothing can be GREEN.")
        return result
    }

    private val uadMap = mapOf("recommended" to 1, "advanced" to 2, "expert" to 3, "unsafe" to 4)
    private val fadMap = mapOf("safe" to 1, "caution" to 2, "danger" to 4)
    // phase 2 = ordinary user-facing apps
    private val ovsMap = mapOf("phase1_safe" to 1, "phase2_advanced" to 1, "phase3_risky" to 3, "phase4_hidden" to 3, "restore_only" to 4)

    fun buildIndex(lists: Map<String, Any?>): ListIndex {
        val idx = ListIndex()
        lists["uad"].asMap()?.let { u ->
            for ((k, e) in u) {
                val m = e.asMap() ?: continue
                val rating = m["removal"].asText()
                val tier = uadMap[rating.lowercase()] ?: 2
                val desc = m["description"].asText().split("\n")[0]
                idx.uad[k.lowercase()] = ListEntry(tier, rating, desc)
            }
        }
        lists["fadeltd"].asMap()?.get("packages").asList()?.let { pkgs ->
            for (p in pkgs) {
                val m = p.asMap() ?: continue
                val risk = m["risk"].asText()
                val tier = fadMap[risk.lowercase()] ?: 2
                idx.fadeltd[m["package"].asText().lowercase()] = ListEntry(tier, risk, m["description"].asText())
            }
        }
        lists["ovsky"].asMap()?.let { o ->
            o["phases"].asMap()?.let { phases ->
                for ((ph, pkgs) in phases) {
                    val tier = ovsMap[ph.lowercase()] ?: 2
                    for (pkg in pkgs.asList() ?: emptyList()) idx.ovsky[pkg.asText().lowercase()] = ListEntry(tier, ph, "")
                }
            }
            o["descriptions"].asMap()?.let { d -> for ((k, v) in d) idx.ovskyDesc[k.lowercase()] = v.asText() }
        }
        return idx
    }
}
