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
 * Shapes what the app sees about VPN, Wi-Fi and the cell identity.
 *
 * A VPN is visible from Java in a surprising number of ways: `NetworkCapabilities`
 * reports the VPN transport, `LinkProperties` names the `tun0` interface, and
 * `NetworkInterface.getNetworkInterfaces()` enumerates it. `NetworkPolicy.vpn`
 * decides whether those answers are the real ones, always say "no VPN", always
 * say "VPN", or are inverted.
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
        val vpn = VpnMode.of(policy.vpn)
        val vpnActive = vpn != VpnMode.DEFAULT

        if (!networkActive && !locationActive && !vpnActive) return

        if (networkActive) {
            hookProxySelector(xposed, context)
            hookSystemProperties(xposed, context)
            hookSettingsProxy(xposed, context)
            hookHttpProxy(xposed, context)
        }

        if (vpnActive) {
            hookCapabilities(xposed, context, vpn)
            hookNetworkInfo(xposed, context, vpn)
            hookLinkProperties(xposed, context, vpn)
            hookInterfaces(xposed, context, vpn)
        }

        if (networkActive || locationActive) {
            hookWifi(xposed, context)
            hookCell(xposed, context)
        }
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

    private fun hookCapabilities(xposed: XposedInterface, context: HookContext, vpn: VpnMode) {
        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.hasTransport",
            { NetworkCapabilities::class.java.getMethod("hasTransport", Int::class.javaPrimitiveType) },
        ) { chain ->
            val raw = runCatching { chain.proceed() }.getOrNull()
            val real = raw as? Boolean ?: return@hookLazy raw
            if (chain.intArg(0) != TRANSPORT_VPN) return@hookLazy real

            val fake = vpn.present(real)
            if (fake != real) record(context, "network", "hasTransport(VPN)", real, fake)
            fake
        }

        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.hasCapability",
            { NetworkCapabilities::class.java.getMethod("hasCapability", Int::class.javaPrimitiveType) },
        ) { chain ->
            val raw = runCatching { chain.proceed() }.getOrNull()
            val real = raw as? Boolean ?: return@hookLazy raw
            if (chain.intArg(0) != NET_CAPABILITY_NOT_VPN) return@hookLazy real

            // NOT_VPN is the inverse of VPN: `default` keeps it, `off` forces it
            // true, `on` forces it false, and `reversed` flips it.
            val fake = !vpn.present(!real)
            if (fake != real) record(context, "network", "hasCapability(NOT_VPN)", real, fake)
            fake
        }

        hookLazy(
            xposed,
            context,
            "NetworkCapabilities.getTransportInfo",
            { NetworkCapabilities::class.java.getMethod("getTransportInfo") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val realVpn = real != null && real.javaClass.name.contains("Vpn", ignoreCase = true)
            val want = vpn.present(realVpn)

            when {
                !want && realVpn -> {
                    record(context, "network", "getTransportInfo", realVpn, null)
                    null
                }

                want && !realVpn -> {
                    // A synthetic VpnTransportInfo may be impossible on this
                    // ROM; reporting the real object is better than a null that
                    // contradicts hasTransport(VPN).
                    val fake = fakeVpnTransportInfo()
                    if (fake != null) record(context, "network", "getTransportInfo", realVpn, true)
                    fake ?: real
                }

                else -> real
            }
        }
    }

    /** Best-effort `VpnTransportInfo`, built through its hidden constructor. */
    private fun fakeVpnTransportInfo(): Any? = runCatching {
        val clazz = Class.forName("android.net.VpnTransportInfo")
        val constructor = clazz.declaredConstructors.maxByOrNull { it.parameterCount }
            ?: return@runCatching null
        constructor.isAccessible = true

        val arguments = constructor.parameterTypes.map { type ->
            when {
                type == Int::class.javaPrimitiveType -> VPN_TRANSPORT_TYPE
                type == Boolean::class.javaPrimitiveType -> false
                type == Long::class.javaPrimitiveType -> 0L
                type == String::class.java -> "doppelisk"
                CharSequence::class.java.isAssignableFrom(type) -> "doppelisk"
                else -> null
            }
        }.toTypedArray()

        constructor.newInstance(*arguments)
    }.getOrNull()

    // ----------------------------------------------------------- network info

    private fun hookNetworkInfo(xposed: XposedInterface, context: HookContext, vpn: VpnMode) {
        hookLazy(
            xposed,
            context,
            "NetworkInfo.getType",
            { NetworkInfo::class.java.getMethod("getType") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? Int
            if (real == null) return@hookLazy null

            val realVpn = real == TYPE_VPN
            val want = vpn.present(realVpn)
            val fake = when {
                want && !realVpn -> TYPE_VPN
                !want && realVpn -> TYPE_WIFI
                else -> real
            }

            if (fake != real) record(context, "network", "NetworkInfo.getType", real, fake)
            fake
        }

        hookLazy(
            xposed,
            context,
            "NetworkInfo.getTypeName",
            { NetworkInfo::class.java.getMethod("getTypeName") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real == null) return@hookLazy null

            val realVpn = real.contains("VPN", ignoreCase = true)
            val want = vpn.present(realVpn)
            val fake = when {
                want && !realVpn -> "VPN"
                !want && realVpn -> "WIFI"
                else -> real
            }

            if (fake != real) record(context, "network", "NetworkInfo.getTypeName", real, fake)
            fake
        }
    }

    // -------------------------------------------------------- link properties

    private fun hookLinkProperties(xposed: XposedInterface, context: HookContext, vpn: VpnMode) {
        hookLazy(
            xposed,
            context,
            "LinkProperties.getInterfaceName",
            { LinkProperties::class.java.getMethod("getInterfaceName") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real == null) return@hookLazy null

            val realVpn = looksVirtual(real)
            val want = vpn.present(realVpn)
            val fake = when {
                want && !realVpn -> "tun0"
                !want && realVpn -> "wlan0"
                else -> real
            }

            if (fake != real) record(context, "network", "LinkProperties.iface", real, fake)
            fake
        }

        hookLazy(
            xposed,
            context,
            "LinkProperties.getRoutes",
            { LinkProperties::class.java.getMethod("getRoutes") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? List<*> ?: return@hookLazy null

            // Routes cannot be synthesised: when the profile asks for a VPN and
            // there is none, there is nothing to add. When it asks for none, the
            // virtual routes are removed.
            val hasVirtual = real.any { route ->
                val name = runCatching {
                    route?.javaClass?.getMethod("getInterface")?.invoke(route) as? String
                }.getOrNull()
                name != null && looksVirtual(name)
            }
            if (vpn.present(hasVirtual)) return@hookLazy real

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
    }

    private fun hookHttpProxy(xposed: XposedInterface, context: HookContext) {
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

    private fun hookInterfaces(xposed: XposedInterface, context: HookContext, vpn: VpnMode) {
        hookLazy(
            xposed,
            context,
            "NetworkInterface.getNetworkInterfaces",
            { NetworkInterface::class.java.getMethod("getNetworkInterfaces") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? java.util.Enumeration<*> ?: return@hookLazy null
            val all = Collections.list(real)
            val hasVirtual = all.any { it is NetworkInterface && looksVirtual(it.name) }
            if (vpn.present(hasVirtual)) return@hookLazy Collections.enumeration(all)

            val filtered = all.filterNot { it is NetworkInterface && looksVirtual(it.name) }
            Collections.enumeration(filtered)
        }

        hookLazy(
            xposed,
            context,
            "NetworkInterface.getByName",
            { NetworkInterface::class.java.getMethod("getByName", String::class.java) },
        ) { chain ->
            val name = chain.arg(0) as? String
            if (name != null && looksVirtual(name) && !vpn.present(true)) {
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
            if (iface != null && looksVirtual(iface.name) && !vpn.present(true)) {
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
            if (iface == null) return@hookLazy real

            val realVpn = looksVirtual(iface.name)
            val want = vpn.present(realVpn)
            val fake = when {
                want && !realVpn -> "tun0"
                !want && realVpn -> "wlan0"
                else -> real
            }
            if (fake != real) record(context, "network", "NetworkInterface.getDisplayName", real, fake)
            fake
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

    /**
     * The four answers to "is there a VPN?".
     *
     * [present] is the whole policy: it maps the real state to the one the app
     * is allowed to see. Every VPN-visible API runs its real answer through it,
     * so the four modes cannot disagree with each other.
     */
    private enum class VpnMode {
        DEFAULT,
        ON,
        OFF,
        REVERSED;

        fun present(real: Boolean): Boolean = when (this) {
            DEFAULT -> real
            ON -> true
            OFF -> false
            REVERSED -> !real
        }

        companion object {
            fun of(raw: String): VpnMode = when (raw.trim().lowercase(Locale.ROOT)) {
                "on" -> ON
                "off" -> OFF
                "reversed" -> REVERSED
                else -> DEFAULT
            }
        }
    }

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
    private const val VPN_TRANSPORT_TYPE = 1
}
