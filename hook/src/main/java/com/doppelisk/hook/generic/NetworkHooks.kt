package com.doppelisk.hook.generic

import android.content.ContentResolver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Proxy
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.CellIdentityGsm
import android.telephony.CellInfoGsm
import android.telephony.CellSignalStrengthGsm
import android.telephony.TelephonyManager
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.intArg
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.io.IOException
import java.net.NetworkInterface
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.Locale

/**
 * Hides the fact that the phone is behind a VPN, and rewrites the Wi-Fi and cell
 * identity.
 *
 * A VPN is visible from Java in a surprising number of ways: `NetworkCapabilities`
 * reports the VPN transport, `LinkProperties` names the `tun0` interface, and
 * `NetworkInterface.getNetworkInterfaces()` enumerates it. All of those are
 * removed so the app sees an ordinary Wi-Fi connection.
 *
 * The Wi-Fi and cell parts run whenever *either* networking or location is
 * active, because a spoofed location that is inconsistent with the visible Wi-Fi
 * network and cell tower is a contradiction in itself.
 */
object NetworkHooks {

    private val proxyKeys = setOf(
        "http.proxyhost", "http.proxyport", "https.proxyhost", "https.proxyport",
        "http.nonproxyhosts", "socksproxyhost", "socksproxyport",
        "ftp.proxyhost", "ftp.proxyport",
    )

    private val proxySettings = setOf(
        "http_proxy", "global_http_proxy_host", "global_http_proxy_port",
        "global_http_proxy_exclusion_list", "global_proxy_pac_url",
    )

    /** What `ProxySelector.getDefault()` returns when the app must not see a proxy. */
    private val directProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<java.net.Proxy> =
            Collections.singletonList(java.net.Proxy.NO_PROXY)

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    fun install(xposed: XposedInterface, context: HookContext) {
        val policy = context.artifact.network
        val mode = policy.mode.trim().lowercase(Locale.ROOT)
        val networkActive = mode != "direct" || policy.killSwitch
        val locationActive = context.artifact.location.mode.trim().lowercase(Locale.ROOT) != "off"

        if (!networkActive && !locationActive) return

        if (networkActive) {
            hookProxySelector(xposed, context)
            hookSystemProperties(xposed, context)
            hookSettingsProxy(xposed, context)
            hookCapabilities(xposed, context)
            hookNetworkInfo(xposed, context)
            hookLinkProperties(xposed, context)
            hookInterfaces(xposed, context)
        }

        hookWifi(xposed, context)
        hookCell(xposed, context)
    }

    // --------------------------------------------------------------- proxy

    private fun hookProxySelector(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "ProxySelector.getDefault",
            { ProxySelector::class.java.getMethod("getDefault") },
        ) {
            context.boundary.record("network", "ProxySelector.getDefault", null, "NO_PROXY")
            directProxySelector
        }

        val proxy = runCatching { Class.forName("android.net.Proxy") }.getOrNull() ?: return

