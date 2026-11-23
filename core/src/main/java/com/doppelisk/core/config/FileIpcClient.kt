package com.doppelisk.core.config

import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Request
import com.doppelisk.core.model.Response
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Client side of the file mailbox.
 *
 * A hooked process cannot talk to the daemon over a socket without extra
 * privileges, so both sides use a directory instead: the client writes
 * `request-<id>.json`, polls for `response-<id>.json`, and deletes both when it
 * is done. Polling at 25 ms keeps the round trip under one frame while staying
 * cheap enough for a hook that runs on the app's main thread.
 *
 * @param baseDir mailbox directory, normally `FileIpcPaths.under(filesDir)`.
 */
class FileIpcClient(private val baseDir: File) {

    private val json: Json = JsonFormats.wire
    private val paths = FileIpcPaths(baseDir)

    /**
     * Sends [request] and waits up to [timeoutMs] for the answer.
     *
     * A response whose id does not match is ignored (it belongs to another
     * in-flight call). Returns null on timeout, I/O failure or a malformed
     * response — callers treat all three as "daemon unavailable".
     */
    fun request(request: Request, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Response? {
        val id = request.id ?: UUID.randomUUID().toString()
        val requestFile = paths.request(id)
        val responseFile = paths.response(id)

        try {
            if (!baseDir.mkdirs() && !baseDir.isDirectory) {
                requestFile.delete()
                responseFile.delete()
                return null
            }
            // A stale response from a previous call with the same id would
            // otherwise be returned immediately.
            responseFile.delete()
            if (!responseFile.createNewFile()) {
                requestFile.delete()
                responseFile.delete()
                return null
            }
            if (!writeAtomic(requestFile, json.encodeToString(Request.serializer(), request.copy(id = id)))) {
                responseFile.delete()
                return null
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val response = readResponse(responseFile, id)
                if (response != null) {
                    requestFile.delete()
                    responseFile.delete()
                    return response.copy(mailboxDir = baseDir.path)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            requestFile.delete()
            responseFile.delete()
            return null
        } catch (_: Throwable) {
            requestFile.delete()
            responseFile.delete()
            return null
        }
    }

    /**
     * Drops a serialized audit report into the mailbox for the daemon to pick
     * up. Audits are fire-and-forget, so there is no response to wait for.
     *
     * @return true when the file was written.
     */
    fun reportAudit(payload: String): Boolean {
        baseDir.mkdirs()
        val file = paths.audit(UUID.randomUUID().toString())
        val ok = writeAtomic(file, payload)
        if (ok) pruneAudits()
        return ok
    }

    /** Reads [file] if it is a complete, matching response for [id]. */
    private fun readResponse(file: File, id: String): Response? {
        if (!file.exists() || file.length() == 0L) return null
        val text = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        if (text.isEmpty()) return null
        val response = runCatching {
            json.decodeFromString(Response.serializer(), text)
        }.getOrNull() ?: return null
        // A null id is accepted for compatibility with older daemons.
        if (response.id != null && response.id != id) return null
        return response
    }

    /** Keeps at most [MAX_AUDITS] audit files, deleting the oldest first. */
    private fun pruneAudits() {
        val files = baseDir.listFiles { f ->
            f.isFile && f.name.startsWith(FileIpcPaths.AUDIT_PREFIX) && f.name.endsWith(FileIpcPaths.SUFFIX)
        } ?: return
        if (files.size <= MAX_AUDITS) return
        files.sortedBy(File::lastModified)
            .take(files.size - MAX_AUDITS)
            .forEach { runCatching { it.delete() } }
    }

    /** Writes [text] via a uniquely named temp file, then renames it into place. */
    private fun writeAtomic(target: File, text: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2000
        private const val POLL_INTERVAL_MS = 25L
        private const val MAX_AUDITS = 50
    }
}
