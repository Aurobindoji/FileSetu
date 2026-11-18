package app.filesetu

import java.net.Inet4Address
import java.net.NetworkInterface

/** One address the PC can open. [kind] is shown to the user (Hotspot / Wi-Fi / USB). */
data class Addr(val kind: String, val ip: String)

private val skipPrefixes = listOf("lo", "rmnet", "ccmni", "dummy", "tun", "p2p", "v4-", "ifb", "sit", "ip6")

/** IPv4 addresses on local interfaces (hotspot, Wi-Fi, USB tethering); mobile-data interfaces are skipped. */
fun localAddresses(): List<Addr> {
    val out = mutableListOf<Addr>()
    val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return out
    for (ni in ifaces) {
        val name = ni.name.lowercase()
        if (runCatching { !ni.isUp || ni.isLoopback }.getOrDefault(true)) continue
        if (skipPrefixes.any { name.startsWith(it) }) continue
        for (a in ni.inetAddresses.toList()) {
            if (a !is Inet4Address || a.isLoopbackAddress || a.isLinkLocalAddress) continue
            val ip = a.hostAddress ?: continue
            out += Addr(kindOf(name, ip), ip)
        }
    }
    // Hotspot first, then USB, then Wi-Fi.
    val order = listOf("Hotspot", "USB", "Wi-Fi", "नेटवर्क")
    return out.distinctBy { it.ip }.sortedBy { order.indexOf(it.kind) }
}

private fun kindOf(iface: String, ip: String): String = when {
    iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "USB"
    iface.startsWith("ap") || iface.startsWith("swlan") || iface.startsWith("softap") -> "Hotspot"
    iface.startsWith("wlan") && iface != "wlan0" && ip.endsWith(".1") -> "Hotspot"
    iface == "wlan0" && ip.endsWith(".1") && ip.startsWith("192.168.43.") -> "Hotspot"
    iface.startsWith("wlan") -> "Wi-Fi"
    else -> "नेटवर्क"
}