        for (name in listOf("getDefaultHost", "getDefaultPort")) {
            hookLazy(xposed, context, "Proxy.$name", { proxy.getMethod(name) }) {
                context.boundary.record("network", "Proxy.$name", null, null)
                if (name.endsWith("Port")) -1 else null
            }
        }
    }

    private fun hookSystemProperties(xposed: XposedInterface, context: HookContext) {
        val methods = listOf(
            runCatching { System::class.java.getMethod("getProperty", String::class.java) }.getOrNull(),
            runCatching {
                System::class.java.getMethod("getProperty", String::class.java, String::class.java)
            }.getOrNull(),
        )

        for (method in methods) {
            val parameterCount = method?.parameterCount ?: continue
            hook(xposed, context, "System.getProperty", method) { chain ->
                val key = (chain.arg(0) as? String)?.lowercase(Locale.ROOT)
                if (key != null && key in proxyKeys) {
                    context.boundary.record("network", "System.getProperty($key)", null, null)
                    if (parameterCount == 2) chain.arg(1) else null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun hookSettingsProxy(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "Settings.Global.getString",
            { Settings.Global::class.java.getMethod("getString", ContentResolver::class.java, String::class.java) },
        ) { chain ->
            val name = chain.arg(1) as? String
            if (name != null && name in proxySettings) {
                context.boundary.record("network", "Settings.Global.$name", null, null)
                null
            } else {
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "Settings.Global.getInt",
            {
                Settings.Global::class.java.getMethod(
                    "getInt",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            },
        ) { chain ->
            val name = chain.arg(1) as? String
            if (name != null && name in proxySettings) {
                context.boundary.record("network", "Settings.Global.$name", null, "-1")
                -1
            } else {
                chain.proceed()
            }
        }
    }

    // ---------------------------------------------------------- capabilities

    private fun hookCapabilities(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.hasTransport",
            { NetworkCapabilities::class.java.getMethod("hasTransport", Int::class.javaPrimitiveType) },
        ) { chain ->
            if (chain.intArg(0) == TRANSPORT_VPN) {
                context.boundary.record("network", "hasTransport(VPN)", "true", "false")
                false
            } else {
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.hasCapability",
            { NetworkCapabilities::class.java.getMethod("hasCapability", Int::class.javaPrimitiveType) },
        ) { chain ->
            if (chain.intArg(0) == NET_CAPABILITY_NOT_VPN) true else chain.proceed()
        }

        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.getTransportInfo",
            { NetworkCapabilities::class.java.getMethod("getTransportInfo") },
        ) { chain ->
            val info = chain.proceed()
            if (info != null && info.javaClass.name.contains("Vpn", ignoreCase = true)) null else info
        }
    }

    // ----------------------------------------------------------- network info

    private fun hookNetworkInfo(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "NetworkInfo.getType",
            { NetworkInfo::class.java.getMethod("getType") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? Int
            if (real == TYPE_VPN) {
                record(context, "network", "NetworkInfo.getType", real, TYPE_WIFI)
                TYPE_WIFI
            } else {
                real
            }
        }

        hookLazy(
            xposed,
            context,
            "NetworkInfo.getTypeName",
            { NetworkInfo::class.java.getMethod("getTypeName") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real != null && real.contains("VPN", ignoreCase = true)) {
                record(context, "network", "NetworkInfo.getTypeName", real, "WIFI")
                "WIFI"
            } else {
                real
            }
        }
    }

    // -------------------------------------------------------- link properties

    private fun hookLinkProperties(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "LinkProperties.getInterfaceName",
            { LinkProperties::class.java.getMethod("getInterfaceName") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real != null && looksVirtual(real)) {
                record(context, "network", "LinkProperties.iface", real, "wlan0")
                "wlan0"
            } else {
                real
            }
        }

        hookLazy(
            xposed,
            context,
            "LinkProperties.getRoutes",
            { LinkProperties::class.java.getMethod("getRoutes") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? List<*> ?: return@hookLazy null

            val filtered = real.filterNot { route ->
                val name = runCatching {
                    route?.javaClass?.getMethod("getInterface")?.invoke(route) as? String
                }.getOrNull()
                name != null && looksVirtual(name)
            }

            if (filtered.size != real.size) {
                record(context, "network", "LinkProperties.getRoutes", real.size, filtered.size)
            }
            filtered
        }

        hookLazy(
            xposed,
            context,
            "LinkProperties.getHttpProxy",
            { LinkProperties::class.java.getMethod("getHttpProxy") },
        ) { chain ->
            val real = chain.proceed()
            if (real != null) {
                record(context, "network", "LinkProperties.getHttpProxy", real, null)
            }
            null
        }
    }

    // ------------------------------------------------------------ interfaces

    private fun hookInterfaces(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "NetworkInterface.getNetworkInterfaces",
            { NetworkInterface::class.java.getMethod("getNetworkInterfaces") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? java.util.Enumeration<*> ?: return@hookLazy null
            val filtered = Collections.list(real).filterNot { it is NetworkInterface && looksVirtual(it.name) }
            Collections.enumeration(filtered)
        }

        hookLazy(
            xposed,
            context,
            "NetworkInterface.getByName",
            { NetworkInterface::class.java.getMethod("getByName", String::class.java) },
        ) { chain ->
            val name = chain.arg(0) as? String
            if (name != null && looksVirtual(name)) {
                context.boundary.record("network", "NetworkInterface.getByName", name, null)
                null
            } else {
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "NetworkInterface.getByIndex",
            { NetworkInterface::class.java.getMethod("getByIndex", Int::class.javaPrimitiveType) },
        ) { chain ->
            val iface = runCatching { chain.proceed() }.getOrNull() as? NetworkInterface
            if (iface != null && looksVirtual(iface.name)) {
                context.boundary.record("network", "NetworkInterface.getByIndex", iface.name, null)
                null
            } else {
                iface
            }
        }

        hookLazy(
            xposed,
            context,
            "NetworkInterface.getDisplayName",
            { NetworkInterface::class.java.getMethod("getDisplayName") },
        ) { chain ->
            val iface = chain.thisObject as? NetworkInterface
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (iface != null && looksVirtual(iface.name)) "wlan0" else real
        }
    }

    // ----------------------------------------------------------------- wifi

    private fun hookWifi(xposed: XposedInterface, context: HookContext) {
        val ids = context.artifact.identity.networkIds
        val id = context.artifact.id

        val ssid = "Doppelisk-${id.takeLast(6)}"
        val mac = ids.wifiMac.ifBlank { "02:00:00:00:00:01" }
        val bssid = fakeBssid(mac, 1)

        val stringGetters = mapOf(
            "getSSID" to "\"$ssid\"",
            "getBSSID" to bssid,
            "getMacAddress" to mac,
        )

        for ((name, fake) in stringGetters) {
            hookLazy(xposed, context, "WifiInfo.$name", { WifiInfo::class.java.getMethod(name) }) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull() as? String
                record(context, "network", "WifiInfo.$name", real, fake)
                fake
            }
        }

        val intGetters = mapOf(
            "getFrequency" to 5180,
            "getRssi" to -52,
            "getLinkSpeed" to 866,
            "getNetworkId" to 1,
        )

        for ((name, fake) in intGetters) {
            hookLazy(xposed, context, "WifiInfo.$name", { WifiInfo::class.java.getMethod(name) }) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull() as? Int
                record(context, "network", "WifiInfo.$name", real, fake)
                fake
            }
        }

        hookLazy(
            xposed,
            context,
            "WifiManager.getScanResults",
            { WifiManager::class.java.getMethod("getScanResults") },
        ) {
            // A scan result carries a BSSID and SSID, so returning the real list
            // would undo the WifiInfo spoof. Below API 30 the list is already
            // heavily restricted, so an empty list is the believable answer.
            if (Build.VERSION.SDK_INT < 30) {
                context.boundary.record("network", "WifiManager.getScanResults", null, "empty (API < 30)")
                return@hookLazy emptyList<ScanResult>()
            }

            val results = ArrayList<ScanResult>()
            repeat(3) { index ->
                results += ScanResult().apply {
                    SSID = if (index == 0) ssid else "Neighbor-${id.takeLast(4)}-$index"
                    BSSID = fakeBssid(mac, index + 1)
                    capabilities = "[WPA2-PSK-CCMP][ESS]"
                    level = -48 - index * 7
                    frequency = if (index == 0) 5180 else index * 5 + 2412
                    timestamp = System.currentTimeMillis() * 1000
                }
            }

            context.boundary.record("network", "WifiManager.getScanResults", null, "${results.size} simulated")
            results
        }

        hookLazy(
            xposed,
            context,
            "WifiManager.getConfiguredNetworks",
            { WifiManager::class.java.getMethod("getConfiguredNetworks") },
        ) { emptyList<Any>() }
    }

    // ----------------------------------------------------------------- cell

    private fun hookCell(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "TelephonyManager.getAllCellInfo",
            { TelephonyManager::class.java.getMethod("getAllCellInfo") },
        ) {
            val simulated = simulatedCell(context)
            if (simulated != null) {
                context.boundary.record("network", "TelephonyManager.getAllCellInfo", null, "simulated")
                listOf(simulated)
            } else {
                context.boundary.record("network", "TelephonyManager.getAllCellInfo", null, "empty")
                emptyList<Any>()
            }
        }

        hookLazy(
            xposed,
            context,
            "TelephonyManager.getCellLocation",
            { TelephonyManager::class.java.getMethod("getCellLocation") },
        ) { null }
    }

    /**
     * Builds a GMS cell from the profile's MCC/MNC.
     *
     * `CellIdentityGsm` and friends have no public constructor, so they are built
     * through the hidden no-arg constructor and their fields are set by
     * reflection. Any failure means "no simulated cell", which is safe: an empty
     * cell list is a normal state.
     */
    private fun simulatedCell(context: HookContext): CellInfoGsm? = runCatching {
        val telephony = context.artifact.identity.telephony
        val id = context.artifact.id

        val identity = CellIdentityGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()
        identity.setInt("mMcc", telephony.mcc.toIntOrNull() ?: 310)
        identity.setInt("mMnc", telephony.mnc.toIntOrNull() ?: 260)
        identity.setInt("mLac", (id.hashCode() and 0xFF) + 100)
        identity.setInt("mCid", (id.hashCode() and 0xFFFF) + 1000)
        identity.setInt("mArfcn", 128)
        identity.setInt("mBsic", 1)

        val signal = CellSignalStrengthGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()
        signal.setInt("mRssi", -75)
        signal.setInt("mBitErrorRate", 0)
        signal.setInt("mTimingAdvance", 0)

        val cell = CellInfoGsm::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        cell.setField("mCellIdentityGsm", identity)
        cell.setField("mCellSignalStrengthGsm", signal)
        cell.setField("mTimeStamp", System.currentTimeMillis() * 1000)
        cell.setInt("mCellConnectionStatus", 1)
        cell
    }.getOrNull()

    private fun Any.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun Any.setInt(name: String, value: Int) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(this, value)
    }

    // --------------------------------------------------------------- helpers

    /** True for interface names that only exist because of a VPN. */
    private fun looksVirtual(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("tun") ||
            lower.startsWith("tap") ||
            lower.startsWith("ppp") ||
            lower.startsWith("ipsec") ||
            lower.startsWith("vpn")
    }

    /** Derives a nearby BSSID from the profile's Wi-Fi MAC. */
    private fun fakeBssid(mac: String, salt: Int): String {
        val clean = mac.uppercase(Locale.ROOT)
            .split(':')
            .filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }

        val bytes = if (clean.size == 6) clean.toMutableList() else mutableListOf(2, 0, 0, 0, 0, 1)
        bytes[5] = (bytes[5] + salt) and 0xFF
        bytes[4] = (bytes[4] + salt * 17) and 0xFF
        return bytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private const val TRANSPORT_VPN = 4
    private const val NET_CAPABILITY_NOT_VPN = 15
    private const val TYPE_WIFI = 1
    private const val TYPE_VPN = 17
}
