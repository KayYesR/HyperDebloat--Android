package io.github.kayyesr.hyperdebloat.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kayyesr.hyperdebloat.BuildConfig
import io.github.kayyesr.hyperdebloat.adb.AdbManager
import io.github.kayyesr.hyperdebloat.adb.PairingService
import io.github.kayyesr.hyperdebloat.core.Classifier
import io.github.kayyesr.hyperdebloat.core.CommunityLists
import io.github.kayyesr.hyperdebloat.core.DeviceInfo
import io.github.kayyesr.hyperdebloat.core.Engine
import io.github.kayyesr.hyperdebloat.core.Log
import io.github.kayyesr.hyperdebloat.core.RestoreAction
import io.github.kayyesr.hyperdebloat.core.Row
import io.github.kayyesr.hyperdebloat.core.Scanner
import io.github.kayyesr.hyperdebloat.core.Tier
import io.github.kayyesr.hyperdebloat.data.AdbShell
import io.github.kayyesr.hyperdebloat.data.AndroidStorage
import io.github.kayyesr.hyperdebloat.data.AppInfoCache
import io.github.kayyesr.hyperdebloat.data.HttpFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Connect : Screen
    data object Home : Screen
    data class Busy(val title: String) : Screen
    data class Pick(val spec: PickSpec) : Screen
    data object Reports : Screen
}

/** A package list to tick from (Show-SelectionGrid). Completes with the ticked rows, or an empty list for Back. */
class PickSpec(
    val title: String,
    val items: List<Row>,
    val actionText: String,
    val preselectBackground: Boolean,
    val legend: String,
    val noMatchHint: String,
    val allowFlagged: Boolean,
) {
    internal val result = CompletableDeferred<List<Row>>()
}

/** A message the user must answer (Read-YesNo) or acknowledge (Show-Notice). */
class DialogSpec(val text: String, val yesNo: Boolean, val defaultYes: Boolean, val warning: Boolean) {
    internal val result = CompletableDeferred<Boolean>()
}

