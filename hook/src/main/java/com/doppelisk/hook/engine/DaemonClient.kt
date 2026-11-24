package com.doppelisk.hook.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.config.FileIpcClient
import com.doppelisk.core.config.FileIpcPaths
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Request
import com.doppelisk.core.model.Response
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Talks to the daemon from inside a hooked app.
 *
 * Two transports, tried in order:
 *
 *  1. the **file mailbox**, because it works even when the app has been placed in
 *     a container or had its network access restricted;
 *  2. the **local socket**, which is faster but needs the daemon to be reachable
 *     through the abstract namespace.
 *
 * Everything here is best-effort: a hook must never crash the app it lives in,
 * so failures return null rather than throwing.
 */
object DaemonClient {

    private val json: Json = JsonFormats.wire

    @Volatile
    private var fileClient: FileIpcClient? = null

    /** Points the file transport at this app's mailbox. */
    fun configure(filesDir: File) {
        fileClient = FileIpcClient(File(filesDir, FileIpcPaths.DIR))
    }

    fun request(req: Request, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Response? {
        fileClient?.request(req, timeoutMs)?.let { return it }
        return requestSocket(req, timeoutMs)
    }

    private fun requestSocket(req: Request, timeoutMs: Int): Response? = try {
        LocalSocket().use { socket ->
            socket.connect(
                LocalSocketAddress(ConfigPaths.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            socket.soTimeout = timeoutMs

            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            writer.write(json.encodeToString(Request.serializer(), req))
            writer.write("\n")
            writer.flush()

            reader.readLine()?.let { json.decodeFromString(Response.serializer(), it) }
        }
    } catch (_: Throwable) {
        null
    }

    fun ping(): Boolean = request(Request(type = "ping"), 800)?.ok == true

    /** Root-only device values as JSON, or null when the daemon is unavailable. */
    fun deviceReadings(): String? =
        request(Request(type = "get_device_readings"))?.takeIf { it.ok }?.payload

    fun getConfigResponse(packageName: String): Response? =
        request(Request(type = "get_config", packageName = packageName))

    fun getConfig(packageName: String): String? =
        getConfigResponse(packageName)?.takeIf { it.ok }?.payload

    /**
     * Asks the daemon to put this process into its container.
     *
     * The timeout is generous because the daemon may have to `chown` and relabel
     * a whole data directory.
     */
    fun prepareContainer(packageName: String): Response? = request(
        Request(type = "prepare_container", packageName = packageName, pid = Process.myPid()),
        PREPARE_TIMEOUT_MS,
    )

    fun prepareNetwork(packageName: String): Response? = request(
        Request(type = "prepare_network", packageName = packageName, pid = Process.myPid()),
        NETWORK_TIMEOUT_MS,
    )

    /**
     * Pushes an audit report.
     *
     * The mailbox path is preferred because the daemon can pick the file up even
     * when the socket is unavailable; the request path is the fallback.
     */
    fun reportAudit(packageName: String, payload: String) {
        if (fileClient?.reportAudit(payload) == true) return
        request(Request(type = "report_audit", packageName = packageName, payload = payload))
    }

    fun getAudit(packageName: String): String? =
        request(Request(type = "get_audit", packageName = packageName))?.takeIf { it.ok }?.payload

    fun getIndex(): String? =
        request(Request(type = "get_index"))?.takeIf { it.ok }?.payload

    fun getArtifact(packageName: String): String? =
        request(Request(type = "get_artifact", packageName = packageName))?.takeIf { it.ok }?.payload

    fun getDoppelisk(id: String): String? =
        request(Request(type = "get_doppelisk", packageName = id))?.takeIf { it.ok }?.payload

    fun listArtifacts(packageName: String): String? =
        request(Request(type = "list_artifacts", packageName = packageName))?.takeIf { it.ok }?.payload

    fun activateDoppelisk(packageName: String, id: String): Boolean =
        request(Request(type = "activate_doppelisk", packageName = packageName, payload = id))?.ok == true

    fun deleteDoppelisk(packageName: String, id: String): Boolean =
        request(Request(type = "delete_doppelisk", packageName = packageName, payload = id))?.ok == true

    fun clearProfileData(packageName: String, id: String): Boolean = request(
        Request(type = "clear_profile_data", packageName = packageName, payload = id),
        PREPARE_TIMEOUT_MS,
    )?.ok == true

    /**
     * Asks the root daemon to force-stop and start [packageName].
     *
     * The manager does this through the daemon rather than `su`: the daemon is
     * already root, so the restart cannot fail because the app's own root grant
     * is missing or its prompt is waiting on screen.
     */
    fun relaunch(packageName: String): Boolean = request(
        Request(type = "relaunch_package", packageName = packageName),
        RELAUNCH_TIMEOUT_MS,
    )?.ok == true

    fun putIndex(payload: String): Boolean =
        request(Request(type = "put_index", payload = payload))?.ok == true

    fun putArtifact(id: String, payload: String): Boolean =
        request(Request(type = "put_artifact", packageName = id, payload = payload))?.ok == true

    /**
     * Asks the daemon to write [id]'s profile to a staging file it owns.
     *
     * The daemon is the only side that can read `/data/adb`, and a zip with
     * container data can be gigabytes, so the timeout is minutes rather than
     * the usual couple of seconds.
     */
    fun exportProfile(id: String, destinationPath: String, includeData: Boolean): Boolean = request(
        Request(
            type = "export_profile",
            packageName = id,
            payload = if (includeData) "zip:$destinationPath" else destinationPath,
        ),
        TRANSFER_TIMEOUT_MS,
    )?.ok == true

    /** Stores a profile from [stagingPath]; returns the id the daemon gave it. */
    fun importProfile(stagingPath: String): String? = request(
        Request(type = "import_profile", payload = stagingPath),
        TRANSFER_TIMEOUT_MS,
    )?.takeIf { it.ok }?.payload

    private const val DEFAULT_TIMEOUT_MS = 2000
    private const val PREPARE_TIMEOUT_MS = 15_000
    private const val NETWORK_TIMEOUT_MS = 10_000
    private const val RELAUNCH_TIMEOUT_MS = 15_000
    private const val TRANSFER_TIMEOUT_MS = 120_000
}
