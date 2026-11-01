package io.github.kayyesr.hyperdebloat.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ADB connection to this phone's own ADB service over wireless debugging (libadb-android).
 * The key pair is created once and kept in the app's private folder; pairing registers it with the phone.
 */
class AdbManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    enum class State { CHECKING, NOT_PAIRED, NOT_REACHABLE, CONNECTED }

    companion object {
        @Volatile
        private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(context.applicationContext).also { instance = it } }

        private const val DEVICE_NAME = "HyperDebloat"
        const val LOCAL_PORT = 5555
    }

    private val _state = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = _state

    /** Last connection or pairing error, shown on the connect screen. */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    /** Connection steps, newest last. Shown under "Show details" on the setup screen, so a stuck connect can be explained. */
    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debug: StateFlow<List<String>> = _debug
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    /** Also sends connection steps to the progress screen's details. */
    @Volatile var echo: ((String) -> Unit)? = null

    /** Connection steps also go to connection_log.txt (Reports), so they survive Android closing the app. */
    private val logFile = File(File(context.filesDir, "HyperDebloat").apply { mkdirs() }, "connection_log.txt")
    private val logDay = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

    fun debug(line: String) {
        val now = java.util.Date()
        val l = "${timeFmt.format(now)}  $line"
        // Several threads log at once; update() never drops a line.
        _debug.update { (it + l).takeLast(60) }
        echo?.invoke(line)
        android.util.Log.i("HyperDebloat", line)
        synchronized(logFile) {
            try {
                logFile.appendText("${logDay.format(now)}  $line\n")
                // Keep the file small: past 200 KB, only the newer half is kept.
                if (logFile.length() > 200_000) {
                    val text = logFile.readText()
                    logFile.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
            } catch (_: Exception) { }
        }
    }

    /** Whether Wireless debugging is switched on, read from the phone's settings (null if it cannot be read). */
    fun wirelessDebuggingOn(): Boolean? = try {
        android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    } catch (_: Exception) { null }

    /**
     * Notes in the log when Wi-Fi drops or connects and when Wireless debugging is switched on or off. Android switches
     * Wireless debugging off when Wi-Fi disconnects or moves to another access point; this shows when that happened.
     * Only listens while the app is alive; it never keeps the app or the phone awake.
     */
    private fun watchConnection() {
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { debug("Wi-Fi connected") }
                override fun onLost(network: android.net.Network) { debug("! Wi-Fi dropped (Android switches Wireless debugging off when this happens)") }
            })
        } catch (e: Exception) { android.util.Log.w("HyperDebloat", "Wi-Fi watch failed: $e") }
        try {
            val uri = android.provider.Settings.Global.getUriFor("adb_wifi_enabled")
            context.contentResolver.registerContentObserver(uri, false, object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    when (wirelessDebuggingOn()) {
                        true -> debug("Wireless debugging switched on")
                        false -> debug("! Wireless debugging switched off")
                        null -> { }
                    }
                }
            })
        } catch (e: Exception) { android.util.Log.w("HyperDebloat", "Wireless debugging watch failed: $e") }
    }

    private val connectFinder by lazy { PortFinder(context, PortFinder.CONNECT, ::debug) }
    private val pairFinder by lazy { PortFinder(context, PortFinder.PAIRING, ::debug) }

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** Last port Wireless debugging was reached on. It stays the same until Wireless debugging is switched off. */
    private var savedPort: Int
        get() = prefs.getInt("tlsPort", -1)
        set(v) = prefs.edit().putInt("tlsPort", v).apply()
    var everPaired: Boolean
        get() = prefs.getBoolean("paired", false)
        private set(v) = prefs.edit().putBoolean("paired", v).apply()

    private val keyMaterial: Pair<PrivateKey, Certificate> by lazy { loadOrCreateKeys() }

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(20, TimeUnit.SECONDS)
        watchConnection()
    }

    // A fresh key object each time: the library destroys the key it was given when a connection closes.
    override fun getPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyMaterial.first.encoded))
    override fun getCertificate(): Certificate = keyMaterial.second
    override fun getDeviceName(): String = DEVICE_NAME

    private fun loadOrCreateKeys(): Pair<PrivateKey, Certificate> {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "private_key_pkcs8.der")
        val certFile = File(dir, "certificate_x509.der")
        try {
            if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                return key to cert
            }
        } catch (_: Exception) {
            // Damaged files: make a new pair below (the phone then needs pairing again).
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        val now = Date()
        val until = Date(now.time + TimeUnit.DAYS.toMillis(3650))
        val name = X500Name("CN=$DEVICE_NAME")
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time), now, until, name, kp.public)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)))
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
        everPaired = false
        return kp.private to cert
    }

    /**
     * "No Wi-Fi mode": the phone's ADB service also listens on 127.0.0.1:5555 (like `adb tcpip 5555`),
     * so HyperDebloat stays reachable after Wi-Fi is turned off. Android resets this on every restart,
     * so Wi-Fi is needed once per boot to switch it back on.
     */
    var noWifiMode: Boolean
        get() = prefs.getBoolean("noWifiMode", false)
        set(v) = prefs.edit().putBoolean("noWifiMode", v).apply()

    /** True when the current connection is the local (no Wi-Fi) one. */
    @Volatile var connectedLocally = false
        private set

    /** Connects to the local port. [authWaitSec] is how long to wait for the "Allow USB debugging?" answer. */
    private fun connectLocal(authWaitSec: Long): Boolean {
        setTimeout(authWaitSec, TimeUnit.SECONDS)
        return try {
            val ok = connect("127.0.0.1", LOCAL_PORT) || isConnected
            connectedLocally = ok
            ok
        } catch (_: Exception) {
            try { disconnect() } catch (_: Exception) { }
            false
        } finally {
            setTimeout(20, TimeUnit.SECONDS)
        }
    }

    /**
     * Connects: first the local port (No Wi-Fi mode), then wireless debugging if [wireless] is allowed.
     * Wireless debugging's port is taken from this phone's own announcement, or the last port that worked.
     * Blocking; call off the main thread.
     */
    @Synchronized
    fun tryConnect(wireless: Boolean = true): Boolean {
        if (isConnected) { _state.value = State.CONNECTED; return true }
        _state.value = State.CHECKING
        if (noWifiMode) {
            debug("Trying no-Wi-Fi port $LOCAL_PORT")
            if (connectLocal(20)) {
                debug("Connected without Wi-Fi")
                _state.value = State.CONNECTED; _lastError.value = ""
                return true
            }
        }
        if (!wireless) {
            _state.value = State.NOT_REACHABLE
            _lastError.value = "Wi-Fi is off."
            return false
        }
        // The port that worked last time is tried first: instant when Wireless debugging was not switched off since.
        val saved = savedPort
        if (saved > 0) {
            when (connectPort(saved)) {
                ConnectResult.OK -> return true
                ConnectResult.NOT_PAIRED -> return false
                ConnectResult.FAILED -> debug("Saved port $saved no longer works")
            }
        }
        connectFinder.start()
        // Give the announcement a few seconds to arrive.
        val deadline = System.currentTimeMillis() + 5000
        while (connectFinder.port <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(250)
        val ports = listOf(connectFinder.port).filter { it > 0 && it != saved }
        if (ports.isEmpty()) {
            _state.value = State.NOT_REACHABLE
            _lastError.value = when (wirelessDebuggingOn()) {
                false -> "Wireless debugging is off. Android switches it off when Wi-Fi drops or changes."
                true -> "Wireless debugging is on. Still looking for it..."
                null -> "Wireless debugging not found yet. Check it is switched on."
            }
            debug(_lastError.value)
            return false
        }
        for (p in ports) {
            when (connectPort(p)) {
                ConnectResult.OK -> return true
                ConnectResult.NOT_PAIRED -> return false
                ConnectResult.FAILED -> continue
            }
        }
        _state.value = State.NOT_REACHABLE
        return false
    }

    enum class ConnectResult { OK, NOT_PAIRED, FAILED }

    /** Connects to Wireless debugging on [port] (from the announcement, or typed by the user). */
    fun connectPort(port: Int): ConnectResult {
        if (isConnected) return ConnectResult.OK
        for (host in hosts()) {
            debug("Connecting to $host:$port")
            try {
                setTimeout(12, TimeUnit.SECONDS)
                connectedLocally = false
                if (connect(host, port) || isConnected) {
                    debug("Connected")
                    savedPort = port
                    everPaired = true
                    _lastError.value = ""
                    _state.value = State.CONNECTED
                    connectFinder.stop()
                    return ConnectResult.OK
                }
                debug("! No answer from $host:$port")
            } catch (e: AdbPairingRequiredException) {
                debug("! Phone says: not paired")
                try { disconnect() } catch (_: Exception) { }
                everPaired = false
                _state.value = State.NOT_PAIRED
                _lastError.value = "Not paired yet."
                return ConnectResult.NOT_PAIRED
            } catch (e: Exception) {
                debug("! $host:$port failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                try { disconnect() } catch (_: Exception) { }
                _lastError.value = "Could not connect to port $port."
            } finally {
                setTimeout(20, TimeUnit.SECONDS)
            }
        }
        return ConnectResult.FAILED
    }

    /** This phone's own addresses: loopback first, then its Wi-Fi address. */
    private fun hosts(): List<String> {
        val wifi = try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) { emptyList() }
        return listOf("127.0.0.1") + wifi
    }

    /**
     * Turns on No Wi-Fi mode from a wireless debugging connection. The phone's ADB service restarts, and the
     * phone may ask "Allow USB debugging?" once. Returns null on success, or the error text.
     */
    fun enableNoWifiMode(): String? {
        if (connectedLocally && isConnected) { noWifiMode = true; return null }
        if (!isConnected && !tryConnect()) return "Not connected."
        try {
            val s = openGuarded("tcpip:$LOCAL_PORT")
            drainBriefly(s)
        } catch (e: Exception) {
            return "The phone refused: ${e.message ?: e.javaClass.simpleName}"
        }
        lost()
        noWifiMode = true
        // The ADB service takes a moment to restart; then the phone may ask to allow this app (up to a minute).
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            if (connectLocal(30)) { _state.value = State.CONNECTED; debug("No-Wi-Fi mode on"); return null }
        }
        debug("! No-Wi-Fi mode did not start")
        noWifiMode = false
        return "Could not connect without Wi-Fi. If the phone asked \"Allow USB debugging?\", tap Always allow and try again."
    }

    /** Turns No Wi-Fi mode off: the local port is closed (`adb usb`) and wireless debugging is used again. */
    fun disableNoWifiMode() {
        noWifiMode = false
        if (connectedLocally && isConnected) {
            try {
                val s = openGuarded("usb:")
                drainBriefly(s)
            } catch (_: Exception) { }
            lost()
        }
        connectedLocally = false
    }

    /** Marks the connection as lost (for example after Wireless debugging was switched off). */
    private fun lost() {
        try { disconnect() } catch (_: Exception) { }
        connectedLocally = false
        _state.value = State.NOT_REACHABLE
    }

    /**
     * Looks for the pairing service the phone opens while "Pair device with pairing code" is showing.
     * Returns the port, or -1 if nothing was found within [timeoutMs].
     */
    fun findPairingPort(timeoutMs: Long): Int {
        pairFinder.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pairFinder.port <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(250)
        return pairFinder.port
    }

    /** Stops looking for the pairing service (after pairing, or when cancelled). */
    fun stopPairingSearch() = pairFinder.stop()

    /** Pairs with the 6-digit code, then connects. Returns null on success, or the error text. */
    fun pairAndConnect(port: Int, code: String): String? {
        if (isConnected) { debug("Already connected; pairing skipped"); pairFinder.stop(); return null }
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return "The code has 6 digits."
        debug("Pairing on port $port")
        val err = pairWithLimit(port, clean, 20_000)
        if (err != null) {
            debug("! Pairing failed: $err")
            _lastError.value = "Wrong or expired code. Open Pair device with pairing code again for a new one."
            return _lastError.value
        }
        debug("Paired")
        everPaired = true
        pairFinder.stop()
        // The connect announcement can take a moment after pairing.
        for (i in 0 until 4) {
            if (tryConnect()) return null
            Thread.sleep(1500)
        }
        return "Paired, but not connected yet. Check Wireless debugging is still on."
    }

    /**
     * Pairs directly (not through the library's manager, which holds its lock for the whole exchange), and gives up
     * after [timeoutMs] so a stuck pairing can never freeze the app. Returns null on success, or what went wrong.
     */
    private fun pairWithLimit(port: Int, code: String, timeoutMs: Long): String? {
        val ctx = try {
            io.github.muntashirakon.adb.PairingConnectionCtx(AndroidUtils.getHostIpAddress(context), port,
                code.toByteArray(Charsets.UTF_8), getPrivateKey(), certificate, DEVICE_NAME)
        } catch (e: Exception) {
            return "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }
            if (!done.get()) { timedOut.set(true); try { ctx.close() } catch (_: Exception) { } }
        }.apply { isDaemon = true; start() }
        return try {
            ctx.start()
            null
        } catch (e: Exception) {
            if (timedOut.get()) "no answer within ${timeoutMs / 1000} s" else "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        } finally {
            done.set(true)
            watchdog.interrupt()
            try { ctx.close() } catch (_: Exception) { }
        }
    }

    private val random = java.security.SecureRandom()

    /**
     * Runs a shell command and streams its output lines. Reconnects once if the connection dropped.
     *
     * Every command ends by printing a unique end line. Reading stops at that line, so the app never depends on the
     * library noticing the end of a stream: the library waits forever when the phone's "done" arrives while output is
     * still queued, which is why long outputs used to stall at their very end. No end line means the answer was cut
     * short, which is an error, never a quiet half result.
     */
    fun shell(command: String, idleSec: Long = 30, onLine: (String) -> Unit) {
        var lastError: Exception? = null
        var delivered = 0L
        for (attempt in 0..1) {
            if (!isConnected && !tryConnect()) throw IOException(_lastError.value.ifEmpty { "Not connected to wireless debugging" })
            val marker = "__HD_END_" + java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE) + "__"
            var stream: AdbStream? = null
            try {
                // A new line, not ";", so a command that already ends in ";" or "&" still works.
                stream = openGuarded("shell:$command\necho $marker")
                readUntilMarker(stream, marker, idleSec) { delivered++; onLine(it) }
                return
            } catch (e: Exception) {
                lastError = e
                debug("! \"${command.take(50)}\" failed after $delivered lines: ${e.message}")
                // A retry would repeat lines already handed on, so only retry when nothing arrived yet.
                if (delivered > 0) break
                if (attempt == 0) lost()
            } finally {
                try { stream?.close() } catch (_: Exception) { }
            }
        }
        throw IOException("Command failed: ${command.take(80)} (${lastError?.message})", lastError)
    }

    /**
     * Reads lines until the end line [marker]. Gives up if the phone sends nothing for [idleSec] seconds.
     * Bytes are split into lines here (not with a BufferedReader), so a read never waits for more than the phone sent.
     */
    private fun readUntilMarker(stream: AdbStream, marker: String, idleSec: Long = 30, onLine: (String) -> Unit) {
        val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            while (!done.get()) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                if (System.currentTimeMillis() - last.get() > idleSec * 1000) {
                    timedOut.set(true)
                    try { stream.close() } catch (_: Exception) { }
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
        val input = stream.openInputStream()
        val buf = ByteArray(64 * 1024)
        val lines = io.github.kayyesr.hyperdebloat.core.MarkerLines(marker)
        try {
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    if (timedOut.get()) throw IOException("The phone stopped answering")
                    throw IOException("The answer was cut short (${e.message})")
                }
                if (n < 0) {
                    if (timedOut.get()) throw IOException("The phone stopped answering")
                    throw IOException("The answer was cut short")
                }
                if (n == 0) continue
                last.set(System.currentTimeMillis())
                if (lines.feed(buf, n, onLine)) return
            }
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    /**
     * Reads what a non-shell service (tcpip, usb, reboot) answers, for at most [maxMs]. These end by closing, which the
     * library can miss, so the wait is capped; their answer is not needed.
     */
    private fun drainBriefly(stream: AdbStream, maxMs: Long = 4000) {
        val t = Thread {
            try {
                val input = stream.openInputStream()
                val b = ByteArray(4096)
                while (input.read(b) >= 0) { }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        t.join(maxMs)
        try { stream.close() } catch (_: Exception) { }
    }

    /**
     * Opens a service with a time limit. The library waits forever for the phone to accept a new stream; if the
     * connection silently died (for example after a Wi-Fi change), that wait never ends. After [timeoutMs] the
     * connection is closed, which ends the wait with an error.
     */
    private val openLock = Any()

    private fun openGuarded(destination: String, timeoutMs: Long = 15_000): AdbStream {
        val conn = adbConnection ?: throw IOException("Not connected")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }
            if (!done.get()) {
                timedOut.set(true)
                debug("! The phone did not answer \"${destination.take(40)}\" - reconnecting")
                try { conn.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        try {
            // The library numbers new streams without a lock, so opens are made one at a time.
            return synchronized(openLock) { conn.open(destination) }
        } catch (e: Exception) {
            if (timedOut.get()) { lost(); throw IOException("The phone stopped answering") }
            throw e
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    /** Restarts the phone (adb reboot). The connection ends with it. */
    fun reboot() {
        try {
            val s = openGuarded("reboot:")
            drainBriefly(s)
        } catch (_: Exception) {
            try { shell("svc power reboot") { } } catch (_: Exception) { }
        }
        lost()
    }
}
