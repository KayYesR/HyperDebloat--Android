package io.github.kayyesr.hyperdebloat.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
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
    fun debug(line: String) {
        val l = "${timeFmt.format(java.util.Date())}  $line"
        _debug.value = (_debug.value + l).takeLast(60)
        android.util.Log.i("HyperDebloat", line)
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
    }

    override fun getPrivateKey(): PrivateKey = keyMaterial.first
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
        connectFinder.start()
        // The first time, give the announcement a few seconds to arrive.
        val deadline = System.currentTimeMillis() + 5000
        while (connectFinder.port <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(250)
        val ports = listOf(connectFinder.port, savedPort).filter { it > 0 }.distinct()
        if (ports.isEmpty()) {
            _state.value = State.NOT_REACHABLE
            _lastError.value = "Wireless debugging not found yet. Check it is switched on."
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
            val s = openStream("tcpip:$LOCAL_PORT")
            try { readLines(s) { } } catch (_: Exception) { }
        } catch (e: Exception) {
            return "The phone refused: ${e.message ?: e.javaClass.simpleName}"
        }
        lost()
        noWifiMode = true
        // The ADB service takes a moment to restart; then the phone may ask to allow this app (up to a minute).
        val deadline = System.currentTimeMillis() + 75_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            if (connectLocal(60)) { _state.value = State.CONNECTED; return null }
        }
        noWifiMode = false
        return "Could not connect without Wi-Fi. If the phone asked \"Allow USB debugging?\", tap Always allow and try again."
    }

    /** Turns No Wi-Fi mode off: the local port is closed (`adb usb`) and wireless debugging is used again. */
    fun disableNoWifiMode() {
        noWifiMode = false
        if (connectedLocally && isConnected) {
            try {
                val s = openStream("usb:")
                try { readLines(s) { } } catch (_: Exception) { }
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
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return "The code has 6 digits."
        val err = AtomicReference<String?>(null)
        debug("Pairing on port $port")
        try {
            pair(AndroidUtils.getHostIpAddress(context), port, clean)
        } catch (e: Exception) {
            debug("! Pairing failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            err.set("Wrong or expired code. Open Pair device with pairing code again for a new one.")
        }
        if (err.get() != null) { _lastError.value = err.get()!!; return err.get() }
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

    /** Runs a shell command and streams its output lines. Reconnects once if the connection dropped. */
    fun shell(command: String, onLine: (String) -> Unit) {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            if (!isConnected && !tryConnect()) throw IOException(_lastError.value.ifEmpty { "Not connected to wireless debugging" })
            var stream: AdbStream? = null
            try {
                stream = openStream("shell:$command")
                readLinesWatched(stream, onLine)
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) lost()
            } finally {
                try { stream?.close() } catch (_: Exception) { }
            }
        }
        throw IOException("Command failed: $command (${lastError?.message})", lastError)
    }

    /** Like readLines, but gives up if the phone sends nothing for [idleSec] seconds, so a stuck command cannot freeze the app. */
    private fun readLinesWatched(stream: AdbStream, onLine: (String) -> Unit, idleSec: Long = 60) {
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
        try {
            readLines(stream) { last.set(System.currentTimeMillis()); onLine(it) }
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
        if (timedOut.get()) throw IOException("The phone stopped answering")
    }

    private fun readLines(stream: AdbStream, onLine: (String) -> Unit) {
        val reader = BufferedReader(InputStreamReader(stream.openInputStream(), Charsets.UTF_8))
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: IOException) {
                if (stream.isClosed) null else throw e
            } ?: break
            onLine(line.trimEnd('\r'))
        }
    }

    /** Restarts the phone (adb reboot). The connection ends with it. */
    fun reboot() {
        try {
            val s = openStream("reboot:")
            try { readLines(s) { } } catch (_: Exception) { }
        } catch (_: Exception) {
            try { shell("svc power reboot") { } } catch (_: Exception) { }
        }
        lost()
    }
}
