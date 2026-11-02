package app.browservault.vault

import java.net.Inet4Address
import java.net.NetworkInterface

/** A telefon elérhető IPv4 címei — a 100.x a Tailscale. */
object Network {

    fun addresses(): List<String> {
        val found = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress
                        if (host != null) found.add(host)
                    }
                }
            }
        } catch (error: Exception) {
            /* nincs hálózat */
        }
        return found.distinct()
    }

    fun tailscale(): String? = addresses().firstOrNull { it.startsWith("100.") }

    fun preferred(): String? = tailscale() ?: addresses().firstOrNull()
}
