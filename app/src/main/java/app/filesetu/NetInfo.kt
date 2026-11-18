package app.filesetu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
    val (wifiClient, tethered) = classifyInterfaces(ctx)
    val out = mutableListOf<Addr>()
    val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return out
    for (ni in ifaces) {
        val name = ni.name.lowercase()
        if (runCatching { !ni.isUp || ni.isLoopback }.getOrDefault(true)) continue
        if (skipPrefixes.any { name.startsWith(it) }) continue
        for (a in ni.inetAddresses.toList()) {
            if (a !is Inet4Address || a.isLoopbackAddress || a.isLinkLocalAddress) continue
            val ip = a.hostAddress ?: continue
            out += Addr(kindOf(name, wifiClient, tethered), ip, name)
        }
    }
    val order = listOf("Hotspot", "USB", "Wi-Fi", "नेटवर्क")
    return out.distinctBy { it.ip }.sortedBy { order.indexOf(it.kind) }
}

/**
 * Returns (phone's own Wi-Fi interfaces, tethering interfaces).
 * Android 15+ also lists the hotspot as a Wi-Fi network, marked NET_CAPABILITY_LOCAL_NETWORK
 * (seen on a Pixel 8a: hotspot on wlan1 came back as Wi-Fi) — that mark means hotspot/tethering.
 */
@Suppress("DEPRECATION")
private fun classifyInterfaces(ctx: Context): Pair<Set<String>, Set<String>> {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptySet<String>() to emptySet()
    val client = mutableSetOf<String>()
    val tethered = mutableSetOf<String>()
    runCatching {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val iface = cm.getLinkProperties(n)?.interfaceName?.lowercase() ?: continue
            val local = Build.VERSION.SDK_INT >= 35 &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
            when {
                local -> tethered += iface
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> client += iface
            }
        }
    }
    client -= tethered
    return client to tethered
}

private fun isUsb(iface: String) =
    iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") || iface.startsWith("eth")

private fun kindOf(iface: String, wifiClient: Set<String>, tethered: Set<String>): String = when {
    iface in tethered -> if (isUsb(iface)) "USB" else "Hotspot"
    iface in wifiClient -> "Wi-Fi"
    isUsb(iface) -> "USB"
    iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("softap") || iface.startsWith("wlan") -> "Hotspot"
    else -> "नेटवर्क"
}
