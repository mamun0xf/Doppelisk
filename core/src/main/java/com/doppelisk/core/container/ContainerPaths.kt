package com.doppelisk.core.container

/**
 * Filesystem layout of the container subsystem.
 *
 * Kept separate from `ConfigPaths` because these paths are only ever touched by
 * the root daemon, and because every component that becomes a path segment comes
 * from an artifact — which may be hand-edited — so it is validated here.
 */
object ContainerPaths {

    const val DEFAULT_ROOT = "/data/adb/doppelisk"

    /**
     * Reduces [value] to a safe path segment.
     *
     * Unlike a plain sanitiser this also trims, and returns `unknown` for input
     * that sanitises to nothing, so a path is never built from an empty segment.
     */
    fun safeComponent(value: String): String {
        val replaced = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return replaced.ifBlank { "unknown" }
    }

    /**
     * True when [value] can be appended to a directory without escaping it:
     * not blank, not `.`/`..`, and free of separators, backslashes and NULs.
     */
    fun isSafeChild(value: String): Boolean =
        value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains('\u0000')

    /** Directory holding the journals for [root]. */
    fun journalDir(root: String = DEFAULT_ROOT): String = "$root/containers/journals"

    /** Journal path for one (artifact, package) pair. */
    fun journalPath(doppeliskId: String, packageName: String, root: String = DEFAULT_ROOT): String =
        "${journalDir(root)}/${safeComponent(doppeliskId)}-${safeComponent(packageName)}.json"

    /** The real data directory Android uses for [packageName]. */
    fun defaultDataDir(packageName: String): String = "/data/user/0/$packageName"
}
