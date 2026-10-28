package io.github.kayyesr.hyperdebloat.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Rule-based classifier (Get-PhoneVerdict, Get-Category, Get-Rating, Get-OrdinaryRow, Get-Classification). */
object Classifier {

    /** Everyday apps that are never ticked for you, even when rated GREEN or when the phone shows no icon for them. */
    val KEEP_UNTICKED = setOf(
        "com.google.android.youtube",      // YouTube
        "com.android.chrome",              // Google Chrome
        "com.google.ar.lens",              // Google Lens
        "com.google.android.apps.tachyon", // Google Meet
        "com.whatsapp",                    // WhatsApp
        "com.instagram.android",           // Instagram
        "com.google.android.apps.docs",    // Google Drive
        "com.google.android.gm",           // Gmail
    )

    /**
     * Android version only: packages HyperDebloat needs while it runs on the phone. Removing one of these
     * mid-run would cut the wireless debugging connection or the pairing screen, so they can never be ticked.
     */
    val NEEDED_ON_PHONE = mapOf(
        "com.android.settings" to "holds the Wireless debugging switch and pairing screen",
        "com.android.shell" to "runs the commands HyperDebloat sends over wireless debugging",
        "com.android.systemui" to "shows the pairing notification",
        "com.android.packageinstaller" to "shows the phone's own uninstall screen",
        "com.google.android.packageinstaller" to "shows the phone's own uninstall screen",
        "com.miui.packageinstaller" to "shows the phone's own uninstall screen",
        "com.android.networkstack" to "keeps the Wi-Fi connection that wireless debugging runs over",
        "com.google.android.networkstack" to "keeps the Wi-Fi connection that wireless debugging runs over",
        "com.android.networkstack.tethering" to "keeps the Wi-Fi connection that wireless debugging runs over",
        "com.google.android.networkstack.tethering" to "keeps the Wi-Fi connection that wireless debugging runs over",
        "com.android.wifi.resources" to "keeps the Wi-Fi connection that wireless debugging runs over",
        "com.google.android.wifi.resources" to "keeps the Wi-Fi connection that wireless debugging runs over",
    )

    private val I = setOf(RegexOption.IGNORE_CASE)

    class Verdict(
        val tier: Int,
        val why: String,
        val hasHard: Boolean,
        val inUse: Boolean,
        val strong: Boolean,
        val hardware: Boolean,
        val hasIcon: Boolean,
    )

    private fun isHardwarePartition(p: String) = p.equals("vendor", true) || p.equals("odm", true)

    fun phoneVerdict(f: PackageFacts, isSystem: Boolean): Verdict {
        // Only real dependencies affect the rating. How a package is built or signed (platform key,
        // privileged, no icon, system user) is shown for information but does not count against it:
        // on Xiaomi phones nearly every package, junk included, looks like that.
        val hard = ArrayList<String>() // RED: Android itself relies on it
        val dep = ArrayList<String>()  // something relies on it
        val info = ArrayList<String>() // context only
        var strong = false

        if (f.persistent) hard.add("persistent (Android keeps it running)")
        hard.addAll(f.roles)

        if (f.usedBy.isNotEmpty()) {
            val shown = f.usedBy.take(3).joinToString(", ")
            val more = if (f.usedBy.size > 3) " +${f.usedBy.size - 3} more" else ""
            dep.add("in use right now by: $shown$more"); strong = true
        }
        if (f.overlayTarget.isNotEmpty() && f.overlayState == "enabled" &&
            listOf("android", "com.android.systemui", "com.android.settings").any { it.equals(f.overlayTarget, true) }
        ) {
            dep.add("active overlay on ${f.overlayTarget}"); strong = true
        }
        if (f.permHolders.isNotEmpty()) {
            val shown = f.permHolders.take(3).joinToString(", ")
            val more = if (f.permHolders.size > 3) " +${f.permHolders.size - 3} more" else ""
            dep.add("${f.permHolders.size} app(s) hold its permissions: $shown$more")
            if (f.permHolders.size >= 5) strong = true
        }

        if (Regex("^android\\.uid\\.", I).containsMatchIn(f.sharedUser)) info.add("runs as ${f.sharedUser}")
        // Installed with the hardware drivers: never rated green, even if nothing visibly depends on it.
        if (isHardwarePartition(f.partition)) dep.add("installed with the hardware drivers (${f.partition} partition)")
        if (isSystem) {
            if (f.platformSigned) info.add("platform-signed")
            if (f.privileged) info.add("privileged")
            if (!f.hasIcon) info.add("no app icon")
        }

        val tier = when {
            hard.isNotEmpty() -> 4
            strong -> 3
            dep.isNotEmpty() -> 2
            else -> 1
        }
        var why = hard + dep
        if (why.isEmpty()) why = listOf("nothing depends on it")
        val text = why.joinToString("; ") + if (info.isNotEmpty()) "  [info: ${info.joinToString(", ")}]" else ""
        return Verdict(tier, text, hard.isNotEmpty(), f.usedBy.isNotEmpty(), strong, isHardwarePartition(f.partition), f.hasIcon)
    }

