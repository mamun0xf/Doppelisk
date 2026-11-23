package com.doppelisk.core.config

import java.io.File

/**
 * Layout of the manager/daemon mailbox.
 *
 * The mailbox is a directory inside the manager's private storage. The manager
 * writes a request file, the daemon polls for it, and the daemon writes back a
 * response file with the same id. Because the daemon runs as root it can write
 * into the app's directory, and because the directory is app-private no other
 * app can inject requests.
 *
 * Files are named `<prefix><id><suffix>` with [SUFFIX] always `".json"`, and the
 * id is whatever opaque string the caller chose — the daemon never parses it.
 */
class FileIpcPaths(baseDir: File) {

    /** Absolute mailbox directory, e.g. `<filesDir>/doppelisk/ipc`. */
    val dir: File = baseDir

    /** `<dir>/request-<id>.json` */
    fun request(id: String): File = File(dir, REQUEST_PREFIX + id + SUFFIX)

    /** `<dir>/response-<id>.json` */
    fun response(id: String): File = File(dir, RESPONSE_PREFIX + id + SUFFIX)

    /** `<dir>/audit-<id>.json` — observations pushed by a hooked process. */
    fun audit(id: String): File = File(dir, AUDIT_PREFIX + id + SUFFIX)

    /** Extracts the id from a request file name, or null if it is not one. */
    fun requestId(file: File): String? = strip(file.name, REQUEST_PREFIX)

    /** Extracts the id from a response file name, or null if it is not one. */
    fun responseId(file: File): String? = strip(file.name, RESPONSE_PREFIX)

    /** Extracts the id from an audit file name, or null if it is not one. */
    fun auditId(file: File): String? = strip(file.name, AUDIT_PREFIX)

    /** True when [file] sits directly in this mailbox. */
    fun owns(file: File): Boolean = file.parentFile?.absolutePath == dir.absolutePath

    /** Creates the mailbox directory if it does not exist yet. */
    fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("cannot create IPC directory $dir")
        }
    }

    private fun strip(name: String, prefix: String): String? {
        if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) return null
        val id = name.substring(prefix.length, name.length - SUFFIX.length)
        return id.ifEmpty { null }
    }

    companion object {
        /** Directory name under the app's `filesDir`. */
        const val DIR = "doppelisk/ipc"

        const val REQUEST_PREFIX = "request-"
        const val RESPONSE_PREFIX = "response-"
        const val AUDIT_PREFIX = "audit-"
        const val SUFFIX = ".json"

        /** Builds the mailbox for an app whose `filesDir` is [filesDir]. */
        fun under(filesDir: File): FileIpcPaths = FileIpcPaths(File(filesDir, DIR))

        /**
         * Extracts the id from a file named `<prefix><id><suffix>`, or null when
         * [file] is not such a file. Used by the daemon, which scans directories
         * it did not create.
         */
        fun idFrom(file: File, prefix: String): String? {
            val name = file.name
            if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) return null
            return name.substring(prefix.length, name.length - SUFFIX.length).ifEmpty { null }
        }
    }
}
