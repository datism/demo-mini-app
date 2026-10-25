package com.demo.imsdc.util

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Gets the local IP address of the device.
     *
     * Resolution order:
     * 1. Kernel Routing Probe (if targetServerHost is provided):
     *    Connects a DatagramSocket to (targetServerHost, 5060). This performs a FIB routing table lookup
     *    in the OS kernel without sending any network packets, returning the exact source IP on the interface
     *    that routes to the server.
     * 2. Active Network LinkProperties (if context is provided):
     *    Queries Android ConnectivityManager for the active default network's IPv4 address.
     * 3. NetworkInterface Enumeration:
     *    Scans network interfaces with strict priority:
     *      - Priority 1: Tailscale / VPN (100.x.x.x or interface name starting with "tun" / "tailscale")
     *      - Priority 2: Wi-Fi ("wlan")
     *      - Priority 3: Primary cellular interface ("rmnet0", "ccmni0")
     *      - Priority 4: Any first valid IPv4 address (does NOT allow secondary rmnet1 to overwrite rmnet0).
     */
    fun getLocalIpAddress(context: Context? = null, targetServerHost: String? = null): String {
        // 1. Kernel Routing Probe to target server
        if (!targetServerHost.isNullOrBlank()) {
            try {
                DatagramSocket().use { socket ->
                    socket.connect(InetSocketAddress(targetServerHost, 5060))
                    val addr = socket.localAddress
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.hostAddress != "0.0.0.0") {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) {
                            Log.i(TAG, "Resolved local IP via kernel route probe to $targetServerHost: $ip")
                            return ip
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kernel route probe to $targetServerHost failed: ${e.message}")
            }
        }

        // 2. Active default network from ConnectivityManager
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                if (activeNet != null) {
                    val linkProps = cm.getLinkProperties(activeNet)
                    val v4Addr = linkProps?.linkAddresses?.firstOrNull {
                        it.address is Inet4Address && !it.address.isLoopbackAddress && it.address.hostAddress != "0.0.0.0"
                    }?.address?.hostAddress
                    if (!v4Addr.isNullOrBlank()) {
                        Log.i(TAG, "Resolved local IP via active network LinkProperties: $v4Addr")
                        return v4Addr
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query ConnectivityManager: ${e.message}")
            }
        }

        // 3. Fallback: NetworkInterface enumeration with priority ranking
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            var vpnIp: String? = null
            var wifiIp: String? = null
            var primaryCellularIp: String? = null
            var firstValidIp: String? = null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                val name = networkInterface.name.lowercase()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        if (ip == "0.0.0.0") continue

                        if (ip.startsWith("100.") || name.startsWith("tun") || name.startsWith("tailscale")) {
                            if (vpnIp == null) vpnIp = ip
                        } else if (name.startsWith("wlan")) {
                            if (wifiIp == null) wifiIp = ip
                        } else if (name == "rmnet0" || name == "ccmni0" || name.startsWith("rmnet_data0")) {
                            if (primaryCellularIp == null) primaryCellularIp = ip
                        } else {
                            if (firstValidIp == null && !name.startsWith("rmnet") && !name.startsWith("dummy")) {
                                firstValidIp = ip
                            }
                        }
                    }
                }
            }

            val resolved = vpnIp ?: wifiIp ?: primaryCellularIp ?: firstValidIp ?: "0.0.0.0"
            Log.i(TAG, "Resolved local IP via interface enumeration: $resolved (vpn=$vpnIp, wifi=$wifiIp, cell=$primaryCellularIp)")
            return resolved
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces: ${e.message}", e)
            return "0.0.0.0"
        }
    }
}

