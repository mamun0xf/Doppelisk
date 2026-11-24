package com.doppelisk.core.model

import com.doppelisk.core.container.ContainerModes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A *doppelisk artifact*: the coherent device identity bound to one target
 * package.
 *
 * It is both the on-disk document (`<root>/doppelisks/<id>.json`) and the
 * object exchanged over the daemon mailbox, so the two formats are identical by
 * construction.
 *
 * [overrides] is nullable on purpose. A `null` list means "the profile is fully
 * derived from its chassis", and the profile codec elides nulls so those files
 * carry no `overrides` key at all. An explicit empty list is preserved and
 * written as `[]`, which is how the app records "the user cleared every pin".
 */
@Serializable
data class DoppeliskArtifact(
    val schema: Int = CURRENT_SCHEMA,
    val id: String,
    val label: String,
    val target: Target = Target(),
    val chassis: ChassisRef,
    val identity: Identity,
    /** Dotted field paths pinned by hand, e.g. `build.manufacturer`. */
    val overrides: List<String>? = null,
    val policy: PolicySet = PolicySet(),
    val container: ContainerSpec = ContainerSpec(),
    val network: NetworkPolicy = NetworkPolicy(),
    val location: LocationPolicy = LocationPolicy(),
) {
    companion object {
        const val CURRENT_SCHEMA = 3
    }
}

/** The package this identity is applied to. Serialized key is `package`. */
@Serializable
data class Target(
    @SerialName("package") val packageName: String = "",
    val minVersionCode: Long = 0L,
    val maxVersionCode: Long? = null,
)

/**
 * The chassis a profile is derived from.
 *
 * [id] is a catalog key such as `pixel8pro-husky`, or the pseudo-chassis `real`
 * for the untouched device. [hash] pins the catalog entry content as
 * `sha256:<hex>` so a profile can notice that the chassis definition changed
 * underneath it.
 */
@Serializable
data class ChassisRef(
    val id: String,
    val hash: String,
)

/** Named hook preset plus per-profile rule overrides. */
@Serializable
data class PolicySet(
    val preset: String = "stealth",
    val rules: List<PolicyRule> = emptyList(),
)

/**
 * One policy rule override.
 *
 * [category] groups rules (`build`, `ids`, `network`, ...), [field] names the
 * concrete field, and [mode] is the behaviour to apply for this profile.
 */
@Serializable
data class PolicyRule(
    val category: String,
    val field: String,
    val mode: String,
)

/**
 * How the target package is sandboxed.
 *
 * [mode] is one of [ContainerModes.SHARED], [ContainerModes.HYBRID] or
 * [ContainerModes.ISOLATED]. [isolate] lists extra paths bind-mounted into the
 * container, and [keystoreNamespace] is the optional isolated keystore name.
 */
@Serializable
data class ContainerSpec(
    val mode: String = ContainerModes.SHARED,
    val isolate: List<String> = emptyList(),
    val keystoreNamespace: String? = null,
)

/** Network shaping for the target package. */
@Serializable
data class NetworkPolicy(
    /** `direct`, `proxy` or `block`. */
    val mode: String = "direct",
    /** Proxy exit identifier, when [mode] is `proxy`. */
    val exit: String? = null,
    val killSwitch: Boolean = false,
    /** DNS override, `host:port` or `host`. */
    val dns: String? = null,
    /** `block`, `allow` or `prefer`. */
    val ipv6: String = "block",
    /**
     * What the target app sees about VPN connectivity: `default`, `on`, `off`
     * or `reversed`.
     *
     * Independent of [mode]: proxying a profile does not imply that the VPN
     * must be hidden. `default` reports the real state, `off` always says there
     * is no VPN, `on` always says there is one, and `reversed` inverts it.
     */
    val vpn: String = "default",
)

/** Location shaping for the target package. */
@Serializable
data class LocationPolicy(
    /** `off`, `static` or `playback`. */
    val mode: String = "off",
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val timezone: String? = null,
)

