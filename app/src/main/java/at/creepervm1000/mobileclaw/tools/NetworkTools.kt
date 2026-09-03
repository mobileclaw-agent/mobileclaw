package at.creepervm1000.mobileclaw.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.net.NetworkInterface

object GetConnectionMethod : AgentTool {
    override val name = "get_connection_method"
    override val description =
        "Find out how this device is currently connected to the internet — Wi-Fi (wlan), mobile " +
            "data (cellular), ethernet, VPN or offline — plus whether the link is metered and " +
            "actually validated (has real internet, not just an association)."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val connectivity =
            ctx.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivity.activeNetwork
            ?: return ok {
                put("connection", "none")
                put("online", false)
                put("summary", "No active network — the device is offline.")
            }

        val caps = connectivity.getNetworkCapabilities(network)
            ?: return ok {
                put("connection", "unknown")
                put("online", false)
                put("summary", "An active network exists but its capabilities are unreadable.")
            }

        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wlan")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile_net")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
            ) add("usb")
        }

        // VPN rides on top of the real transport, so report the underlying one as primary.
        val primary = transports.firstOrNull { it != "vpn" } ?: transports.firstOrNull() ?: "unknown"

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return ok {
            put("connection", primary)
            put("transports", buildJsonArray { transports.forEach { add(it) } })
            put("online", validated)
            put("validated", validated)
            put("metered", metered)
            put("vpn_active", transports.contains("vpn"))
            put("downstream_kbps", caps.linkDownstreamBandwidthKbps)
            put("upstream_kbps", caps.linkUpstreamBandwidthKbps)
            if (primary == "mobile_net") {
                put("mobile_network_type", mobileNetworkType(ctx.app))
            }
            if (primary == "wlan") {
                put("wifi_link_speed_mbps", wifiLinkSpeed(ctx.app))
            }
            put(
                "summary",
                "Connected via $primary" +
                    (if (metered) " (metered)" else " (unmetered)") +
                    (if (validated) " with verified internet access." else " but internet is NOT validated."),
            )
        }
    }

    private fun mobileNetworkType(context: Context): String = runCatching {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        when (telephony.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            -> "3G"

            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            -> "2G"

            else -> "unknown"
        }
    }.getOrDefault("unavailable (permission)")

    private fun wifiLinkSpeed(context: Context): Int = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.connectionInfo.linkSpeed
    }.getOrDefault(-1)
}

object GetLocalIps : AgentTool {
    override val name = "get_local_ips"
    override val description =
        "List this device's local network addresses per interface (wlan0 is usually Wi-Fi, " +
            "swlan/ap the hotspot, rndis/usb tethering). Use this when you need to tell the " +
            "user or a LAN service which IP the phone is reachable on. Public IPs are NOT " +
            "here — use a web service via http_request for that."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())

        return ok {
            put("interfaces", buildJsonArray {
                interfaces.filter { it.isUp }.forEach { iface ->
                    addJsonObject {
                        put("name", iface.name)
                        put("loopback", iface.isLoopback)
                        put("addresses", buildJsonArray {
                            iface.inetAddresses.toList().forEach { addr ->
                                addJsonObject {
                                    put("address", addr.hostAddress ?: "")
                                    put("version", if (addr is java.net.Inet4Address) "ipv4" else "ipv6")
                                }
                            }
                        })
                    }
                }
            })
        }
    }
}

object IsHotspotRunning : AgentTool {
    override val name = "is_hotspot_running"
    override val description =
        "Check whether this device is currently sharing its connection as a Wi-Fi hotspot " +
            "(tethering / SoftAP). Android hides this API, so the check is best-effort: it tries " +
            "the hidden framework call first and falls back to inspecting tether interfaces."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val viaReflection = hotspotViaReflection(ctx.app)
        val tetherInterfaces = activeTetherInterfaces()
        val viaInterface = tetherInterfaces.isNotEmpty()

        val running = viaReflection ?: viaInterface
        val method = when {
            viaReflection != null -> "framework (WifiManager.isWifiApEnabled)"
            viaInterface -> "network interface probe"
            else -> "network interface probe (nothing found)"
        }

        return ok {
            put("hotspot_running", running)
            put("detection_method", method)
            put("confident", viaReflection != null)
            put("tether_interfaces", buildJsonArray { tetherInterfaces.forEach { add(it) } })
            if (viaReflection == null) {
                put(
                    "note",
                    "The hidden isWifiApEnabled API was blocked by this Android version, so this " +
                        "result is inferred from active tether interfaces and may be wrong.",
                )
            }
        }
    }

    /** Returns null when the hidden API is unreachable, rather than guessing false. */
    private fun hotspotViaReflection(context: Context): Boolean? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
        method.isAccessible = true
        method.invoke(wifi) as Boolean
    }.getOrNull()

    /** SoftAP shows up as ap0/softap0/swlan0/wlan1 holding a non-loopback IPv4 address. */
    private fun activeTetherInterfaces(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { iface ->
                iface.isUp && !iface.isLoopback &&
                    APS.any { iface.name.startsWith(it) } &&
                    iface.inetAddresses.toList().any { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            }
            .map { it.name }
    }.getOrDefault(emptyList())

    private val APS = listOf("ap", "softap", "swlan", "wlan1", "rndis", "usb")
}
