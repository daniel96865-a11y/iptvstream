package de.dgstudios.iptvstream.core.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Macht den Fernseher im Heimnetz unter dem kurzen Code auffindbar. Der lange Token steht hier nicht. */
class LanPairingAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    fun register(port: Int, code: String) {
        unregister()
        multicast = multicastLock(appContext)
        try {
            multicast?.acquire()
        } catch (e: RuntimeException) {
        }
        val info = NsdServiceInfo().apply {
            serviceName = "IPTVstream"
            serviceType = LanPairing.SERVICE_TYPE
            this.port = port
            setAttribute("code", LanPairing.normalizeCode(code))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            registration = null
        }
    }

    fun unregister() {
        val listener = registration
        registration = null
        if (listener != null) {
            try {
                nsd.unregisterService(listener)
            } catch (e: RuntimeException) {
            }
        }
        try {
            if (multicast?.isHeld == true) multicast?.release()
        } catch (e: RuntimeException) {
        }
        multicast = null
    }
}

/** Sucht einen Fernseher, der genau diesen Code per mDNS meldet. */
class LanPairingBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun find(code: String, timeoutMs: Long = 8_000): LanPairing.Endpoint? {
        val want = LanPairing.normalizeCode(code)
        if (want.length != LanPairing.CODE_LENGTH) return null
        val found = AtomicReference<LanPairing.Endpoint?>(null)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val endpoint = endpointOf(info, want) ?: return
                                if (found.compareAndSet(null, endpoint)) done.countDown()
                            }
                        })
                    } catch (e: RuntimeException) {
                    }
                }
            }
        }
        val lock = multicastLock(appContext)
        try {
            lock?.acquire()
        } catch (e: RuntimeException) {
        }
        try {
            nsd.discoverServices(LanPairing.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: RuntimeException) {
            return null
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: RuntimeException) {
            }
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: RuntimeException) {
            }
        }
        return found.get()
    }

    private fun endpointOf(info: NsdServiceInfo, want: String): LanPairing.Endpoint? {
        val raw = info.attributes?.get("code")?.let { String(it, Charsets.UTF_8) }?.trim('\u0000', ' ') ?: return null
        if (LanPairing.normalizeCode(raw) != want) return null
        val host = ipv4(info) ?: return null
        if (info.port !in 1..65535 || !LanPairing.isPrivateHost(host)) return null
        return LanPairing.Endpoint(host, info.port)
    }

    private fun ipv4(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val direct = info.host
        if (direct is Inet4Address && LanPairing.isPrivateV4(direct)) return direct.hostAddress
        if (Build.VERSION.SDK_INT >= 34) return hostV4Api34(info)
        return null
    }

    @androidx.annotation.RequiresApi(34)
    private fun hostV4Api34(info: NsdServiceInfo): String? {
        for (addr in info.hostAddresses) {
            if (addr is Inet4Address && LanPairing.isPrivateV4(addr)) return addr.hostAddress
        }
        return null
    }
}

private fun multicastLock(context: Context): WifiManager.MulticastLock? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    return wifi.createMulticastLock("iptvstream-pair").apply { setReferenceCounted(false) }
}
