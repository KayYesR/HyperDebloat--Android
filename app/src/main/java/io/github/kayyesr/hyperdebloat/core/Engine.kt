package io.github.kayyesr.hyperdebloat.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Remove, freeze, restore, came-back check and reports, ported from the PowerShell actions.
 * Nothing here asks the user anything; the screens do the asking and call these steps in order.
 */
class Engine(
    private val shell: Shell,
    private val storage: Storage,
    private val log: Log,
    private val sdk: Int,
    private val version: String,
) {
    /** Folder for the connected phone and OS build (Connect-Phone). */
    var deviceDir: String = ""

    private val scanner = Scanner(shell, log)
    private val I = setOf(RegexOption.IGNORE_CASE)

    private fun now(pattern: String) = LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))
    private fun nowS() = now("yyyy-MM-dd'T'HH:mm:ss")
    private fun stamp() = now("yyyy-MM-dd_HHmmss")

    // ------------------------------------------------------------------------------------------
    // Package state on the phone
    // ------------------------------------------------------------------------------------------
    fun packageSet(command: String) = scanner.packageSet(command)

    fun installedForUser(pkg: String) = packageSet("pm list packages --user 0 ${requirePackageName(pkg)}").containsKey(pkg.lowercase())

    private val rSuspendedUser0 = Regex("^\\s*User 0:.*\\binstalled=true\\b.*\\bsuspended=true\\b", I)

    fun suspendedForUser(pkg: String): Boolean =
        shell.run("dumpsys package ${requirePackageName(pkg)}").any { rSuspendedUser0.containsMatchIn(it) }

    /** System apps running an update stored on the phone (from the Play Store or GetApps). */
    fun updatedSystemApps(): Set<String> {
        val set = HashSet<String>()
        // Android 10 and older: "pm uninstall-system-updates" takes no app name and would reset every system app.
        if (sdk < 30) return set
        val r = Regex("^package:(/data/.+)=([A-Za-z][\\w.]*)\\s*$", I)
        for (l in shell.run("pm list packages -s -f --user 0")) r.find(l)?.let { set.add(it.groupValues[2].lowercase()) }
        return set
    }

    /** Last error text from a refused freeze (Script:FreezeError). */
    var freezeError = ""
        private set

    /** Freezes an app in place. HyperOS refuses "pm disable-user" for system apps over ADB, but allows suspend. */
    fun suspendPackage(pkg: String): Boolean {
        requirePackageName(pkg)
        freezeError = shell.text("pm suspend --user 0 $pkg")
        shell.run("am force-stop $pkg")
        return suspendedForUser(pkg)
    }

    fun removeOnePackage(pkg: String, updated: Boolean, noFreeze: Boolean = false): String {
        requirePackageName(pkg)
        // 1. A system app with an update: remove the update first (as Canta does), so the store has
        //    no installed update to refresh, which is how such apps come back.
        var pre = ""
        if (updated) {
            val u = shell.text("pm uninstall-system-updates $pkg")
            if (u.contains("Success", true)) pre = " (update removed first)"
        }
        // 2. Remove it for the user and check the phone really dropped it.
        var res = shell.text("pm uninstall -k --user 0 $pkg")
        if (res.startsWith("Success", true)) {
            if (!installedForUser(pkg)) return "Success$pre"
            res = "Phone said Success, but the app is still installed"
        }
        // 3. Not removable: freeze it instead (UAD-ng disables here; HyperOS only allows suspend).
        if (noFreeze) return res
        if (suspendPackage(pkg)) return "Suspended (could not be removed: $res)"
        return "$res; freezing refused too: $freezeError"
    }

    // ------------------------------------------------------------------------------------------
    // Removal (Remove-Packages), in steps so the screen can ask in between
    // ------------------------------------------------------------------------------------------
    class Result(val time: String, val pkg: String, val risk: String, var result: String, val description: String)

    /** Text shown for one result line. */
    fun shownResult(res: String): String = when {
        res.startsWith("Suspended", true) -> "frozen (suspended): the phone would not remove it, so it cannot open and its notifications are hidden"
        res.contains("Failure [-1000]") -> "$res - blocked by MIUI/HyperOS (protected app), nothing changed"
        res.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") -> "$res - a device administrator protects it, nothing changed"
        isOk(res) -> res
        else -> "$res - not removed"
    }

    private fun isOk(res: String) = res.startsWith("Success", true) || res.startsWith("Suspended", true)

    /**
     * Step 1: remove the packages (no freezing yet). Several removals go to the phone in one command, and one
     * package list afterwards checks which are really gone (some HyperOS versions answer Success and keep the app).
     */
    fun removeFirstPass(items: List<Row>, progress: (Int, Int, Row, String) -> Unit): MutableList<Result> {
        shell.run("logcat -G 16M")
        val updated = updatedSystemApps()
        val raw = HashMap<String, String>()
        var done = 0
        for (chunk in items.chunked(15)) {
            val cmd = chunk.joinToString("; ") { r ->
                val p = requirePackageName(r.pkg)
                (if (updated.contains(p.lowercase())) "pm uninstall-system-updates $p >/dev/null 2>&1; " else "") +
                    "echo '@@$p'; pm uninstall -k --user 0 $p 2>&1"
            }
            var cur: String? = null
            shell.stream(cmd) { line ->
                if (line.startsWith("@@")) { cur = line.substring(2).trim(); raw[cur!!.lowercase()] = "" }
                else cur?.let { c -> raw[c.lowercase()] = (raw[c.lowercase()].orEmpty() + " " + line).trim() }
            }
            done += chunk.size
            log.line("Removed $done of ${items.size}...")
        }
        val stillThere = packageSet("pm list packages --user 0")
        val results = ArrayList<Result>()
        items.forEachIndexed { i, it ->
            val k = it.pkg.lowercase()
            var res = raw[k].orEmpty().ifEmpty { "No answer from the phone" }
            if (res.startsWith("Success", true)) {
                res = if (stillThere.containsKey(k)) "Phone said Success, but the app is still installed"
                else "Success" + if (updated.contains(k)) " (update removed first)" else ""
            }
            progress(i + 1, items.size, it, res)
            results.add(Result(nowS(), it.pkg, it.risk, res, it.description))
        }
        return results
    }

    fun notRemoved(results: List<Result>) = results.filter { !isOk(it.result) }

    /** Opens the phone's own uninstall screen for one package. Returns false if it is already gone. */
    fun openPhoneUninstall(r: Result): Boolean {
        if (!installedForUser(r.pkg)) { r.result = "Success (already gone)"; return false }
        shell.run("am start -a android.intent.action.DELETE -d package:${requirePackageName(r.pkg)}")
        return true
    }

    /** After the user answered the phone's uninstall screen. */
    fun afterPhoneUninstall(r: Result) {
        Thread.sleep(2000)
        if (!installedForUser(r.pkg)) {
            r.result = "Success (phone uninstall)"
            log.line("  ${r.pkg}: removed by the phone")
            return
        }
        // A system app keeps its factory copy after its update is removed; try the normal removal once more.
        val again = shell.text("pm uninstall -k --user 0 ${r.pkg}")
        if (again.startsWith("Success", true) && !installedForUser(r.pkg)) {
            r.result = "Success (after phone uninstall of its update)"
            log.line("  ${r.pkg}: removed")
        } else {
            r.result = "${r.result}; phone uninstall: still installed ($again)"
            log.line("  ${r.pkg}: still installed - the phone did not allow it, or it was cancelled")
        }
    }

    /** Step 3: whatever the phone still refuses to remove is frozen instead. */
    fun freezeRefused(results: List<Result>) {
        for (r in results.filter { !it.result.startsWith("Success", true) }) {
            if (!installedForUser(r.pkg)) continue
            if (suspendPackage(r.pkg)) {
                r.result = "Suspended (could not be removed: ${r.result})"
                log.line("  ${r.pkg}: could not be removed - frozen instead")
            } else r.result = "${r.result}; freezing refused too: $freezeError"
        }
    }

    /** Step 4: apps that come straight back (some ROMs reinstall within seconds) are frozen instead. */
    fun freezeCameStraightBack(results: List<Result>) {
        val gone = results.filter { it.result.startsWith("Success", true) }
        if (gone.isEmpty()) return
        Thread.sleep(3000)
        for (r in gone) {
            if (installedForUser(r.pkg) && suspendPackage(r.pkg)) {
                r.result = "Suspended (came straight back after removal)"
                log.line("  ${r.pkg}: came straight back - frozen instead")
            }
        }
    }

    class Summary(val removed: Int, val frozen: Int, val total: Int, val failText: String, val frozenText: String, val logFile: String)

    /** Step 5: log and remember what was done. */
    fun finishRemoval(results: List<Result>): Summary {
        val logFile = "$deviceDir/removed_${stamp()}.csv"
        storage.writeText(logFile, Csv.write(
            listOf("Time", "Package", "Risk", "Result", "Description"),
            results.map { listOf(it.time, it.pkg, it.risk, it.result, it.description) },
        ), export = true)
        val state = removedState()
        for (r in results) {
            val t = r.time.replace('T', ' ')
            if (r.result.startsWith("Success", true)) state[r.pkg] = t
            else if (r.result.startsWith("Suspended", true)) state[r.pkg] = "suspended $t"
        }
        saveRemovedState(state)
        val removed = results.count { it.result.startsWith("Success", true) }
        val frozen = results.filter { it.result.startsWith("Suspended", true) }
        val failed = results.filter { !isOk(it.result) }
        val failText = if (failed.isNotEmpty()) "\n\nNot removed, protected by HyperOS (${failed.size}):\n• " + failed.joinToString("\n• ") { it.pkg } else ""
        val offText = if (frozen.isNotEmpty()) "\n\nFrozen instead (${frozen.size}): greyed out, can't open. Restore unfreezes them.\n• " +
            frozen.joinToString("\n• ") { it.pkg } else ""
        log.line("Removed $removed, froze ${frozen.size}, of ${results.size}. Log saved: $logFile")
        return Summary(removed, frozen.size, results.size, failText, offText, logFile)
    }

    // ------------------------------------------------------------------------------------------
    // Removed-state file and came-back check (Get-RemovedState, Test-CameBack)
    // ------------------------------------------------------------------------------------------
    fun removedState(): LinkedHashMap<String, String> {
        val h = LinkedHashMap<String, String>()
        val text = storage.readText("$deviceDir/removed_state.json") ?: return h
        try {
            Json.parse(text).asMap()?.forEach { (k, v) -> h[k] = v.asText() }
        } catch (_: Exception) { }
        return h
    }

    fun saveRemovedState(state: Map<String, String>) {
        try { storage.writeText("$deviceDir/removed_state.json", Json.stringify(state)) } catch (_: Exception) { }
    }

    /**
     * Apps this tool removed that are installed again are logged and dealt with automatically:
     * first return -> removed again; second return -> frozen; unfrozen -> removed again.
     * Returns a notice for the user, or null when nothing came back. [changed] is set when something was done.
     */
    fun cameBackCheck(installed: Map<String, String>): Pair<String?, Boolean> {
        val state = removedState()
        if (state.isEmpty()) return null to false
        val off = packageSet("pm list packages -d --user 0")
        val back = state.keys.filter { p ->
            installed.containsKey(p.lowercase()) && !(
                (state[p]!!.startsWith("suspended", true) && suspendedForUser(p)) ||
                    (state[p]!!.startsWith("disabled", true) && off.containsKey(p.lowercase())))
        }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (back.isEmpty()) return null to false

        val file = "$deviceDir/came_back_${stamp()}.txt"
        val lines = ArrayList<String>()
        lines.add("HyperDebloat $version - removed apps found installed again at ${now("yyyy-MM-dd HH:mm:ss")}")
        for (p in back) lines.add("  $p  (last handled by this tool: ${state[p]})")
        val rDetail = Regex("codePath|versionName|firstInstallTime|lastUpdateTime|installerPackageName|initiatingPackageName|User 0:", I)
        for (p in back) {
            lines.add(""); lines.add("==== dumpsys package $p")
            for (l in shell.run("dumpsys package ${requirePackageName(p)}")) if (rDetail.containsMatchIn(l)) lines.add(l)
        }
        lines.add(""); lines.add("==== phone log lines about these apps or app installs")
        val names = Regex(back.joinToString("|") { Regex.escape(it) }, I)
        val rInstall = Regex("install-existing|installExisting|installPackage|PackageInstaller", I)
        shell.streamLarge("logcat -d") { l -> if (names.containsMatchIn(l) || rInstall.containsMatchIn(l)) lines.add(l) }
        try { storage.writeText(file, lines.joinToString("\n"), export = true) } catch (_: Exception) { }
        log.line("! ${back.size} app(s) removed earlier are back: ${back.joinToString(", ")}")

        shell.run("logcat -G 16M") // keep more log, so the next return can be traced
        val done = ArrayList<String>()
        val notDone = ArrayList<String>()
        val updated = updatedSystemApps()
        val stampNow = now("yyyy-MM-dd HH:mm:ss")
        lines.add(""); lines.add("==== what this tool did")
        for (p in back) {
            val res = if (state[p]!!.startsWith("again", true)) {
                // Second return: removing does not stick for this app, so freeze it in place.
                if (suspendPackage(p)) "Suspended (came back twice)" else removeOnePackage(p, updated.contains(p.lowercase()))
            } else removeOnePackage(p, updated.contains(p.lowercase()))
            lines.add("  $p -> $res")
            log.line("  $p  $res")
            when {
                res.startsWith("Success", true) -> { state[p] = "again $stampNow"; done.add("$p - removed again") }
                res.startsWith("Suspended", true) -> { state[p] = "suspended $stampNow"; done.add("$p - frozen (it came back after removal)") }
                else -> { state.remove(p); notDone.add("$p ($res)") }
            }
        }
        try { storage.writeText(file, lines.joinToString("\n"), export = true) } catch (_: Exception) { }
        saveRemovedState(state)
        var msg = ""
        if (done.isNotEmpty()) msg += "Dealt with:\n• " + done.joinToString("\n• ")
        if (notDone.isNotEmpty()) msg += "\n\nCould not remove or freeze:\n• " + notDone.joinToString("\n• ")
        msg += "\n\nAn app that comes back twice gets frozen. If you reinstalled one yourself, use Restore so it is left alone."
        return msg.trim() to true
    }

    // ------------------------------------------------------------------------------------------
    // Report (Save-Report)
    // ------------------------------------------------------------------------------------------
    fun saveReport(rows: List<Row>, dev: DeviceInfo): String {
        val file = "$deviceDir/report_${stamp()}.csv"
        val header = "# HyperDebloat $version | ${dev.marketName} (${dev.model}) | ${dev.osName} ${dev.osBuild} | Android ${dev.android} | ${now("yyyy-MM-dd HH:mm")}"
        val cols = listOf("Risk", "Category", "Package", "Description", "PhoneSays", "ListsSay", "FinalReasoning", "Partition", "Privileged",
            "PlatformSigned", "HasIcon", "Persistent", "AppId", "UsedBy", "PermHolders", "Running", "InstallTime", "Installer")
        val data = rows.map { r ->
            val f = r.facts
            listOf(r.risk, r.category, r.pkg, r.description, r.phone, r.lists, r.reason,
                f?.partition, f?.privileged, f?.platformSigned, f?.hasIcon, f?.persistent, f?.appId,
                f?.usedBy?.joinToString(" "), f?.permHolders?.joinToString(" "), f?.running, f?.installTime, f?.installer)
        }
        storage.writeText(file, header + "\r\n" + Csv.write(cols, data), export = true)
        log.line("Full report: $file")
        return file
    }

    // ------------------------------------------------------------------------------------------
    // Restore (Get-RemovedOnPhone, Invoke-Restore)
    // ------------------------------------------------------------------------------------------
    class PhoneState(
        val removed: Map<String, Boolean>, // package -> is a system app (file still on the phone)
        val disabled: Set<String>,
        val suspended: Set<String>,
    ) {
        val count get() = removed.size + disabled.size + suspended.size
    }

    /** Asks the phone itself, so anything removed or disabled earlier - by this or any tool - shows up. */
    fun removedOnPhone(): PhoneState {
        val installed = packageSet("pm list packages --user 0")
        val known = packageSet("pm list packages -u --user 0") // includes ones removed for user 0
        val systemAll = packageSet("pm list packages -u -s --user 0")
        val removed = LinkedHashMap<String, Boolean>()
        for ((k, p) in known) if (!installed.containsKey(k)) removed[p] = systemAll.containsKey(k)

        // Disabled by a user or a tool (enabled=3), as opposed to disabled by default; and frozen (suspended).
        val disabled = LinkedHashSet<String>()
        val suspended = LinkedHashSet<String>()
        val rPackage = Regex("^\\s*Package \\[([^\\]]+)\\]", I)
        val rUser0 = Regex("^\\s*User 0:", I)
        val rEnabled3 = Regex("\\benabled=3\\b", I)
        val rSusp = Regex("\\binstalled=true\\b.*\\bsuspended=true\\b", I)
        var cur: String? = null
        shell.streamLarge("dumpsys package packages") { line ->
            rPackage.find(line)?.let { cur = it.groupValues[1]; return@streamLarge }
            val c = cur ?: return@streamLarge
            if (installed.containsKey(c.lowercase()) && rUser0.containsMatchIn(line)) {
                if (rEnabled3.containsMatchIn(line)) disabled.add(c)
                if (rSusp.containsMatchIn(line)) suspended.add(c)
            }
        }
        return PhoneState(removed, disabled, suspended)
    }

    /** Builds the Restore list. [index] is only loaded when there is something to describe. */
    fun restoreItems(model: String, state: PhoneState, index: () -> ListIndex?): List<Row> {
        // This tool's own logs (any OS version of this model) only add the date and description.
        val prefix = model.replace(Regex("[^\\w.-]"), "_") + "_"
        val whenRemoved = HashMap<String, String>()
        val descOf = HashMap<String, String>()
        for (dir in storage.listDirs().filter { it.startsWith(prefix) }) {
            for (file in storage.listFiles(dir).filter { it.startsWith("removed_") && it.endsWith(".csv") }.sorted()) {
                val text = storage.readText("$dir/$file") ?: continue
                for (r in Csv.read(text)) {
                    if (r["Result"].orEmpty().startsWith("Success", true)) {
                        val k = r["Package"].orEmpty().lowercase()
                        whenRemoved[k] = r["Time"].orEmpty().split("T")[0]
                        descOf[k] = r["Description"].orEmpty()
                    }
                }
            }
        }
        val idx = if (state.count > 0) index() else null
        fun describe(p: String): String {
            descOf[p.lowercase()]?.takeIf { it.isNotEmpty() }?.let { return it }
            return idx?.describe(p) ?: ""
        }
        fun origin(p: String) = whenRemoved[p.lowercase()]?.let { "removed by HyperDebloat on $it" }
            ?: "removed earlier (another tool or before this tool's logs)"

        val items = ArrayList<Row>()
        for (p in state.removed.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            items.add(
                if (state.removed[p] == true) Row(Tier.SAFE, "RESTORE", p, describe(p), "system app: its file is still on the phone", "", origin(p), action = RestoreAction.INSTALL)
                else Row(Tier.MODERATE, "PLAY STORE", p, describe(p), "ordinary app: its file was deleted, data kept", "",
                    "${origin(p)}; restoring opens its Play Store page on the phone", action = RestoreAction.STORE)
            )
        }
        for (p in state.disabled.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            items.add(Row(Tier.SAFE, "RE-ENABLE", p, describe(p), "installed but disabled for your user", "",
                "disabled earlier (this tool, another tool or Settings)", action = RestoreAction.ENABLE))
        }
        for (p in state.suspended.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            items.add(Row(Tier.SAFE, "UNFREEZE", p, describe(p), "installed but frozen (suspended) for your user", "",
                "frozen by HyperDebloat because it kept coming back", action = RestoreAction.UNSUSPEND))
        }
        return items
    }

    /** Restores one item. Returns true when it is really back. STORE items return false (they need the Play Store). */
    fun restoreOne(it: Row): Pair<Boolean, String> {
        val p = requirePackageName(it.pkg)
        return when (it.action) {
            RestoreAction.INSTALL -> {
                shell.run("cmd package install-existing --user 0 $p")
                // install-existing can report success without restoring anything, so check the phone really has it.
                if (installedForUser(p)) true to "restored" else false to "NOT restored - reinstall it from the Play Store"
            }
            RestoreAction.ENABLE -> {
                val res = shell.text("pm enable --user 0 $p")
                val stillOff = packageSet("pm list packages -d --user 0 $p").containsKey(p.lowercase())
                if (!stillOff) true to "re-enabled" else false to "could not re-enable: $res"
            }
            RestoreAction.UNSUSPEND -> {
                val res = shell.text("pm unsuspend --user 0 $p")
                if (!suspendedForUser(p)) true to "unfrozen" else false to "could not unfreeze: $res"
            }
            RestoreAction.STORE, null -> false to "ordinary app - needs the Play Store"
        }
    }

    fun forgetRestored(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        val state = removedState()
        for (p in pkgs) state.keys.filter { it.equals(p, true) }.forEach { state.remove(it) }
        saveRemovedState(state)
    }

    fun openPlayStore(pkg: String) {
        shell.run("am start -a android.intent.action.VIEW -d market://details?id=${requirePackageName(pkg)}")
    }
}
