package io.github.kayyesr.hyperdebloat.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the port Wireless debugging is listening on, by watching this phone's own mDNS announcements
 * ("_adb-tls-connect._tcp" for connecting, "_adb-tls-pairing._tcp" for pairing).
 *
 * One long-running discovery per service type, started once and stopped once. The library's helper
 * started a new discovery on every attempt and, when stopped before discovery had started, never stopped
 * it; on a phone that keeps checking every few seconds those leftovers can pile up until the system
 * refuses new ones. Resolves run one at a time, because older Android versions fail parallel resolves.
 */
class PortFinder(context: Context, private val type: String, private val log: (String) -> Unit) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    @Volatile var port: Int = -1
        private set

    private val running = AtomicBoolean(false)
    @Volatile private var started = false
    private val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) { started = true; log("Looking for $type") }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            started = false; running.set(false); log("! Could not start looking for $type (error $errorCode)")
        }
        override fun onDiscoveryStopped(serviceType: String) { started = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { }
        override fun onServiceFound(info: NsdServiceInfo) {
            queue.add(info)
            resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            log("$type gone (${info.serviceName})")
            port = -1
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            running.set(false)
            log("! Could not look for $type: ${e.message}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) { }
        port = -1
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val info = queue.poll() ?: run { resolving.set(false); return }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    log("! Could not read ${si.serviceName} (error $errorCode)")
                    // Error 3 = already active: try again shortly.
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) queue.add(si)
                    resolving.set(false); resolveNext()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host != null && isThisPhone(host)) {
                        port = si.port
                        log("Found $type on this phone: port ${si.port}")
                    } else {
                        log("Ignored $type from another device ($host)")
                    }
                    resolving.set(false); resolveNext()
                }
            })
        } catch (e: Exception) {
            log("! Could not read service: ${e.message}")
            resolving.set(false)
        }
    }

    private fun isThisPhone(host: String): Boolean = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).any { ni ->
            Collections.list(ni.inetAddresses).any { it.hostAddress?.substringBefore('%') == host.substringBefore('%') }
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val CONNECT = "_adb-tls-connect._tcp"
        const val PAIRING = "_adb-tls-pairing._tcp"
    }
}