/**
 * Runs the same flow as the PowerShell Main loop: connect, scan, Recommended / Custom / Restore.
 * Questions in the middle of an action suspend until the user answers, so the steps read in order.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val version: String = BuildConfig.VERSION_NAME
    val adb = AdbManager.get(app)
    val storage = AndroidStorage(app)
    val appInfo = AppInfoCache(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val fetcher = HttpFetcher()

    private val _screen = MutableStateFlow<Screen>(Screen.Connect)
    val screen: StateFlow<Screen> = _screen

    private val _dialog = MutableStateFlow<DialogSpec?>(null)
    val dialog: StateFlow<DialogSpec?> = _dialog

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device

    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary

    private val _saveRaw = MutableStateFlow(prefs.getBoolean("saveRaw", false))
    val saveRaw: StateFlow<Boolean> = _saveRaw

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private val shell = AdbShell(adb, storage) { _saveRaw.value }
    private val logger = Log { line ->
        _log.update { (it + line).takeLast(3000) }
    }

    private var engine: Engine? = null
    private var rows: List<Row>? = null
    private var needScan = true
    private var deviceKey = ""

    init {
        tryConnect()
    }

    // ------------------------------------------------------------------------------------------
    // Asking the user
    // ------------------------------------------------------------------------------------------
    private suspend fun ask(text: String, defaultYes: Boolean = false, warning: Boolean = false): Boolean {
        val d = DialogSpec(text, true, defaultYes, warning)
        _dialog.value = d
        return d.result.await().also { _dialog.value = null }
    }

    private suspend fun notice(text: String, warning: Boolean = false) {
        val d = DialogSpec(text, false, true, warning)
        _dialog.value = d
        d.result.await()
        _dialog.value = null
    }

    fun answer(yes: Boolean) { _dialog.value?.result?.complete(yes) }

    private suspend fun pick(spec: PickSpec): List<Row> {
        val back = _screen.value
        _screen.value = Screen.Pick(spec)
        return spec.result.await().also { _screen.value = back }
    }

    fun finishPick(spec: PickSpec, picked: List<Row>) { spec.result.complete(picked) }

    private fun busy(title: String) { _screen.value = Screen.Busy(title) }
    private fun step(text: String) { logger.line(""); logger.line("== $text") }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun setSaveRaw(on: Boolean) { _saveRaw.value = on; prefs.edit().putBoolean("saveRaw", on).apply() }

    fun showReports() { if (!_working.value) _screen.value = Screen.Reports }
    fun goHome() { if (!_working.value) _screen.value = if (adb.state.value == AdbManager.State.CONNECTED) Screen.Home else Screen.Connect }

    // ------------------------------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------------------------------
    fun tryConnect() {
        if (_working.value) return
        viewModelScope.launch {
            val ok = io { adb.tryConnect() }
            if (ok && _screen.value == Screen.Connect) onConnected()
        }
    }

    fun startPairing() = PairingService.start(getApplication())

    /** Pairing with the code typed in the app (works in split screen next to Settings). */
    fun pairInApp(code: String) {
        viewModelScope.launch {
            val port = PairingService.port.value.takeIf { it > 0 } ?: io { adb.findPairingPort(10_000) }
            if (port <= 0) {
                notice("The pairing service was not found. Open Wireless debugging > Pair device with pairing code, keep it open, then try again.", true)
                return@launch
            }
            val err = io { adb.pairAndConnect(port, code) }
            if (err != null) notice(err, true) else { PairingService.stop(getApplication()); onConnected() }
        }
    }

    private suspend fun onConnected() {
        if (connectPhone() != null) _screen.value = Screen.Home
    }

    /** Reads the phone's details (Connect-Phone). Returns null if the user gives up. */
    private suspend fun connectPhone(): DeviceInfo? {
        if (adb.state.value != AdbManager.State.CONNECTED && !io { adb.tryConnect() }) {
            _screen.value = Screen.Connect
            return null
        }
        val dev = io { Scanner(shell, logger).deviceInfo() }
        if (dev.key != deviceKey) {
            step("Phone")
            logger.line("${dev.marketName} (${dev.model}, ${dev.codename})  |  ${dev.osName} ${dev.osBuild}  |  Android ${dev.android}")
            if (!Regex("xiaomi|redmi|poco", RegexOption.IGNORE_CASE).containsMatchIn("${dev.manufacturer} ${dev.brand}")) {
                if (!ask("This does not look like a Xiaomi, Redmi or POCO phone. The lists are built for those. Continue anyway?", false, true)) return null
            }
            deviceKey = dev.key
            needScan = true
            rows = null
        }
        engine = Engine(shell, storage, logger, dev.sdk, version).also { it.deviceDir = dev.deviceDir }
        _device.value = dev
        return dev
    }

    // ------------------------------------------------------------------------------------------
    // Main actions
    // ------------------------------------------------------------------------------------------
    enum class Action { RECOMMENDED, CUSTOM, RESTORE, RESCAN }

    fun run(action: Action) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                val dev = connectPhone() ?: return@launch
                if (action == Action.RESCAN || (action != Action.RESTORE && (needScan || rows == null))) {
                    try {
                        rows = scan(dev)
                    } catch (e: Exception) {
                        val report = errorReport(e, "Scan")
                        logger.line("  Error in scan: ${e.message}")
                        notice("The scan stopped with an error.\n\n$report", true)
                        return@launch
                    }
                }
                try {
                    when (action) {
                        Action.RECOMMENDED -> recommended(rows!!)
                        Action.CUSTOM -> custom(rows!!)
                        Action.RESTORE -> restore(dev)
                        Action.RESCAN -> {}
                    }
                } catch (e: Exception) {
                    // One failed action must not close the app: report it and go back to the home screen.
                    needScan = true
                    val name = action.name.lowercase().replaceFirstChar { it.uppercase() }
                    val report = errorReport(e, name)
                    logger.line("  Error in $name: ${e.message}")
                    notice("$name stopped with an error. Nothing after this point was done.\n\n$report", true)
                }
            } finally {
                _working.value = false
                if (_screen.value !is Screen.Connect) _screen.value = Screen.Home
            }
        }
    }

    private suspend fun scan(dev: DeviceInfo): List<Row> {
        busy("Scanning the phone (read-only)")
        val eng = engine!!
        step("Scanning the phone (read-only)")
        val sc = Scanner(shell, logger)
        var sets = io { sc.packageSets() }
        logger.line("${sets.all.size} packages installed (${sets.system.size} system)")
        val (msg, changed) = try {
            io { eng.cameBackCheck(sets.all) }
        } catch (e: Exception) {
            logger.line("! Came-back check skipped: ${e.message}")
            null to false
        }
        if (msg != null) notice(msg, true)
        if (changed) sets = io { sc.packageSets() }
        val facts = io { sc.deviceFacts(sets.all) }
        step("Community lists (descriptions and cross-check)")
        val idx = io { CommunityLists.buildIndex(CommunityLists.load(storage, fetcher, logger)) }
        val result = Classifier.classify(idx, sets, facts, getApplication<Application>().packageName)
        step("Classification")
        for (t in listOf(Tier.SAFE, Tier.MODERATE, Tier.HIGH, Tier.UNSAFE, Tier.FLAGGED, Tier.APP)) {
            logger.line("  ${t.colour.padEnd(7)} ${t.label.padEnd(9)} ${result.count { it.tier == t }} packages")
        }
        io { eng.saveReport(result, dev) }
        _summary.value = "Last scan ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}: " + Classifier.summary(result)
        needScan = false
        return result
    }

    /** Names any ticked app that has an icon on the home screen, so nothing the user sees daily goes by accident. */
    private fun iconAppNote(items: List<Row>): String {
        val apps = items.filter { it.userApp && !it.ordinary }.map { it.pkg }
        val ord = items.filter { it.ordinary }.map { it.pkg }
        val ordText = if (ord.isNotEmpty()) "\n\n${ord.size} ordinary app(s) will be removed completely; Restore can only open their Play Store page:\n  " + ord.joinToString("\n  ") else ""
        if (apps.isEmpty()) return ordText
        return "\n\nThese ${apps.size} are everyday apps (home-screen icon or on the keep list) that you may use:\n  " + apps.joinToString("\n  ") +
            "\n\nPress No and untick them if you want to keep them." + ordText
    }

    private suspend fun recommended(rows: List<Row>) {
        val green = rows.filter { it.tier == Tier.SAFE || (it.tier == Tier.APP && it.atSetup) }
        if (green.isEmpty()) {
            logger.line("Nothing rated GREEN on this phone.")
            notice("Nothing on this phone is rated GREEN (safe to remove without side effects).\n\nUse Custom to review the YELLOW packages: each one says what stops working if you remove it.")
            return
        }
        step("Recommended: ${green.size} packages (GREEN, plus apps installed during setup)")
        val flagged = rows.count { it.tier == Tier.FLAGGED }
        if (flagged > 0) logger.line("$flagged packages were flagged (phone and lists disagree, or no data) and will not be touched. See the report.")
        val legend = "GREEN: removing them breaks nothing else. Background junk is ticked; everyday apps (YouTube, Chrome, Gmail, WhatsApp and anything with an icon) are NOT. BLUE: apps installed during phone setup (games and other preloads) - not ticked, as backup-restored apps arrive then too. Tick what you don't want."
        val picked = pick(PickSpec("Recommended (${green.size} packages)", green, "Remove selected", true, legend,
            "Recommended shows GREEN and setup-installed apps only. Go Back > Custom to search everything.", false))
        if (picked.isEmpty()) { logger.line("Back. Nothing was removed."); return }
        if (!ask("Remove ${picked.size} GREEN package(s)?${iconAppNote(picked)}")) { logger.line("Cancelled. Nothing was removed."); return }
        removePackages(picked)
    }

    private suspend fun custom(rows: List<Row>) {
        step("Custom: opening the list")
        val legend = "GREEN = safe  |  YELLOW = a minor feature may break  |  ORANGE = a notable/core feature may break  |  RED = can stop the phone booting  |  GREY = flagged: unknown to every list, tick only if you know what it is  |  BLUE = ordinary app (never pre-ticked)"
        var picked = pick(PickSpec("Custom", rows, "Remove selected", true, legend, "", true))
        if (picked.isEmpty()) { logger.line("Back. Nothing was removed."); return }

        val red = picked.filter { it.tier == Tier.UNSAFE }
        if (red.isNotEmpty()) {
            val names = red.joinToString("\n") { it.pkg }
            if (!ask("You selected ${red.size} RED (unsafe) package(s):\n\n$names\n\nThese can stop the phone booting. The only fix may be a factory reset.\n\nRemove them anyway?", false, true)) {
                picked = picked.filter { it.tier != Tier.UNSAFE }; logger.line("RED packages skipped.")
            }
        }
        val grey = picked.filter { it.tier == Tier.FLAGGED }
        if (grey.isNotEmpty()) {
            val names = grey.joinToString("\n") { it.pkg }
            val warn = "No list knows what these do, so the tool cannot say what breaks without them. If something stops working, use Restore."
            if (!ask("You selected ${grey.size} FLAGGED (unknown) package(s):\n\n$names\n\n$warn\n\nRemove them anyway?", false, true)) {
                picked = picked.filter { it.tier != Tier.FLAGGED }; logger.line("FLAGGED packages skipped.")
            }
        }
        if (picked.isEmpty()) { logger.line("Nothing left to remove."); return }
        val byTier = picked.groupBy { it.risk }.map { "${it.value.size} ${it.key}" }
        if (!ask("Remove ${picked.size} packages (${byTier.joinToString(", ")})?${iconAppNote(picked)}")) { logger.line("Cancelled. Nothing was removed."); return }
        removePackages(picked)
    }

    private suspend fun removePackages(items: List<Row>) {
        val eng = engine!!
        busy("Removing ${items.size} package(s)")
        step("Removing ${items.size} package(s)")
        val results = io {
            eng.removeFirstPass(items) { i, n, it, res -> logger.line("  [$i/$n] ${it.pkg}  ${eng.shownResult(res)}") }
        }

        // Fallback for apps the ROM refuses to remove over ADB: the phone's own uninstall screen.
        val failed = eng.notRemoved(results)
        if (failed.isNotEmpty()) {
            val names = failed.joinToString("\n  ") { it.pkg }
            val q = "${failed.size} app(s) could not be removed this way:\n  $names\n\nThe phone may still let you uninstall them from its own uninstall screen (the one Settings uses). For each app, the phone will ask; tap OK (or Uninstall) there.\n\nNote: this removes the app completely, or only its update if it is a system app. Restore may not be able to bring it back; you would reinstall it from a store.\n\nTry it?"
            if (ask(q)) {
                for (r in failed) {
                    if (!io { eng.openPhoneUninstall(r) }) continue
                    logger.line("  ${r.pkg}: uninstall screen opened - tap OK there")
                    notice("The phone is asking whether to uninstall:\n\n${r.pkg}\n\nTap OK (or Uninstall) on the phone's screen, wait a moment, then press OK here.")
                    io { eng.afterPhoneUninstall(r) }
                }
            }
        }
        io { eng.freezeRefused(results) }
        io { eng.freezeCameStraightBack(results) }
        val s = io { eng.finishRemoval(results) }
        if (s.removed + s.frozen > 0) {
            needScan = true
            logger.line("Restore any of them later with Restore.")
            if (ask("Removed ${s.removed} of ${s.total} package(s).${s.frozenText}${s.failText}\n\nReboot the phone now? Afterwards, check calls, camera, GPS/compass, payments and fingerprint. Anything removed can be put back with Restore.")) {
                reboot()
            }
        } else notice("Nothing was removed.${s.failText}", true)
    }

    private suspend fun restore(dev: DeviceInfo) {
        val eng = engine!!
        busy("Asking the phone what has been removed or disabled")
        step("Asking the phone what has been removed or disabled")
        val state = io { eng.removedOnPhone() }
        val items = io {
            eng.restoreItems(dev.model, state) {
                logger.line("Loading package descriptions...")
                CommunityLists.buildIndex(CommunityLists.load(storage, fetcher, logger))
            }
        }
        if (items.isEmpty()) {
            logger.line("Nothing has been removed or disabled on this phone.")
            notice("Nothing has been removed or disabled on this phone, so there is nothing to restore.")
            return
        }
        val nR = items.count { it.action == RestoreAction.INSTALL }
        val nS = items.count { it.action == RestoreAction.STORE }
        val nE = items.count { it.action == RestoreAction.ENABLE }
        val nF = items.count { it.action == RestoreAction.UNSUSPEND }
        step("Restore: $nR removed system apps, $nE disabled apps, $nF frozen apps, $nS ordinary apps (Play Store)")
        val picked = pick(PickSpec("Restore", items, "Restore selected", false,
            "GREEN = RESTORE (removed system app, comes straight back), RE-ENABLE (disabled app) or UNFREEZE (frozen app)  |  YELLOW = PLAY STORE (ordinary app, its Play Store page opens on the phone)",
            "", false))
        if (picked.isEmpty()) { logger.line("Nothing selected."); return }
        if (!ask("Restore ${picked.size} packages?", true)) return
        busy("Restoring ${picked.size} package(s)")
        val lost = ArrayList<String>()
        val restored = ArrayList<String>()
        for (it in picked) {
            val (ok, text) = io { eng.restoreOne(it) }
            logger.line("  ${it.pkg}  $text")
            if (ok) restored.add(it.pkg)
            else if (it.action == RestoreAction.INSTALL || it.action == RestoreAction.STORE) lost.add(it.pkg)
        }
        needScan = true
        io { eng.forgetRestored(restored) }
        if (lost.isNotEmpty()) {
            logger.line("! ${lost.size} app(s) must be reinstalled from the Play Store (your data was kept).")
            for (p in lost) {
                if (ask("Open the Play Store page for $p on the phone?", true)) {
                    io { eng.openPlayStore(p) }
                    notice("The Play Store page for $p is open on the phone.\n\nTap Install there, then press OK here.")
                }
            }
        }
        val failedN = picked.count { it.action != RestoreAction.STORE } - restored.size
        val summary = "Restored ${restored.size} app(s)." +
            (if (lost.isNotEmpty()) "\n${lost.size} need the Play Store." else "") +
            (if (failedN > 0) "\n$failedN could not be restored (see the progress log)." else "")
        if (restored.isNotEmpty()) {
            logger.line("Some launchers only show restored apps after a reboot.")
            if (ask("$summary\n\nSome launchers only show restored apps after a reboot. Reboot the phone now?", true)) reboot()
        } else notice(summary)
    }

    private suspend fun reboot() {
        io { adb.reboot() }
        _screen.value = Screen.Connect
    }

    /** Saves the full error to error_log.txt and returns a short description for the user. */
    private fun errorReport(e: Exception, context: String): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        try {
            storage.appendText("error_log.txt", "==== ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}  HyperDebloat $version  ($context)\n${e.message}\n$sw\n")
        } catch (_: Exception) { }
        return "${e.message ?: e.javaClass.simpleName}\n\nDetails saved to error_log.txt (see Reports)."
    }
}
