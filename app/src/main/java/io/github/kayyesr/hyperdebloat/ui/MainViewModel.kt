package io.github.kayyesr.hyperdebloat.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicBoolean

/** Where the user is in the guided flow on the home screen. */
enum class Guide { SCAN, REVIEW, RESTART, DONE }

sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
    data class Busy(val title: String) : Screen
    data class Pick(val spec: PickSpec) : Screen
    data object Reports : Screen
}

/** A package list to tick from. Completes with the ticked rows, or an empty list for Back. */
class PickSpec(
    val title: String,
    val items: List<Row>,
    val actionText: String,
    val preselectBackground: Boolean,
    val noMatchHint: String,
    val allowFlagged: Boolean,
    /** Simple mode: no package names, search or categories; the list is split into "ticked for you" and "you decide". */
    val simple: Boolean = false,
    /** One line at the top saying what to do on this screen. */
    val intro: String = "",
) {
    internal val result = CompletableDeferred<List<Row>>()
}

/** A question (Yes/No) or a notice (OK). */
class DialogSpec(
    val title: String?,
    val text: String,
    val yesNo: Boolean,
    val warning: Boolean,
    val yesText: String? = null,
    val noText: String? = null,
) {
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

    private val _screen = MutableStateFlow<Screen>(Screen.Setup)
    val screen: StateFlow<Screen> = _screen

    private val _setupStep = MutableStateFlow(SetupStep.DEV_OPTIONS)
    val setupStep: StateFlow<SetupStep> = _setupStep

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _dialog = MutableStateFlow<DialogSpec?>(null)
    val dialog: StateFlow<DialogSpec?> = _dialog

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device

    private val _counts = MutableStateFlow<Map<Tier, Int>?>(null)
    val counts: StateFlow<Map<Tier, Int>?> = _counts

    private val _lastScan = MutableStateFlow("")
    val lastScan: StateFlow<String> = _lastScan

    private val _saveRaw = MutableStateFlow(prefs.getBoolean("saveRaw", false))
    val saveRaw: StateFlow<Boolean> = _saveRaw

    private val _guide = MutableStateFlow(Guide.SCAN)
    val guide: StateFlow<Guide> = _guide

    private var restartPending: Boolean
        get() = prefs.getBoolean("restartPending", false)
        set(v) = prefs.edit().putBoolean("restartPending", v).apply()
    private var justRestarted: Boolean
        get() = prefs.getBoolean("justRestarted", false)
        set(v) = prefs.edit().putBoolean("justRestarted", v).apply()

    private fun updateGuide() {
        _guide.value = when {
            restartPending -> Guide.RESTART
            justRestarted && (needScan || rows == null) -> Guide.DONE
            needScan || rows == null -> Guide.SCAN
            else -> Guide.REVIEW
        }
    }

    private val _noWifi = MutableStateFlow(adb.noWifiMode)
    val noWifi: StateFlow<Boolean> = _noWifi

    private val working = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    private val shell = AdbShell(adb, storage) { _saveRaw.value }
    private val logger = Log { line -> _log.update { (it + line).takeLast(3000) } }

    private var engine: Engine? = null
    private var rows: List<Row>? = null
    private var needScan = true
    private var deviceKey = ""

    init {
        refreshSetup()
    }

    // ------------------------------------------------------------------------------------------
    // Asking the user
    // ------------------------------------------------------------------------------------------
    private suspend fun ask(title: String?, text: String, yes: String? = null, no: String? = null, warning: Boolean = false): Boolean {
        val d = DialogSpec(title, text, true, warning, yes, no)
        _dialog.value = d
        return d.result.await().also { _dialog.value = null }
    }

    private suspend fun notice(title: String?, text: String, warning: Boolean = false) {
        val d = DialogSpec(title, text, false, warning)
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

    fun showReports() { if (!working.get()) _screen.value = Screen.Reports }
    fun goHome() { if (!working.get()) _screen.value = if (adb.state.value == AdbManager.State.CONNECTED) Screen.Home else Screen.Setup }

    // ------------------------------------------------------------------------------------------
    // Setup and connection
    // ------------------------------------------------------------------------------------------
    private fun devOptionsOn(): Boolean =
        Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    @Suppress("DEPRECATION")
    private fun wifiConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Works out which setup step is next, and connects when everything is in place. */
    fun refreshSetup() {
        if (working.get()) return
        if (!devOptionsOn()) { _setupStep.value = SetupStep.DEV_OPTIONS; return }
        if (!connecting.compareAndSet(false, true)) return
        viewModelScope.launch {
            _checking.value = true
            try {
                val wifi = wifiConnected()
                // No Wi-Fi mode is tried first; wireless debugging only when Wi-Fi is connected.
                val ok = io { adb.tryConnect(wireless = wifi) }
                if (ok) {
                    if (_screen.value == Screen.Setup) {
                        try {
                            adb.debug("Reading phone details")
                            onConnected()
                        } catch (e: Exception) {
                            adb.debug("! Reading phone details failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                        }
                    }
                } else {
                    _setupStep.value = when {
                        !wifi -> SetupStep.WIFI
                        adb.state.value == AdbManager.State.NOT_PAIRED -> SetupStep.PAIR
                        else -> SetupStep.WIRELESS_DEBUGGING
                    }
                    if (_screen.value == Screen.Home) _screen.value = Screen.Setup
                }
            } finally {
                _checking.value = false
                connecting.set(false)
            }
        }
    }

    fun startPairing() = PairingService.start(getApplication())

    /** Connects to a port the user typed from the Wireless debugging screen. */
    fun connectPort(port: Int) {
        if (!connecting.compareAndSet(false, true)) return
        viewModelScope.launch {
            _checking.value = true
            try {
                when (io { adb.connectPort(port) }) {
                    AdbManager.ConnectResult.OK -> onConnected()
                    AdbManager.ConnectResult.NOT_PAIRED -> {
                        _setupStep.value = SetupStep.PAIR
                        notice("Not paired yet", "The phone answered on that port but does not know HyperDebloat yet. Do the pairing step.")
                    }
                    AdbManager.ConnectResult.FAILED -> notice("Could not connect",
                        "Nothing answered on port $port. Check the number after the colon under IP address & Port in Wireless debugging.", true)
                }
            } finally {
                _checking.value = false
                connecting.set(false)
            }
        }
    }

    /** Pairing with the code typed in the app (split screen next to Settings). */
    fun pairInApp(code: String) {
        viewModelScope.launch {
            val port = PairingService.port.value.takeIf { it > 0 } ?: io { adb.findPairingPort(10_000) }
            if (port <= 0) {
                notice("Pairing screen not found", "Keep Pair device with pairing code open in Settings, then try again.", true)
                return@launch
            }
            val err = io { adb.pairAndConnect(port, code) }
            if (err != null) notice("Pairing failed", err, true) else { PairingService.stop(getApplication()); onConnected() }
        }
    }

    private suspend fun onConnected() {
        if (connectPhone() == null) return
        updateGuide()
        _screen.value = Screen.Home
        _noWifi.value = adb.noWifiMode
        when {
            // Turned on before, but the phone restarted since: switch it back on quietly.
            adb.noWifiMode && !adb.connectedLocally -> io { adb.enableNoWifiMode() }
            !adb.noWifiMode && !prefs.getBoolean("noWifiOffered", false) -> {
                prefs.edit().putBoolean("noWifiOffered", true).apply()
                if (ask("Use without Wi-Fi?", "HyperDebloat can stay connected after you turn Wi-Fi off, until the phone restarts. " +
                        "After a restart it needs Wi-Fi once again.\n\nYour phone may ask \"Allow USB debugging?\". Tap Always allow, " +
                        "but only when HyperDebloat asks.", yes = "Turn on", no = "Not now")) setNoWifi(true)
            }
        }
    }

    /** Turns No Wi-Fi mode on or off (Home menu). */
    fun setNoWifi(on: Boolean) {
        if (!working.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (on) {
                    busy("Switching to no Wi-Fi")
                    logger.line("If the phone asks \"Allow USB debugging?\", tap Always allow.")
                    val err = io { adb.enableNoWifiMode() }
                    if (err != null) notice("Could not switch", err, true)
                    else notice("Done", "You can turn Wi-Fi off now. After the phone restarts, HyperDebloat needs Wi-Fi once to switch this back on.")
                } else {
                    io { adb.disableNoWifiMode() }
                }
                _noWifi.value = adb.noWifiMode
            } finally {
                working.set(false)
                _screen.value = Screen.Home
                if (!adb.isConnected) { _screen.value = Screen.Setup; refreshSetup() }
            }
        }
    }

    /** Reads the phone's details. Returns null if not connected or the user gives up. */
    private suspend fun connectPhone(): DeviceInfo? {
        if (adb.state.value != AdbManager.State.CONNECTED && !io { adb.tryConnect() }) {
            _screen.value = Screen.Setup
            refreshSetup()
            return null
        }
        val dev = io { Scanner(shell, logger).deviceInfo() }
        if (dev.key != deviceKey) {
            step("Phone")
            logger.line("${dev.marketName} (${dev.model}, ${dev.codename})  |  ${dev.osName} ${dev.osBuild}  |  Android ${dev.android}")
            if (!Regex("xiaomi|redmi|poco", RegexOption.IGNORE_CASE).containsMatchIn("${dev.manufacturer} ${dev.brand}")) {
                if (!ask("Not a Xiaomi phone", "The lists are made for Xiaomi, Redmi and POCO phones. Continue anyway?", warning = true)) return null
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
        if (!working.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val dev = connectPhone() ?: return@launch
                if (action == Action.RESCAN || (action != Action.RESTORE && (needScan || rows == null))) {
                    try {
                        rows = scan(dev)
                    } catch (e: Exception) {
                        notice("Scan stopped", errorReport(e, "Scan"), true)
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
                    notice("$name stopped", "Nothing after this point was done.\n\n" + errorReport(e, name), true)
                }
            } finally {
                working.set(false)
                updateGuide()
                if (_screen.value != Screen.Setup) _screen.value = Screen.Home
            }
        }
    }

    private suspend fun scan(dev: DeviceInfo): List<Row> {
        busy("Checking your phone")
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
        if (msg != null) notice("Removed apps came back", msg, true)
        if (changed) sets = io { sc.packageSets() }
        val facts = io { sc.deviceFacts(sets.all) }
        step("Downloading the debloat lists")
        val idx = io { CommunityLists.buildIndex(CommunityLists.load(storage, fetcher, logger)) }
        val result = Classifier.classify(idx, sets, facts, getApplication<Application>().packageName)
        step("Classification")
        for (t in listOf(Tier.SAFE, Tier.MODERATE, Tier.HIGH, Tier.UNSAFE, Tier.FLAGGED, Tier.APP)) {
            logger.line("  ${t.colour.padEnd(7)} ${t.label.padEnd(9)} ${result.count { it.tier == t }} packages")
        }
        io { eng.saveReport(result, dev) }
        _counts.value = Tier.entries.associateWith { t -> result.count { it.tier == t } }
        _lastScan.value = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        needScan = false
        justRestarted = false
        return result
    }

    /** Names ticked everyday apps and ordinary apps, so nothing used daily goes by accident. */
    private fun iconAppNote(items: List<Row>): String {
        val apps = items.filter { it.userApp && !it.ordinary }.map { appName(it.pkg) }
        val ord = items.filter { it.ordinary }.map { appName(it.pkg) }
        var s = ""
        if (apps.isNotEmpty()) s += "\n\nApps you may use:\n• " + apps.joinToString("\n• ") + "\nPress Cancel if you want to keep any of them."
        if (ord.isNotEmpty()) s += "\n\nFully uninstalled (Restore can only open the Play Store):\n• " + ord.joinToString("\n• ")
        return s
    }

    private fun appName(pkg: String) = appInfo.label(pkg).ifEmpty { pkg }

    private suspend fun recommended(rows: List<Row>) {
        val green = rows.filter { it.tier == Tier.SAFE || (it.tier == Tier.APP && it.atSetup) }
        if (green.isEmpty()) {
            notice("Nothing safe to remove", "Nothing on this phone is rated safe. Use Custom to look at the rest.")
            return
        }
        step("Recommended: ${green.size} packages")
        val picked = pick(PickSpec("Remove junk", green, "Remove selected", true,
            "", false, simple = true,
            intro = "Junk is already ticked. Untick anything you want to keep, then tap Remove."))
        if (picked.isEmpty()) { logger.line("Back. Nothing was removed."); return }
        if (!ask("Remove ${picked.size} apps?", "You can bring them back with Restore.${iconAppNote(picked)}", yes = "Remove", no = "Cancel")) return
        removePackages(picked)
    }

    private suspend fun custom(rows: List<Row>) {
        step("Custom")
        var picked = pick(PickSpec("Choose apps", rows, "Remove selected", true, "", true,
            intro = "Every app on the phone, colour-coded. Tap ⓘ to see why."))
        if (picked.isEmpty()) { logger.line("Back. Nothing was removed."); return }

        val red = picked.filter { it.tier == Tier.UNSAFE }
        if (red.isNotEmpty()) {
            val names = red.joinToString("\n• ") { appName(it.pkg) }
            if (!ask("${red.size} unsafe selected", "• $names\n\nThese can stop the phone starting. The fix may be a factory reset.",
                    yes = "Remove anyway", no = "Skip them", warning = true)) {
                picked = picked.filter { it.tier != Tier.UNSAFE }
            }
        }
        val grey = picked.filter { it.tier == Tier.FLAGGED }
        if (grey.isNotEmpty()) {
            val names = grey.joinToString("\n• ") { appName(it.pkg) }
            if (!ask("${grey.size} unknown selected", "• $names\n\nNo list knows what these do. If something breaks, use Restore.",
                    yes = "Remove anyway", no = "Skip them", warning = true)) {
                picked = picked.filter { it.tier != Tier.FLAGGED }
            }
        }
        if (picked.isEmpty()) return
        if (!ask("Remove ${picked.size} apps?", "You can bring them back with Restore.${iconAppNote(picked)}", yes = "Remove", no = "Cancel")) return
        removePackages(picked)
    }

    private suspend fun removePackages(items: List<Row>) {
        val eng = engine!!
        busy("Removing ${items.size} apps")
        step("Removing ${items.size} package(s)")
        val results = io {
            eng.removeFirstPass(items) { i, n, it, res -> logger.line("  [$i/$n] ${it.pkg}  ${eng.shownResult(res)}") }
        }

        // Fallback for apps HyperOS refuses to remove this way: the phone's own uninstall screen.
        val failed = eng.notRemoved(results)
        if (failed.isNotEmpty()) {
            val names = failed.joinToString("\n• ") { appName(it.pkg) }
            if (ask("${failed.size} apps are protected", "• $names\n\nTry the phone's own uninstall screen? You tap OK for each one. " +
                    "This uninstalls fully, so Restore may not bring them back.", yes = "Try", no = "Skip")) {
                for (r in failed) {
                    if (!io { eng.openPhoneUninstall(r) }) continue
                    logger.line("  ${r.pkg}: uninstall screen opened")
                    notice("Tap OK on the phone", "Confirm uninstalling ${appName(r.pkg)}, then press OK here.")
                    io { eng.afterPhoneUninstall(r) }
                }
            }
        }
        io { eng.freezeRefused(results) }
        io { eng.freezeCameStraightBack(results) }
        val s = io { eng.finishRemoval(results) }
        if (s.removed + s.frozen > 0) {
            needScan = true
            restartPending = true
            if (ask("Removed ${s.removed} of ${s.total}", "${s.frozenText}${s.failText}\n\nRestart the phone now? Then check calls, camera, GPS, payments and fingerprint.".trimStart(),
                    yes = "Restart", no = "Later")) reboot()
        } else notice("Nothing was removed", s.failText.trim(), true)
    }

    private suspend fun restore(dev: DeviceInfo) {
        val eng = engine!!
        busy("Checking what was removed")
        step("Asking the phone what has been removed or disabled")
        val state = io { eng.removedOnPhone() }
        val items = io {
            eng.restoreItems(dev.model, state) {
                logger.line("Loading package descriptions...")
                CommunityLists.buildIndex(CommunityLists.load(storage, fetcher, logger))
            }
        }
        if (items.isEmpty()) {
            notice("Nothing to restore", "Nothing has been removed or disabled on this phone.")
            return
        }
        val picked = pick(PickSpec("Bring apps back", items, "Restore selected", false, "", false, simple = true,
            intro = "Tick the apps you want back, then tap Restore."))
        if (picked.isEmpty()) return
        busy("Restoring ${picked.size} apps")
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
        for (p in lost) {
            if (ask("Reinstall from Play Store?", "${appName(p)} was fully uninstalled. Open its Play Store page? Your data was kept.", yes = "Open", no = "Skip")) {
                io { eng.openPlayStore(p) }
                notice("Tap Install", "Tap Install on the Play Store page, then press OK here.")
            }
        }
        val failedN = picked.count { it.action != RestoreAction.STORE } - restored.size
        val extra = if (failedN > 0) "\n$failedN could not be restored (see Reports)." else ""
        if (restored.isNotEmpty()) {
            if (ask("Restored ${restored.size} apps", "Some launchers only show them after a restart. Restart now?$extra", yes = "Restart", no = "Later")) reboot()
        } else notice("Restore finished", "Nothing came back.$extra".trim())
    }

    /** Home screen "Restart phone" step. */
    fun restartNow() {
        if (!working.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (ask("Restart now?", "Your phone will restart. Open HyperDebloat again afterwards.", yes = "Restart", no = "Cancel")) reboot()
            } finally {
                working.set(false)
                updateGuide()
            }
        }
    }

    /** "I'll restart later" on the home screen: skip the restart step. */
    fun skipRestart() { restartPending = false; justRestarted = true; updateGuide() }

    private suspend fun reboot() {
        restartPending = false
        justRestarted = true
        io { adb.reboot() }
        _screen.value = Screen.Setup
        _setupStep.value = SetupStep.WIRELESS_DEBUGGING
    }

    /** Saves the full error to error_log.txt and returns a short description for the user. */
    private fun errorReport(e: Exception, context: String): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        try {
            storage.appendText("error_log.txt", "==== ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}  HyperDebloat $version  ($context)\n${e.message}\n$sw\n")
        } catch (_: Exception) { }
        logger.line("  Error in $context: ${e.message}")
        return "${e.message ?: e.javaClass.simpleName}\n\nFull details: Reports › error_log.txt"
    }
}