    private val rAds = Regex("^(com\\.miui\\.(msa|analytics|daemon|bugreport|hybrid|systemAdSolution|contentcatcher)|com\\.miui\\.android\\.fashiongallery|com\\.mfashiongallery|com\\.glance|com\\.facebook\\.(appmanager|services|system))(\\.|$)", I)
    private val rAdsDesc = Regex("telemetry|ad-serving|advertis|collects usage data", I)
    private val rXiaomiOrAndroid = Regex("^(android|com\\.android|com\\.google|com\\.miui|com\\.xiaomi|com\\.mi|com\\.mipay|miui|com\\.lbe|com\\.fido|com\\.milink|com\\.duokan|org\\.chromium|org\\.ifaa|cn\\.wps|com\\.unionpay|com\\.gallery|com\\.qualcomm|com\\.qti|org\\.codeaurora|vendor|com\\.mediatek|com\\.mtk|com\\.fingerprints|com\\.goodix|com\\.huaqin|com\\.tencent\\.soter|com\\.debug|com\\.wapi|com\\.wdstechnology|com\\.example)(\\.|$)", I)
    private val rTheme = Regex("(^|\\.)(overlay|theme|icon_pack|navbar|cutout)(\\.|$)|overlay$", I)
    private val rHardware = Regex("^(com\\.qualcomm|com\\.qti|org\\.codeaurora|vendor|com\\.mediatek|com\\.mtk|com\\.fingerprints|com\\.goodix|com\\.huaqin|com\\.tencent\\.soter|com\\.debug|com\\.wapi)(\\.|$)", I)
    private val rGoogle = Regex("^com\\.google\\.|^com\\.android\\.(vending|chrome)$", I)
    private val rAndroid = Regex("^(android|com\\.android)(\\.|$)", I)

    /** A plain label for what kind of package this is. It helps people find things; it does not change the rating. */
    fun category(pkg: String, desc: String, icon: Boolean, partition: String): String {
        if (rAds.containsMatchIn(pkg) || rAdsDesc.containsMatchIn(desc)) return "Ads & tracking"
        if (!rXiaomiOrAndroid.containsMatchIn(pkg)) return "Third-party preloads"
        if (rTheme.containsMatchIn(pkg)) return "Themes & overlays"
        if (isHardwarePartition(partition) || rHardware.containsMatchIn(pkg)) return "Hardware & drivers"
        if (rGoogle.containsMatchIn(pkg)) return if (icon) "Google apps" else "Google services"
        if (icon) return "Xiaomi & phone apps"
        if (rAndroid.containsMatchIn(pkg)) return "Android system"
        return "Xiaomi services"
    }

    private fun sources(pkg: String, idx: ListIndex): LinkedHashMap<String, ListEntry> {
        val k = pkg.lowercase()
        val src = LinkedHashMap<String, ListEntry>()
        idx.uad[k]?.let { src["UAD-ng"] = it }
        idx.fadeltd[k]?.let { src["fadeltd"] = it }
        idx.ovsky[k]?.let { src["ovsky"] = it }
        return src
    }

    private fun listsSay(src: Map<String, ListEntry>) =
        if (src.isNotEmpty()) src.entries.joinToString(" | ") { "${it.key}: ${it.value.rating}" } else "not in any list"

    fun rating(pkg: String, isSystem: Boolean, idx: ListIndex, facts: Map<String, PackageFacts>): Row? {
        val src = sources(pkg, idx)
        // Only system apps are rated. A non-system app is handled as an ordinary app (ordinaryRow).
        if (!isSystem) return null

        val desc = idx.describe(pkg)
        val lists = listsSay(src)
        val f = facts[pkg.lowercase()]
        if (f == null || !f.seen) {
            return Row(Tier.FLAGGED, Tier.FLAGGED.label, pkg, desc, "no data", lists,
                "the phone returned no details for this package - not touched", f)
        }

        val pv = phoneVerdict(f, isSystem)
        var tier = pv.tier
        val notes = ArrayList<String>()
        notes.add("phone rates it ${Tier.of(pv.tier).colour}")

        if (src.isNotEmpty()) {
            val tiers = src.values.map { it.tier }
            val listMax = tiers.max()
            val allSafe = tiers.all { it == 1 }

            if (listMax > tier) {
                tier = listMax
                notes.add("raised to ${Tier.of(tier).colour}: a list warns about it")
            } else if (allSafe && !pv.hasHard && tier > 1) {
                if (src.size >= 3) {
                    if (pv.strong || pv.hardware) {
                        tier = 2
                        notes.add("lowered to YELLOW: all three lists say junk, but other apps rely on it, so something may stop working")
                    } else {
                        tier = 1
                        notes.add("lowered to GREEN: all three lists agree it is junk and Android does not rely on it")
                    }
                } else if (src.size == 2 && !pv.inUse && !(pv.hardware && tier <= 2)) {
                    tier -= 1
                    notes.add("lowered to ${Tier.of(tier).colour}: both lists that know it say safe")
                } else {
                    notes.add("lists rate it lower; the phone's evidence wins")
                }
            } else if (listMax < tier) {
                notes.add("lists rate it lower; the phone's evidence wins")
            }
            if (tier == 1 && src.size < 2) {
                tier = 2
                notes.add("raised to YELLOW: only one list knows it, so it cannot be cross-checked")
            }
        } else if (idx.loaded) {
            // Unknown to every list: the scan can say what depends on it, but not what it does.
            tier = 0
            notes.add("not in any list - unknown package, flagged and not touched")
        } else if (tier == 1) {
            tier = 2
            notes.add("raised to YELLOW: lists unavailable, so it cannot be confirmed as junk")
        }

        return Row(
            tier = Tier.of(tier),
            risk = Tier.of(tier).label,
            pkg = pkg,
            description = desc,
            phone = "${Tier.of(pv.tier).colour}: ${pv.why}",
            lists = lists,
            reason = notes.joinToString("; "),
            facts = f,
            userApp = pv.hasIcon || KEEP_UNTICKED.contains(pkg.lowercase()),
            category = category(pkg, desc, pv.hasIcon, f.partition),
        )
    }

