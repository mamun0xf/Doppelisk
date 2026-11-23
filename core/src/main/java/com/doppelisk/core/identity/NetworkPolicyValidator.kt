package com.doppelisk.core.identity

import com.doppelisk.core.model.NetworkPolicy
import java.net.URI
import java.util.Locale

/** Result of [NetworkPolicyValidator.validate]. */
data class NetworkValidation(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
)

/** A parsed proxy exit endpoint. */
data class Exit(val host: String, val port: Int)

/**
 * Checks a [NetworkPolicy] for the mistakes that would silently break
 * connectivity at runtime.
 *
 * Everything here is static: no socket is opened, so the manager can validate a
 * draft policy offline.
 */
object NetworkPolicyValidator {

    val MODES: Set<String> = linkedSetOf("direct", "socks5", "tproxy", "block")

    val IPV6_MODES: Set<String> = linkedSetOf("block", "direct", "proxy")

    private const val DEFAULT_SOCKS_PORT = 1080

    fun validate(policy: NetworkPolicy): NetworkValidation {
        val errors = mutableListOf<String>()

        val mode = policy.mode.trim().lowercase(Locale.ROOT)
        if (mode !in MODES) {
            errors += "network mode must be one of " + MODES.joinToString()
        }
        if (mode == "tproxy") {
            errors += "tproxy mode is reserved; use socks5"
        }

        if (mode == "socks5" || mode == "tproxy") {
            val exit = (policy.exit ?: "").trim()
            if (exit.isEmpty()) {
                errors += "network exit is required for $mode"
            } else if (runCatching { parseExit(exit) }.getOrNull() == null) {
                errors += "network exit is not a valid host:port or URI"
            }
        }

        val ipv6 = policy.ipv6.trim().lowercase(Locale.ROOT)
        if (ipv6 !in IPV6_MODES) {
            errors += "ipv6 must be one of block, direct, proxy"
        }

        val dns = policy.dns?.trim()
        if (!dns.isNullOrEmpty()) {
            if (dns != "block" && dns != "system" && !Regex("^[0-9a-fA-F:.]+$").matches(dns)) {
                errors += "dns must be a literal address, 'system', or 'block'"
            }
        }

        return NetworkValidation(errors.isEmpty(), errors)
    }

    /**
     * Parses `host:port`, `host` or a `socks5://` / `socks5h://` / `tproxy://`
     * URI.
     *
     * Returns null for anything that does not yield a host and a port in
     * `1..65535`. A missing port defaults to 1080, the conventional SOCKS port.
     */
    fun parseExit(value: String): Exit? {
        val text = value.trim()
        if (text.isEmpty()) return null

        val explicitScheme = text.contains("://")
        val uri = runCatching {
            URI(if (explicitScheme) text else "socks5://$text")
        }.getOrNull() ?: return null

        if (explicitScheme) {
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "socks5" && scheme != "socks5h" && scheme != "tproxy") return null
        }

        val host = (uri.host ?: "").trim()
        val port = if (uri.port > 0) uri.port else DEFAULT_SOCKS_PORT
        if (host.isEmpty() || port !in 1..65535) return null
        return Exit(host, port)
    }
}
