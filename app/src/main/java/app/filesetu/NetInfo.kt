package app.filesetu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** One address the PC can open. [kind] is shown to the user (Hotspot / Wi-Fi / USB). */
data class Addr(val kind: String, val ip: String, val iface: String = "")

private val skipPrefixes = listOf("lo", "rmnet", "ccmni", "dummy", "tun", "p2p", "v4-", "ifb", "sit", "ip6")

/**
 * IPv4 addresses on local interfaces (hotspot, Wi-Fi, USB tethering); mobile-data interfaces are skipped.
 * The phone's own Wi-Fi connection is asked from Android, so any other wireless interface is the hotspot
 * (hotspot addresses are random on new Android, e.g. 10.227.167.8, so the IP alone cannot tell).
 */
fun localAddresses(ctx: Context): List<Addr> {
    val wifiClient = wifiClientInterfaces(ctx)
    val out = mutableListOf<Addr>()
    val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return out
    for (ni in ifaces) {
        val name = ni.name.lowercase()
        if (runCatching { !ni.isUp || ni.isLoopback }.getOrDefault(true)) continue
        if (skipPrefixes.any { name.startsWith(it) }) continue
        for (a in ni.inetAddresses.toList()) {
            if (a !is Inet4Address || a.isLoopbackAddress || a.isLinkLocalAddress) continue
            val ip = a.hostAddress ?: continue
            out += Addr(kindOf(name, wifiClient), ip, name)
        }
    }
    val order = listOf("Hotspot", "USB", "Wi-Fi", "नेटवर्क")
    return out.distinctBy { it.ip }.sortedBy { order.indexOf(it.kind) }
}

@Suppress("DEPRECATION")
private fun wifiClientInterfaces(ctx: Context): Set<String> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
    return runCatching {
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            cm.getLinkProperties(n)?.interfaceName?.lowercase()
        }.toSet()
    }.getOrDefault(emptySet())
}

private fun kindOf(iface: String, wifiClient: Set<String>): String = when {
    iface in wifiClient -> "Wi-Fi"
    iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") || iface.startsWith("eth") -> "USB"
    iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("softap") || iface.startsWith("wlan") -> "Hotspot"
    else -> "नेटवर्क"
}
