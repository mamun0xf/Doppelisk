package com.doppelisk.daemon

import android.util.Log
import com.doppelisk.core.identity.NetworkPolicyValidator
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.NetworkPolicy
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Applies a [NetworkPolicy] to one uid with `iptables`.
 *
 * The rules are keyed by uid rather than by package because that is what the
 * kernel can match, and because an app may have several processes. Every apply
 * starts by removing this uid's previous rules, so re-applying is idempotent and
 * a mode change never leaves a stale redirect behind.
 *
 * Rules are inserted at the top of `OUTPUT` so they win over anything the ROM
 * installed. `-C` is tried before `-I`/`-D` so a duplicate never accumulates.
 */
class NetworkManager {

    data class Result(val ok: Boolean, val mode: String, val error: String? = null)

    private val bridges = LinkedHashMap<Int, ProxyBridge>()
    private val externalProxyPort: Int? = System.getenv("DOPPELISK_PROXY_PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }

    @Synchronized
    fun apply(artifact: DoppeliskArtifact, uid: Int): Result {
        if (uid <= 0) return Result(false, artifact.network.mode, "invalid uid")

        val policy = artifact.network
        val validation = NetworkPolicyValidator.validate(policy)
        if (!validation.ok) {
            return Result(false, policy.mode, validation.errors.joinToString("; "))
        }

        val mode = policy.mode.trim().lowercase(Locale.ROOT)
        return try {
            stopBridge(uid)
            removeRules(uid)

            val result = when (mode) {
                "direct" -> {
                    if (policy.ipv6.trim().equals("block", ignoreCase = true)) blockIpv6(uid)
                    Result(true, mode)
                }

                "socks5" -> applySocks5(uid, policy, mode)

                "block" -> {
                    blockIpv4(uid)
                    blockIpv6(uid)
                    Result(true, mode)
                }

                else -> Result(false, mode, "unsupported network mode")
            }

            if (policy.dns?.trim().equals("block", ignoreCase = true)) blockDns(uid)
            if (policy.killSwitch && !result.ok && mode == "socks5") {
                blockIpv4(uid)
                blockIpv6(uid)
            }
            result
        } catch (t: Throwable) {
            runCatching { stopBridge(uid) }
            runCatching { removeRules(uid) }
            Result(false, mode, t.message)
        }
    }

    private fun applySocks5(uid: Int, policy: NetworkPolicy, mode: String): Result {
        val port = externalProxyPort ?: startBridge(uid, policy.exit)
        if (port == null) {
            return if (policy.killSwitch) {
                blockIpv4(uid)
                blockIpv6(uid)
                Result(false, mode, "proxy bridge unavailable; kill switch is blocking uid $uid")
            } else {
                Result(false, mode, "proxy bridge unavailable; refusing to claim proxy routing")
            }
        }

        redirect(uid, port, policy.ipv6.trim().equals("proxy", ignoreCase = true))

        val dns = policy.dns?.trim()?.takeIf { it.isNotEmpty() && it !in setOf("block", "system") }
        if (dns != null) redirectDns(uid, dns)
        if (policy.ipv6.trim().equals("block", ignoreCase = true)) blockIpv6(uid)

        return Result(true, mode)
    }

    @Synchronized
    fun clear(uid: Int): Boolean = runCatching {
        stopBridge(uid)
        removeRules(uid)
        true
    }.getOrDefault(false)

    private fun startBridge(uid: Int, exit: String?): Int? {
        val parsed = NetworkPolicyValidator.parseExit(exit ?: return null) ?: return null
        val bridge = ProxyBridge(parsed) {
            // The upstream died: the bridge can no longer be trusted, so drop it
            // and block the uid rather than let traffic leak out unproxied.
            runCatching { blockIpv4(uid) }
            runCatching { blockIpv6(uid) }
        }
        bridges[uid] = bridge
        return bridge.port
    }

    private fun stopBridge(uid: Int) {
        bridges.remove(uid)?.close()
    }

    private fun removeRules(uid: Int) {
        deleteIfPresent("iptables", listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-j", "DROP"))
        deleteIfPresent("ip6tables", listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-j", "DROP"))
        removeRedirects("iptables", uid)
        removeRedirects("ip6tables", uid)
        deleteIfPresent(
            "iptables",
            listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-d", "127.0.0.0/8", "-j", "RETURN"),
        )
        deleteIfPresent(
            "ip6tables",
            listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-d", "::1/128", "-j", "RETURN"),
        )
        for (table in listOf("iptables", "ip6tables")) {
            deleteIfPresent(table, listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-p", "udp", "--dport", "53", "-j", "DROP"))
            deleteIfPresent(table, listOf("-D", "OUTPUT", "-m", "owner", "--uid-owner", "$uid", "-p", "tcp", "--dport", "53", "-j", "DROP"))
        }
    }

    private fun blockIpv4(uid: Int) {
        addIfAbsent("iptables", listOf("-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid", "-j", "DROP"))
    }

    private fun blockIpv6(uid: Int) {
        addIfAbsent("ip6tables", listOf("-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid", "-j", "DROP"))
    }

    /**
     * Redirects [uid]'s outbound TCP to the local bridge on [port].
     *
     * Loopback traffic is exempted first, otherwise the bridge's own connection
     * to the upstream would be redirected into itself.
     */
    private fun redirect(uid: Int, port: Int, ipv6: Boolean) {
        val cmd = if (ipv6) "ip6tables" else "iptables"
        addIfAbsent(
            cmd,
            listOf(
                "-t", "nat", "-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid",
                "-d", if (ipv6) "::1/128" else "127.0.0.0/8", "-j", "RETURN",
            ),
        )
        addIfAbsent(
            cmd,
            listOf(
                "-t", "nat", "-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid",
                "-p", "tcp", "-j", "REDIRECT", "--to-ports", "$port",
            ),
        )
    }

    private fun redirectDns(uid: Int, address: String) {
        val table = if (address.contains(':')) "ip6tables" else "iptables"
        for (proto in listOf("udp", "tcp")) {
            addIfAbsent(
                table,
                listOf(
                    "-t", "nat", "-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid",
                    "-p", proto, "--dport", "53", "-j", "DNAT", "--to-destination", "$address:53",
                ),
            )
        }
    }

    private fun blockDns(uid: Int) {
        for (table in listOf("iptables", "ip6tables")) {
            for (proto in listOf("udp", "tcp")) {
                addIfAbsent(
                    table,
                    listOf(
                        "-I", "OUTPUT", "1", "-m", "owner", "--uid-owner", "$uid",
                        "-p", proto, "--dport", "53", "-j", "DROP",
                    ),
                )
            }
        }
    }

    private fun addIfAbsent(cmd: String, args: List<String>) {
        if (run(listOf(cmd) + checkSpec(args)) == 0) return
        val result = run(listOf(cmd) + args)
        if (result != 0) {
            throw IllegalStateException("$cmd ${args.joinToString(" ")} failed with $result")
        }
    }

    private fun deleteIfPresent(cmd: String, args: List<String>) {
        if (run(listOf(cmd) + checkSpec(args)) == 0) run(listOf(cmd) + args)
    }

    /**
     * Rewrites an `-I`/`-D` spec into the matching `-C` spec.
     *
     * `-I OUTPUT 1 ...` has no `-C` equivalent with the position argument, so the
     * position is dropped when present.
     */
    private fun checkSpec(args: List<String>): List<String> {
        val index = args.indexOfFirst { it == "-I" || it == "-D" }
        if (index < 0) return args

        val check = args.toMutableList()
        check[index] = "-C"
        if (args[index] == "-I" && check.getOrNull(index + 2)?.toIntOrNull() != null) {
            check.removeAt(index + 2)
        }
        return check
    }

    private fun removeRedirects(cmd: String, uid: Int) {
        val listing = runCapture(listOf(cmd, "-t", "nat", "-S", "OUTPUT")) ?: return
        listing.lineSequence()
            .filter { it.contains("--uid-owner $uid") && (it.contains("REDIRECT") || it.contains("DNAT")) }
            .forEach { line ->
                val spec = line.substringAfter("OUTPUT ", "").trim()
                if (spec.isEmpty()) return@forEach
                val parts = spec.split(Regex("\\s+")).filter(String::isNotEmpty)
                if (parts.isEmpty()) return@forEach
                run(listOf(cmd, "-t", "nat", "-D", "OUTPUT") + parts)
            }
    }

    private fun run(args: List<String>): Int = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            -1
        } else {
            process.inputStream.readBytes()
            process.exitValue()
        }
    }.getOrDefault(-1)

    private fun runCapture(args: List<String>): String? = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = InputStreamReader(process.inputStream, Charsets.UTF_8).use { it.readText() }
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() == 0) text else null
    }.getOrNull()

    /** Closes every bridge. Used on shutdown. */
    @Synchronized
    fun closeAll() {
        for (bridge in bridges.values) runCatching { bridge.close() }
        bridges.clear()
        Log.i(TAG, "network bridges closed")
    }

    private companion object {
        const val TAG = "DoppeliskNet"
    }
}
