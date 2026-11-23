package com.doppelisk.core.container

/**
 * The three sandboxing modes a target package can be run under.
 *
 * The strings are the on-disk wire values, so they are constants rather than an
 * enum: an unknown value from a newer profile must survive a read/write round
 * trip instead of failing to decode.
 */
object ContainerModes {

    /** Hooks only; the app keeps its real data directory. */
    const val SHARED = "shared"

    /** Real data directory, but selected paths are replaced. */
    const val HYBRID = "hybrid"

    /** Private data directory plus a private keystore namespace. */
    const val ISOLATED = "isolated"

    val ALL: List<String> = listOf(SHARED, HYBRID, ISOLATED)

    /** Trims and lowercases [mode], falling back to [SHARED] for anything unknown. */
    fun normalize(mode: String?): String =
        when (mode?.trim()?.lowercase()) {
            HYBRID -> HYBRID
            ISOLATED -> ISOLATED
            else -> SHARED
        }
}
