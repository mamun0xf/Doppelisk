package com.doppelisk.daemon

import android.system.Os
import android.util.Log
import com.doppelisk.core.config.ConfigStore
import com.doppelisk.core.config.FileIpcPaths
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.DoppeliskIndex
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Request
import com.doppelisk.core.model.Response
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The polling half of the file mailbox.
 *
 * Every [POLL_MS] milliseconds the server walks:
 *
 *  1. the manager's mailbox, dispatching requests as the manager's uid;
 *  2. every assigned package's mailbox, dispatching requests as that app's uid.
 *
 * The uid matters because the dispatcher uses it to decide what an app is
 * allowed to ask for. For an app that runs in an isolated container the mailbox
 * lives inside the container's data directory, which the daemon cannot see by
 * path alone — the app tells the daemon where its mailbox is by setting
 * [Response.mailboxDir], and the daemon writes the answer there through a
 * prepared, correctly-labelled directory.
 */
class FileIpcServer(
    private val managerIpcDir: File?,
    private val managerUid: Int,
    private val store: ConfigStore,
    private val packageUid: (String) -> Int?,
    private val packageDataDir: (String) -> File?,
    private val dispatch: (Request, Int, Int, String?) -> Response,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val loggedSkips = LinkedHashSet<String>()
    private val visibleMailboxes = LinkedHashMap<String, VisibleMailbox>()
    private var lastAssigned: List<String>? = null

    fun start() {
        val thread = Thread(::loop, "doppelisk-file-ipc")
        thread.isDaemon = true
        thread.start()
    }

    private fun loop() {
        while (true) {
            try {
                tick()
            } catch (e: Throwable) {
                Log.e(TAG, "file mailbox tick failed", e)
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun tick() {
        managerIpcDir?.let { processRequests(it, managerUid, null) }

        val index: DoppeliskIndex = store.readIndex()
        val assigned = (index.assignments.keys + index.doppelisks.values.map { it.packageName }).distinct()
        if (assigned != lastAssigned) {
            Log.i(TAG, "assigned targets: $assigned")
            lastAssigned = assigned
        }

        for (pkg in assigned) {
            val dirs = targetDirs(pkg)
            if (dirs.isEmpty()) {
                if (loggedSkips.add("dir:$pkg")) {
                    Log.w(TAG, "target mailbox missing for $pkg: ${originalDir(pkg)}")
                }
                continue
            }

            val fallbackUid = packageUid(pkg)
            var processed = false
            for (dir in dirs) {
                val uid = fallbackUid ?: runCatching { Os.stat(dir.path).st_uid }.getOrNull()
                if (uid == null || uid <= 0) continue
                processed = true
                processRequests(dir, uid, pkg)
                processAudits(dir, pkg)
            }
            if (processed) loggedSkips.remove("dir:$pkg")
        }
    }

    /**
     * Mailboxes for [pkg]: the real one plus, if the app told us about it, the
     * one inside its container.
     */
    private fun targetDirs(pkg: String): List<File> {
        val dirs = mutableListOf<File>()

        val original = originalDir(pkg)
        if (original.isDirectory) dirs += original

        val visible = visibleMailboxes[pkg]
        if (visible != null) {
            val alive = visible.pid <= 0 || File("/proc/${visible.pid}").isDirectory
            if (alive) {
                val dir = File(visible.dir)
                if (dir.isDirectory) dirs += dir
            } else {
                visibleMailboxes.remove(pkg)
            }
        }

        return dirs.distinctBy(File::getPath)
    }

    private fun originalDir(pkg: String): File {
        val fromPackageDb = packageDataDir(pkg)?.let { File(File(it, "files"), FileIpcPaths.DIR) }
        if (fromPackageDb != null && fromPackageDb.isDirectory) return fromPackageDb
        return File("/data/user/0/$pkg/files", FileIpcPaths.DIR)
    }

    private fun processRequests(dir: File, callerUid: Int, callerPackage: String?) {
        val requests = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FileIpcPaths.REQUEST_PREFIX) && f.name.endsWith(FileIpcPaths.SUFFIX)
        } ?: return

        for (file in requests) {
            val id = FileIpcPaths.idFrom(file, FileIpcPaths.REQUEST_PREFIX) ?: continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val request = runCatching { json.decodeFromString(Request.serializer(), text) }.getOrNull()
            if (request == null) {
                file.delete()
                continue
            }

            val response = try {
                dispatch(request, callerUid, request.pid ?: 0, callerPackage)
            } catch (e: Throwable) {
                Response(ok = false, type = request.type, error = e.message)
            }.copy(id = id)

            val mailboxDir = response.mailboxDir
            if (callerPackage != null && mailboxDir != null) {
                visibleMailboxes[callerPackage] = VisibleMailbox(mailboxDir, request.pid ?: 0)
            }

            val visibleDir = mailboxDir?.let { File(it) }?.takeIf { it.path != dir.path }
            val responseFile = if (visibleDir != null) {
                val prepared = prepareVisibleMailbox(visibleDir, id, callerUid, callerPackage)
                if (prepared != null) {
                    runCatching { FileIpcPaths(dir).response(id).delete() }
                    prepared
                } else {
                    Log.e(TAG, "cannot prepare visible mailbox $visibleDir")
                    FileIpcPaths(dir).response(id)
                }
            } else {
                FileIpcPaths(dir).response(id)
            }

            if (responseFile.exists()) {
                runCatching { responseFile.writeText(json.encodeToString(Response.serializer(), response)) }
                    .onFailure { Log.e(TAG, "cannot write response $responseFile", it) }
            }
            file.delete()
        }
    }

    /**
     * Creates a response file in [dir] and hands it to the app.
     *
     * The daemon is root and the app is not, so the file must be chowned and
     * relabelled with the app's SELinux context or the app will not be able to
     * read it. The context is copied from the app's data directory, which is the
     * authoritative source.
     */
    private fun prepareVisibleMailbox(dir: File, id: String, callerUid: Int, packageName: String?): File? {
        if (packageName == null) return null

        var existing: File? = dir
        while (existing != null && !existing.exists()) existing = existing.parentFile
        if (!dir.mkdirs() && !dir.isDirectory) return null

        val response = FileIpcPaths(dir).response(id)
        if (!response.exists() && !response.createNewFile()) return null

        val label = packageLabel(packageName)
        if (!applyOwnerAndLabel(existing ?: dir, callerUid, label)) {
            runCatching { response.delete() }
            return null
        }
        return response
    }

    private fun packageLabel(packageName: String): String? {
        val dataDir = packageDataDir(packageName) ?: File("/data/user/0/$packageName")
        val bytes = runCatching { Os.getxattr(dataDir.path, "security.selinux") }.getOrNull() ?: return null
        val label = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
        return label.ifBlank { null }
    }

    private fun applyOwnerAndLabel(path: File, uid: Int, label: String?): Boolean {
        if (!runRootCommand("chown", "-R", "$uid:$uid", path.path)) return false
        return label == null || runRootCommand("chcon", "-R", label, path.path)
    }

    private fun runRootCommand(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun processAudits(dir: File, packageName: String) {
        val audits = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FileIpcPaths.AUDIT_PREFIX) && f.name.endsWith(FileIpcPaths.SUFFIX)
        } ?: return

        for (file in audits) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            // Reports are written with `JsonFormats.compact`, which elides nulls.
            // Decoding them with a format that requires explicit nulls rejected
            // every report with a null `real`/`returned` value, so a working hook
            // looked like it had never reported anything.
            val decoded = runCatching { JsonFormats.compact.decodeFromString(AuditReport.serializer(), text) }
            val report = decoded.getOrNull()
            if (report?.packageName == packageName) {
                store.writeAudit(packageName, text)
            } else {
                Log.w(
                    TAG,
                    "discarding audit for wrong package: ${report?.packageName} " +
                        "(${decoded.exceptionOrNull()?.message ?: "no decode error"})",
                )
            }
            file.delete()
        }
    }

    private data class VisibleMailbox(val dir: String, val pid: Int)

    private companion object {
        const val TAG = "DoppeliskFileIpc"
        const val POLL_MS = 80L
    }
}
