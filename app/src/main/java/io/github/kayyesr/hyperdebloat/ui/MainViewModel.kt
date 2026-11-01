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
import io.github.kayyesr.hyperdebloat.core.ScanCache
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** The bar at the bottom of the screen: what is happening, and how far along (null = no known end). */
data class Progress(val label: String, val fraction: Float? = null)

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

    private val _setupStep = MutableStateFlow(SetupStep.NOTIFY)
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

    private val _scanError = MutableStateFlow<String?>(null)
    /** Set when the last check failed, so the home screen can say so and offer to try again. */
    val scanError: StateFlow<String?> = _scanError

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
    /** Only one flow talks to the phone at a time: a background reconnect never runs inside a check, and the other way round. */
    private val phoneLock = Mutex()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    /** Part of the bar the current stage fills, so pieces of work (reading a big answer) move it inside their stage. */
    @Volatile private var stageFrom = 0f
    @Volatile private var stageTo = 1f

    private fun progress(label: String, from: Float? = null, to: Float = from ?: 1f) {
        stageFrom = from ?: 0f
        stageTo = to
        _progress.value = Progress(label, from)
    }

    /** [f] runs 0..1 inside the current stage. */
    private fun subProgress(f: Float) {
        val v = stageFrom + (stageTo - stageFrom) * f.coerceIn(0f, 1f)
        _progress.update { it?.copy(fraction = v) }
    }

    /** True until the first connection attempt after opening the app has finished. */
    private val _firstCheck = MutableStateFlow(true)
    val firstCheck: StateFlow<Boolean> = _firstCheck
    val everPaired: Boolean get() = adb.everPaired

    private val shell = AdbShell(adb, storage, { _saveRaw.value }, { logger.line(it) }, { subProgress(it) })
    private val logger = Log { line -> _log.update { (it + line).takeLast(3000) } }

    private var engine: Engine? = null
    private var rows: List<Row>? = null
    private var needScan = true
        set(v) {
            field = v
            // Results saved from before a removal or restore are out of date.
            if (v) try { ScanCache.clear(storage) } catch (_: Exception) { }
        }
    private var deviceKey = ""

    init {
        adb.echo = { logger.line("  $it") }
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
        val bar = _progress.value
        _progress.value = null
        _screen.value = Screen.Pick(spec)
        return spec.result.await().also { _screen.value = back; _progress.value = bar }
    }

    fun finishPick(spec: PickSpec, picked: List<Row>) { spec.result.complete(picked) }

    private fun busy(title: String, from: Float? = null, to: Float = from ?: 1f) {
        _screen.value = Screen.Busy(title)
        progress(title, from, to)
    }
    private fun step(text: String) { logger.line(""); logger.line("== $text") }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun setSaveRaw(on: Boolean) { _saveRaw.value = on; prefs.edit().putBoolean("saveRaw", on).apply() }

    fun showReports() { if (!working.get()) _screen.value = Screen.Reports }
    fun goHome() { if (!working.get()) _screen.value = if (adb.state.value == AdbManager.State.CONNECTED) Screen.Home else Screen.Setup }

    // ------------------------------------------------------------------------------------------
    // Setup and connection
    // ------------------------------------------------------------------------------------------
    /** Notifications are needed only to type the pairing code into one; Android 12 and older allow them already. */
    private fun notificationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val _notifySkipped = MutableStateFlow(false)
    /** "Not now" on the notification step: carry on; the pairing code can still be typed in the app. */
    fun skipNotifications() { _notifySkipped.value = true; prefs.edit().putBoolean("notifySkipped", true).apply(); refreshSetup() }

    private fun devOptionsOn(): Boolean =
        Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    @Suppress("DEPRECATION")
    private fun wifiConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Works out which setup step is next, and connects when everything is in place. */
    fun refreshSetup() {
        if (working.get() || phoneLock.isLocked) return
        if (!notificationsAllowed() && !adb.everPaired && !prefs.getBoolean("notifySkipped", false)) {
            _setupStep.value = SetupStep.NOTIFY; _firstCheck.value = false; return
        }
        if (!devOptionsOn()) { _setupStep.value = SetupStep.DEV_OPTIONS; _firstCheck.value = false; goSetupIfHome(); return }
        if (!connecting.compareAndSet(false, true)) return
        viewModelScope.launch {
            // An action already holds the phone: it reconnects by itself if needed.
            if (!phoneLock.tryLock()) { connecting.set(false); return@launch }
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
                    goSetupIfHome()
                }
            } finally {
                _checking.value = false
                _firstCheck.value = false
                connecting.set(false)
                phoneLock.unlock()
                // A quiet "work without Wi-Fi" switch-on waits until the lock is free.
                if (pendingNoWifi) { pendingNoWifi = false; setNoWifi(true, quiet = true) }
            }
        }
    }

    private fun goSetupIfHome() { if (_screen.value == Screen.Home) _screen.value = Screen.Setup }
    @Volatile private var pendingNoWifi = false

    fun startPairing() = PairingService.start(getApplication())

    /** Connects to a port the user typed from the Wireless debugging screen. */
    fun connectPort(port: Int) {
        if (!connecting.compareAndSet(false, true)) return
        viewModelScope.launch {
            _checking.value = true
            phoneLock.lock()
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
                phoneLock.unlock()
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
            progress("Pairing")
            try {
                phoneLock.withLock {
                    val err = io { adb.pairAndConnect(port, code) }
                    if (err != null) notice("Pairing failed", err, true) else { PairingService.stop(getApplication()); onConnected() }
                }
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun onConnected() {
        if (connectPhone() == null) return
        updateGuide()
        _screen.value = Screen.Home
        _noWifi.value = adb.noWifiMode
        // Turned on before, but the phone restarted since: switch it back on (once the phone is free).
        if (adb.noWifiMode && !adb.connectedLocally) pendingNoWifi = true
    }

    private val noWifiDialogText = "HyperDebloat stays connected after you turn Wi-Fi off, until the phone restarts. " +
        "After a restart it needs Wi-Fi once again.\n\nYour phone may ask \"Allow USB debugging?\". Tap Always allow, but only when HyperDebloat asks."

    /** Turns No Wi-Fi mode on or off (Home menu). */
    fun setNoWifi(on: Boolean, quiet: Boolean = false) {
        if (!working.compareAndSet(false, true)) return
        viewModelScope.launch {
            phoneLock.lock()
            try {
                if (on) {
                    if (!quiet && !ask("Work without Wi-Fi?", noWifiDialogText, yes = "Turn on", no = "Cancel")) return@launch
                    busy("Turning on Work without Wi-Fi")
                    logger.line("If the phone asks \"Allow USB debugging?\", tap Always allow.")
                    val err = io { adb.enableNoWifiMode() }
                    if (err != null) notice("Could not turn it on", err, true)
                    else if (!quiet) notice("Done", "You can turn Wi-Fi off now. After the phone restarts, HyperDebloat needs Wi-Fi once to switch this back on.")
                } else {
                    io { adb.disableNoWifiMode() }
                }
                _noWifi.value = adb.noWifiMode
            } finally {
                phoneLock.unlock()
                working.set(false)
                _progress.value = null
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
            rows = null
            needScan = true
            // Results from earlier today survive the app being closed in the background.
            ScanCache.load(storage, dev.key)?.let { saved ->
                rows = saved.rows
                needScan = false
                _lastScan.value = saved.shownTime
                _counts.value = Tier.entries.associateWith { t -> saved.rows.count { it.tier == t } }
                logger.line("Using the check from ${saved.shownTime}")
            }
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
        // Show progress straight away, so a tap never looks ignored while the phone is being reached.
        busy("Connecting to your phone")
        adb.debug("Action: ${action.name.lowercase()}")
        viewModelScope.launch {
            phoneLock.lock()
            try {
                val dev = try {
                    connectPhone()
                } catch (e: Exception) {
                    // Reading the phone's details failed: say so on the home screen instead of closing the app.
                    _scanError.value = e.message ?: e.javaClass.simpleName
                    errorReport(e, "Connect")
                    null
                } ?: return@launch
                if (action == Action.RESCAN || (action != Action.RESTORE && (needScan || rows == null))) {
                    try {
                        rows = scan(dev)
                        _scanError.value = null
                    } catch (e: Exception) {
                        _scanError.value = e.message ?: e.javaClass.simpleName
                        errorReport(e, "Scan")
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
                phoneLock.unlock()
                working.set(false)
                _progress.value = null
                updateGuide()
                if (_screen.value != Screen.Setup) _screen.value = Screen.Home
            }
        }
    }

    private suspend fun scan(dev: DeviceInfo): List<Row> {
        busy("Checking your phone", 0f, 0.05f)
        val eng = engine!!
        step("Scanning the phone (read-only)")
        val sc = Scanner(shell, logger)
        var sets = io { sc.packageSets() }
        progress("Checking if removed apps came back", 0.05f, 0.2f)
        logger.line("${sets.all.size} packages installed (${sets.system.size} system)")
        val (msg, changed) = try {
            io { eng.cameBackCheck(sets.all) }
        } catch (e: Exception) {
            logger.line("! Came-back check skipped: ${e.message}")
            null to false
        }
        if (msg != null) notice("Removed apps came back", msg, true)
        if (changed) sets = io { sc.packageSets() }
        progress("Reading app details", 0.2f, 0.9f)
        // The lists load at the same time as the phone is read.
        val lists = viewModelScope.async(Dispatchers.IO) { CommunityLists.buildIndex(CommunityLists.load(storage, fetcher, logger)) }
        val facts = try { io { sc.deviceFacts(sets.all) } } catch (e: Exception) { lists.cancel(); throw e }
        step("Comparing with the debloat lists")
        progress("Comparing with the debloat lists", 0.92f)
        val idx = lists.await()
        val result = Classifier.classify(idx, sets, facts, getApplication<Application>().packageName)
        step("Classification")
        for (t in listOf(Tier.SAFE, Tier.MOSTLY, Tier.MODERATE, Tier.HIGH, Tier.UNSAFE, Tier.FLAGGED, Tier.APP)) {
            logger.line("  ${t.colour.padEnd(11)} ${t.label.padEnd(9)} ${result.count { it.tier == t }} packages")
        }
        io { eng.saveReport(result, dev) }
        _counts.value = Tier.entries.associateWith { t -> result.count { it.tier == t } }
        _lastScan.value = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        needScan = false
        try { io { ScanCache.save(storage, dev.key, System.currentTimeMillis(), _lastScan.value, result) } } catch (_: Exception) { }
        progress("Done", 1f)
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
        // Safe and mostly-okay packages, setup junk, and the essential Google apps (in their own box, never ticked).
        val green = rows.filter {
            it.tier == Tier.SAFE || it.tier == Tier.MOSTLY || (it.tier == Tier.APP && it.atSetup) || it.category == "Essential Google apps"
        }
        if (green.none { it.tier == Tier.SAFE || it.tier == Tier.MOSTLY || it.atSetup }) {
            notice("Nothing safe to remove", "Nothing on this phone is rated safe. Use Custom to look at the rest.")
            return
        }
        step("Recommended: ${green.size} packages")
        val picked = pick(PickSpec("Remove junk", green, "Remove selected", true,
            "", false, simple = true,
            intro = "Safe junk is already ticked. Check each section, untick anything you want to keep, then tap Remove."))
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
        busy("Removing ${items.size} apps", 0f, 0.85f)
        step("Removing ${items.size} package(s)")
        val results = io {
            eng.removeFirstPass(items) { i, n, it, res ->
                logger.line("  [$i/$n] ${it.pkg}  ${eng.shownResult(res)}")
                subProgress(i.toFloat() / n)
            }
        }
        progress("Checking the result", 0.85f)

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
        busy("Restoring ${picked.size} apps", 0f, 1f)
        val lost = ArrayList<String>()
        val restored = ArrayList<String>()
        for ((n, it) in picked.withIndex()) {
            subProgress(n.toFloat() / picked.size)
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
            phoneLock.lock()
            try {
                if (ask("Restart now?", "Your phone will restart. Open HyperDebloat again afterwards.", yes = "Restart", no = "Cancel")) reboot()
            } finally {
                phoneLock.unlock()
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