    private val installFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun installDate(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text.trim(), installFormat).takeIf { it.year >= 2012 }
    } catch (e: Exception) {
        null
    }

    fun ordinaryRow(pkg: String, idx: ListIndex, facts: Map<String, PackageFacts>, setupTime: LocalDateTime?): Row {
        // Ordinary (non-system) apps, including junk the phone installs during first setup (games and the
        // like). They are never rated green or pre-ticked: the phone cannot tell setup junk from apps the
        // user restored from a backup at the same moment, so the user decides.
        val src = sources(pkg, idx)
        val desc = idx.describe(pkg)
        val f = facts[pkg.lowercase()]
        val whenText = if (f != null && f.installTime.isNotEmpty()) f.installTime else "unknown time"
        val by = if (f != null && f.installer.isNotEmpty() && !f.installer.equals("null", true)) f.installer else "no installer recorded"
        val d = f?.let { installDate(it.installTime) }
        val atSetup = d != null && setupTime != null && !d.isAfter(setupTime.plusHours(2))
        val cat = if (atSetup) "Installed during setup" else "Your apps"
        val reason = if (atSetup) "installed within 2 hours of the first app on this phone: probably preloaded at setup (apps restored from your backup arrive then too, so check it)"
        else "installed later, most likely by you"
        return Row(
            tier = Tier.APP, risk = Tier.APP.label, pkg = pkg, description = desc,
            phone = "ordinary app (not a system app), installed $whenText by $by", lists = listsSay(src), reason = reason,
            facts = f, userApp = true, category = cat, ordinary = true, atSetup = atSetup,
        )
    }

    /** Locks packages HyperDebloat needs while it runs (Android version only). */
    private fun lockIfNeeded(row: Row): Row {
        val why = NEEDED_ON_PHONE[row.pkg.lowercase()] ?: return row
        return row.copy(
            tier = Tier.UNSAFE, risk = Tier.UNSAFE.label, locked = true,
            reason = row.reason + "; locked: HyperDebloat needs it while it runs on the phone ($why)",
        )
    }

    /**
     * Rates every installed package. [selfPackage] (HyperDebloat itself) is left out.
     * Packages already switched off or frozen are left out too; they are listed in Restore.
     */
    fun classify(idx: ListIndex, sets: Scanner.PackageSets, facts: Map<String, PackageFacts>, selfPackage: String): List<Row> {
        // The first ordinary app's install time marks when the phone was set up (system apps carry a build date).
        var setup: LocalDateTime? = null
        for (k in sets.all.keys) {
            if (sets.system.contains(k)) continue
            val f = facts[k] ?: continue
            val d = installDate(f.installTime) ?: continue
            if (setup == null || d.isBefore(setup)) setup = d
        }
        val rows = ArrayList<Row>()
        for ((k, pkg) in sets.all) {
            if (k == selfPackage.lowercase()) continue
            val f = facts[k]
            if (f != null && (f.userDisabled || f.userSuspended)) continue
            val row = if (sets.system.contains(k)) rating(pkg, true, idx, facts) else ordinaryRow(pkg, idx, facts, setup)
            if (row != null) rows.add(lockIfNeeded(row))
        }
        return rows.sortedWith(
            compareBy<Row> { it.tier.sortOrder }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.category }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.pkg }
        )
    }

    /** "12 green, 30 yellow, ..." for the home screen. */
    fun summary(rows: List<Row>): String =
        listOf(Tier.SAFE, Tier.MODERATE, Tier.HIGH, Tier.UNSAFE, Tier.FLAGGED, Tier.APP)
            .joinToString(", ") { t -> "${rows.count { it.tier == t }} ${t.colour.lowercase()}" }
}
