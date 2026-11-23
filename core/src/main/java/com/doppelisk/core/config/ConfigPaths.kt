package com.doppelisk.core.config

import java.security.MessageDigest

/**
 * Every filesystem location the app touches, in one place.
 *
 * The whole tree lives under [ROOT] because the daemon runs as root and the
 * manager must not be able to write there: the manager asks the daemon, and the
 * daemon owns the directory.
 */
object ConfigPaths {

    const val ROOT = "/data/adb/doppelisk"

    /** One `<id>.json` per doppelisk. */
    const val DOPPELISKS_DIR = "$ROOT/doppelisks"

    /** One `<package>.json` per target package that reported observations. */
    const val AUDIT_DIR = "$ROOT/audit"

    /** Container roots, swap targets and journals, keyed by doppelisk id. */
    const val CONTAINERS_DIR = "$ROOT/containers"

    /** Pre-change copies of anything the container machinery moved. */
    const val BACKUPS_DIR = "$ROOT/backups"

    /** Cached copy of the native shim, shared by the daemon and every hooked process. */
    const val LIB_DIR = "$ROOT/lib"

    /** The assignment table plus the catalog of known doppelisks. */
    const val INDEX = "$ROOT/index.json"

    /**
     * Prefix of the implicit per-package profile.
     *
     * A package that has never been configured still gets a stable, reproducible
     * doppelisk id derived from its name, so the hook side can answer "is there
     * an identity for me?" without any state on disk.
     */
    const val DEFAULT_PROFILE_PREFIX = "dpl_default_"

    /** Prefix of an explicitly created doppelisk. */
    const val PROFILE_PREFIX = "dpl_"

    /** Abstract socket name advertised by the daemon. */
    const val SOCKET_NAME = "doppelisk"

    /** Number of hex characters of the package digest used in a default id. */
    private const val DEFAULT_ID_HEX = 16

    /** Number of hex characters of the random suffix used in a created id. */
    private const val PROFILE_ID_HEX = 12

    /**
     * The implicit doppelisk id for [packageName]: `dpl_default_` followed by the
     * first 16 hex characters of `SHA-256(packageName)`.
     *
     * Deterministic on purpose — the same package always maps to the same
     * default id, on any install, without consulting [INDEX].
     */
    fun defaultProfileId(packageName: String): String =
        DEFAULT_PROFILE_PREFIX + sha256Hex(packageName).take(DEFAULT_ID_HEX)

    /** A fresh random id for an explicitly created doppelisk. */
    fun newProfileId(random: ByteArray): String =
        PROFILE_PREFIX + random.toHex().take(PROFILE_ID_HEX)

    /** True when [id] is the implicit per-package profile rather than a created one. */
    fun isDefaultProfileId(id: String): Boolean = id.startsWith(DEFAULT_PROFILE_PREFIX)

    /** Absolute path of a doppelisk document. */
    fun artifactPath(id: String): String = "$DOPPELISKS_DIR/${sanitize(id)}.json"

    /** Absolute path of a package's audit report. */
    fun auditPath(packageName: String): String = "$AUDIT_DIR/${sanitize(packageName)}.json"

    /** Container root for a doppelisk. */
    fun containerDir(id: String): String = "$CONTAINERS_DIR/${sanitize(id)}"

    /** Backup root for a package. */
    fun backupDir(packageName: String): String = "$BACKUPS_DIR/${sanitize(packageName)}"

    /**
     * Reduces an arbitrary identifier to a safe single path segment.
     *
     * Package names and doppelisk ids are already restricted to
     * `[A-Za-z0-9._-]`, but audit paths are built from a package name that comes
     * from the hooked process, so anything else is replaced rather than trusted.
     */
    fun sanitize(value: String): String {
        if (value.isEmpty()) return "_"
        val out = StringBuilder(value.length)
        for (ch in value) {
            val keep = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '.' || ch == '_' || ch == '-'
            out.append(if (keep) ch else '_')
        }
        // A leading dot would produce a hidden file (and `.`/`..` traversal).
        if (out[0] == '.') out[0] = '_'
        return out.toString()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            out.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }
}
