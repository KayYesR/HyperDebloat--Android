package io.github.kayyesr.hyperdebloat.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
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
    }

    private val _state = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = _state

    /** Last connection or pairing error, shown on the connect screen. */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
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

    /** Finds wireless debugging on this phone and connects. Blocking; call off the main thread. */
    fun tryConnect(): Boolean {
        if (isConnected) { _state.value = State.CONNECTED; return true }
        _state.value = State.CHECKING
        return try {
            val ok = autoConnect(context, 6000)
            _state.value = if (ok || isConnected) State.CONNECTED else State.NOT_REACHABLE
            if (_state.value == State.CONNECTED) { everPaired = true; _lastError.value = "" }
            _state.value == State.CONNECTED
        } catch (e: AdbPairingRequiredException) {
            everPaired = false
            _state.value = State.NOT_PAIRED
            _lastError.value = "Wireless debugging is on, but this app is not paired yet."
            false
        } catch (e: InterruptedException) {
            _state.value = State.NOT_REACHABLE
            _lastError.value = "Wireless debugging was not found. Check that Wi-Fi is connected and Wireless debugging is on."
            false
        } catch (e: Exception) {
            _state.value = State.NOT_REACHABLE
            _lastError.value = "Could not connect: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Marks the connection as lost (for example after Wireless debugging was switched off). */
    private fun lost() {
        try { disconnect() } catch (_: Exception) { }
        _state.value = State.NOT_REACHABLE
    }

    /**
     * Looks for the pairing service the phone opens while "Pair device with pairing code" is showing.
     * Returns the port, or -1 if nothing was found within [timeoutMs].
     */
    fun findPairingPort(timeoutMs: Long): Int {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _: InetAddress?, p: Int ->
            if (p > 0) { port.set(p); latch.countDown() }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return port.get()
    }

    /** Pairs with the 6-digit code, then connects. Returns null on success, or the error text. */
    fun pairAndConnect(port: Int, code: String): String? {
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return "The pairing code has 6 digits."
        val err = AtomicReference<String?>(null)
        try {
            pair(AndroidUtils.getHostIpAddress(context), port, clean)
        } catch (e: Exception) {
            err.set("Pairing failed: ${e.message ?: e.javaClass.simpleName}. Check the code; the phone shows a new code each time.")
        }
        if (err.get() != null) { _lastError.value = err.get()!!; return err.get() }
        everPaired = true
        // The connect service appears a moment after pairing.
        for (i in 0 until 3) {
            if (tryConnect()) return null
            Thread.sleep(1500)
        }
        return "Paired, but could not connect yet. Make sure Wireless debugging is still on, then press Connect."
    }

    /** Runs a shell command and streams its output lines. Reconnects once if the connection dropped. */
    fun shell(command: String, onLine: (String) -> Unit) {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            if (!isConnected && !tryConnect()) throw IOException(_lastError.value.ifEmpty { "Not connected to wireless debugging" })
            var stream: AdbStream? = null
            try {
                stream = openStream("shell:$command")
                readLines(stream, onLine)
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
