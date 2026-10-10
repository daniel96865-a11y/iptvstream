package de.dgstudios.iptvstream.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import de.dgstudios.iptvstream.core.pairing.LanPairing
import java.net.Inet4Address

/**
 * Hält das WLAN wach und legt neue Sockets auf dieses Netz, solange der Fernseher auf das Handy wartet.
 * Ohne das kommt eine Verbindung an die angezeigte Adresse auf vielen Geräten nicht am Server an.
 */
class TvLanHold(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var processBound = false

    fun prepare(): String? {
        val network = homeNetwork()
        if (network != null && cm != null) {
            try {
                processBound = cm.bindProcessToNetwork(network)
            } catch (e: RuntimeException) {
                processBound = false
            }
        }
        val lock = wifiLock()
        wifiLock = lock
        if (lock != null) {
            try {
                lock.acquire()
            } catch (e: RuntimeException) {
            }
        }
        return addressOf(network) ?: LanPairing.lanIpv4()?.hostAddress
    }

    fun release() {
        if (processBound) {
            try {
                cm?.bindProcessToNetwork(null)
            } catch (e: RuntimeException) {
            }
            processBound = false
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: RuntimeException) {
        }
        wifiLock = null
    }

    private fun homeNetwork(): Network? {
        val cm = cm ?: return null
        val active = cm.activeNetwork
        if (active != null && isHome(cm, active)) return active
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { isHome(cm, it) }
    }

    private fun isHome(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun addressOf(network: Network?): String? {
        if (network == null || cm == null) return null
        val links = cm.getLinkProperties(network)?.linkAddresses ?: return null
        var linkLocal: String? = null
        for (link in links) {
            val addr = link.address as? Inet4Address ?: continue
            if (!LanPairing.isPrivateV4(addr)) continue
            val host = addr.hostAddress ?: continue
            val b = addr.address
            if ((b[0].toInt() and 0xff) == 169 && (b[1].toInt() and 0xff) == 254) {
                if (linkLocal == null) linkLocal = host
                continue
            }
            return host
        }
        return linkLocal
    }

    private fun wifiLock(): WifiManager.WifiLock? {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return wifi.createWifiLock(mode, "iptvstream-pair").apply { setReferenceCounted(false) }
    }
}
