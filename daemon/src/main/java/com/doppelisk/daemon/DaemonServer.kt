package com.doppelisk.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.config.ConfigStore
import com.doppelisk.core.config.FileIpcPaths
import com.doppelisk.core.container.ContainerModes
import com.doppelisk.core.model.ArtifactList
import com.doppelisk.core.model.DeviceReadings
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.DoppeliskIndex
import com.doppelisk.core.model.IndexEntry
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Request
import com.doppelisk.core.model.Response
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The root-side server.
 *
 * It serves two transports:
 *
 *  - a **local socket** (`doppelisk`) for request/response calls, authenticated
 *    with `SO_PEERCRED` so a caller cannot claim to be someone else;
 *  - a **file mailbox** for hooked processes inside containers, whose network
 *    namespace and mount namespace make a socket connection impractical.
 *
 * The server is deliberately the only component that writes configuration. The
 * manager UI, the hooks and the daemon all go through [dispatch], so permission
 * checks live in exactly one place.
 */
class DaemonServer(private val store: ConfigStore = ConfigStore()) {

    private val json: Json = JsonFormats.wire
    private val pool = Executors.newCachedThreadPool()
    private val containers = ContainerManager()
    private val networks = NetworkManager()
    private val uidPackages = ConcurrentHashMap<Int, String>()

    @Volatile
    private var managerUid: Int = -1

    private var lockChannel: FileChannel? = null

    private val packagesList: Map<String, PackageRecord> by lazy { loadPackagesList() }

    fun serveForever() {
        if (!acquireSingleInstanceLock()) {
            Log.e(TAG, "another daemon instance already holds ${ConfigPaths.ROOT}/daemon.lock")
            return
        }

        store.ensureLayout()
        managerUid = resolveUid(MANAGER_PACKAGE)
        Log.i(TAG, "daemon starting; manager uid=$managerUid")
        watchManagerInstall()

        containers.recoverStale(::log)
        networks.closeAll()

        val managerDir = resolveDataDir(MANAGER_PACKAGE)
            ?.let { File(File(it, "files"), FileIpcPaths.DIR) }

        FileIpcServer(
            managerIpcDir = managerDir,
            managerUid = managerUid,
            store = store,
            packageUid = ::resolveUidOrNull,
            packageDataDir = ::resolveDataDir,
            dispatch = ::dispatch,
        ).start()

        val server = LocalServerSocket(ConfigPaths.SOCKET_NAME)
        Log.i(TAG, "listening on local socket ${ConfigPaths.SOCKET_NAME}")

        while (true) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                Log.e(TAG, "accept failed", t)
                continue
            }
            pool.execute { handle(client) }
        }
    }

    /**
     * Watches the manager's installation and retires the daemon when it changes.
     *
     * The daemon outlives the app: an uninstall/reinstall leaves this root process
     * running code from the old APK and holding the old manager uid, and the
     * manager cannot kill it. Comparing the package uid and the APK stamp lets the
     * daemon step aside on its own, so the next manager refresh starts a fresh one
     * from the current APK.
     */
    private fun watchManagerInstall() {
        val apk = System.getenv("DOPPELISK_APK")?.let(::File)
        val startUid = managerUid
        val startStamp = apk?.let(::apkStamp)

        val thread = Thread({
            while (true) {
                try {
                    Thread.sleep(INSTALL_WATCH_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                val uid = currentUid(MANAGER_PACKAGE)
                val stamp = apk?.let(::apkStamp)
                val replaced = apk != null && (stamp == null || stamp != startStamp)
                if (uid != startUid || replaced) {
                    Log.i(
                        TAG,
                        "manager changed (uid $startUid -> $uid); exiting so the next refresh starts a clean daemon",
                    )
                    runCatching { lockChannel?.close() }
                    Runtime.getRuntime().halt(0)
                }
            }
        }, "doppelisk-install-watch")
        thread.isDaemon = true
        thread.start()
    }

    /** The current uid of [pkg], re-read from disk rather than the cached map. */
    private fun currentUid(pkg: String): Int = runCatching {
        File("/data/system/packages.list").useLines { lines ->
            lines.firstOrNull { it.startsWith("$pkg ") }
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: -1
        }
    }.getOrDefault(-1)

    private fun apkStamp(apk: File): String? =
        apk.takeIf { it.isFile }?.let { "${it.length()}:${it.lastModified()}" }

    private fun acquireSingleInstanceLock(): Boolean = try {
        val file = File(ConfigPaths.ROOT, "daemon.lock")
        file.parentFile?.mkdirs()
        val channel = FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock: FileLock? = channel.tryLock()
        if (lock == null) {
            channel.close()
            false
        } else {
            lockChannel = channel
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "cannot take daemon lock", t)
        false
    }

    /** One request/response exchange over the local socket. */
    private fun handle(socket: LocalSocket) {
        try {
            val uid = peerUid(socket)
            val pid = peerPid(socket)
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

            val line = reader.readLine() ?: return
            val request = json.decodeFromString(Request.serializer(), line)
            val response = dispatch(request, uid, pid, null).copy(id = request.id)
            writer.write(json.encodeToString(Response.serializer(), response))
            writer.write("\n")
            writer.flush()
        } catch (t: Throwable) {
            Log.e(TAG, "client failed", t)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * The single decision point for every request.
     *
     * [callerUid] is authoritative — it comes from the socket credentials or from
     * the uid that owns the mailbox directory — so a request cannot ask for
     * another app's data by name.
     */
    private fun dispatch(request: Request, callerUid: Int, callerPid: Int, callerPackage: String?): Response =
        try {
            when (request.type) {
                "ping" -> Response(ok = true, type = "pong")

                "get_device_readings" -> getDeviceReadings(callerUid)

                "get_index" -> {
                    if (!isMutator(callerUid)) {
                        deny("get_index")
                    } else {
                        Response(true, "get_index", json.encodeToString(DoppeliskIndex.serializer(), store.readIndex()))
                    }
                }

                "put_index" -> putIndex(request, callerUid)

                "list_artifacts" -> {
                    val pkg = request.packageName
                    when {
                        !isMutator(callerUid) -> deny("list_artifacts")
                        pkg == null -> fail("list_artifacts", "missing package")
                        else -> Response(
                            true,
                            "list_artifacts",
                            json.encodeToString(ArtifactList.serializer(), ArtifactList(store.artifactsForPackage(pkg))),
                        )
                    }
                }

                "get_artifact" -> {
                    val pkg = request.packageName
                    when {
                        !isMutator(callerUid) -> deny("get_artifact")
                        pkg == null -> fail("get_artifact", "missing package")
                        else -> store.findArtifactForPackage(pkg)
                            ?.let { Response(true, "get_artifact", it) }
                            ?: fail("get_artifact", "no doppelisk for $pkg")
                    }
                }

                "get_doppelisk" -> {
                    val id = request.packageName
                    when {
                        !isMutator(callerUid) -> deny("get_doppelisk")
                        id == null -> fail("get_doppelisk", "missing id")
                        else -> store.readArtifact(id)
                            ?.let { Response(true, "get_doppelisk", it) }
                            ?: fail("get_doppelisk", "no doppelisk $id")
                    }
                }

                "put_artifact" -> putArtifact(request, callerUid)
                "activate_doppelisk" -> activateDoppelisk(request, callerUid)
                "relaunch_package" -> relaunchPackage(request, callerUid)
                "delete_doppelisk" -> deleteDoppelisk(request, callerUid)
                "clear_profile_data" -> clearProfileData(request, callerUid)

                "get_audit" -> {
                    val pkg = request.packageName
                    when {
                        !isMutator(callerUid) -> deny("get_audit")
                        pkg == null -> fail("get_audit", "missing package")
                        else -> Response(true, "get_audit", store.readAudit(pkg))
                    }
                }

                "report_audit" -> {
                    val pkg = callerPackage ?: packageForUid(callerUid) ?: "uid:$callerUid"
                    store.writeAudit(pkg, request.payload ?: "{}")
                    Response(true, "report_audit")
                }

                "get_config" -> {
                    val pkg = callerPackage ?: packageForUid(callerUid)
                    when {
                        pkg == null -> fail("get_config", "unknown caller uid $callerUid")
                        else -> {
                            val artifact = store.findArtifactForPackage(pkg)
                            when {
                                artifact != null -> Response(true, "get_config", artifact)
                                isVirtualDefault(pkg) -> fail("get_config", "virtual-default")
                                else -> fail("get_config", "no doppelisk for $pkg")
                            }
                        }
                    }
                }

                "prepare_container" -> {
                    val pkg = callerPackage ?: packageForUid(callerUid)
                    if (pkg == null) {
                        fail("prepare_container", "unknown caller uid $callerUid")
                    } else {
                        val artifactJson = store.findArtifactForPackage(pkg)
                        if (artifactJson == null) {
                            fail("prepare_container", "no doppelisk for $pkg")
                        } else {
                            val artifact = json.decodeFromString(DoppeliskArtifact.serializer(), artifactJson)
                            val result = containers.prepare(
                                artifact,
                                pkg,
                                callerUid,
                                request.pid ?: callerPid,
                                ::log,
                            )
                            Response(
                                ok = result.ok,
                                type = "prepare_container",
                                payload = result.mechanism,
                                error = result.error,
                                mailboxDir = result.mailboxDir,
                            )
                        }
                    }
                }

                "prepare_network" -> {
                    val pkg = callerPackage ?: packageForUid(callerUid)
                    if (pkg == null) {
                        fail("prepare_network", "unknown caller uid $callerUid")
                    } else {
                        val artifactJson = store.findArtifactForPackage(pkg)
                        if (artifactJson == null) {
                            fail("prepare_network", "no doppelisk for $pkg")
                        } else {
                            val artifact = json.decodeFromString(DoppeliskArtifact.serializer(), artifactJson)
                            val result = networks.apply(artifact, callerUid)
                            Response(result.ok, "prepare_network", result.mode, result.error)
                        }
                    }
                }

                else -> fail(request.type, "unknown request")
            }
        } catch (t: Throwable) {
            Response(false, request.type, error = t.message)
        }

    @Synchronized
    private fun putIndex(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("put_index")
        val payload = request.payload ?: return fail("put_index", "missing payload")
        store.writeIndex(json.decodeFromString(DoppeliskIndex.serializer(), payload))
        return Response(true, "put_index")
    }

    @Synchronized
    private fun putArtifact(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("put_artifact")
        val id = request.packageName ?: return fail("put_artifact", "missing id")
        val payload = request.payload ?: return fail("put_artifact", "missing payload")

        val artifact = json.decodeFromString(DoppeliskArtifact.serializer(), payload)
        store.writeArtifact(id, payload)
        store.writeIndex(withAssignment(store.readIndex(), artifact, id))
        return Response(true, "put_artifact")
    }

    /**
     * Records [artifact] in the index.
     *
     * Saving a profile never changes which one the app uses: assignment is an
     * explicit act ("Use"), so a newly created profile stays idle until it is
     * chosen. The single exception is the `Default` row, which describes "this
     * device" and becomes effective as soon as it is materialised, because the
     * user edited the real values rather than creating a separate identity.
     *
     * A dangling assignment — one whose artifact is gone — falls back to the
     * implicit default instead of silently keeping a dead id.
     */
    private fun withAssignment(index: DoppeliskIndex, artifact: DoppeliskArtifact, id: String): DoppeliskIndex {
        val pkg = artifact.target.packageName
        if (pkg.isBlank()) return index

        val assignments = index.assignments.toMutableMap()
        val current = assignments[pkg]
        val defaultId = ConfigPaths.defaultProfileId(pkg)

        if (current == null && id == defaultId) {
            assignments[pkg] = id
        } else if (current != null && current != defaultId && !index.doppelisks.containsKey(current)) {
            assignments[pkg] = defaultId
        }

        val entries = index.doppelisks.toMutableMap()
        entries[id] = IndexEntry(id, artifact.label, pkg, store.artifactPath(id))
        return index.copy(assignments = assignments, doppelisks = entries)
    }

    @Synchronized
    private fun activateDoppelisk(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("activate_doppelisk")
        val pkg = request.packageName ?: return fail("activate_doppelisk", "missing package")
        val id = request.payload ?: return fail("activate_doppelisk", "missing id")

        val index = store.readIndex()
        val defaultId = ConfigPaths.defaultProfileId(pkg)

        // The virtual default has no artifact of its own: assigning it means
        // "stop hooking this app", so no entry is required.
        if (id == defaultId && store.readArtifact(id) == null) {
            val assignments = index.assignments.toMutableMap().apply { put(pkg, id) }
            val entries = index.doppelisks.toMutableMap().apply { remove(id) }
            store.writeIndex(index.copy(assignments = assignments, doppelisks = entries))
            forceStop(pkg)
            return Response(true, "activate_doppelisk", id)
        }

        val entry = index.doppelisks[id] ?: return fail("activate_doppelisk", "unknown doppelisk $id")
        if (entry.packageName != pkg) {
            return fail("activate_doppelisk", "$id belongs to ${entry.packageName}")
        }
        if (store.readArtifact(id) == null) {
            return fail("activate_doppelisk", "artifact $id is missing")
        }

        store.writeIndex(index.copy(assignments = index.assignments.toMutableMap().apply { put(pkg, id) }))
        // The new profile only takes effect in a fresh process: the running one
        // loaded its hooks (or its pass-through) before the assignment changed.
        forceStop(pkg)
        return Response(true, "activate_doppelisk", id)
    }

    /**
     * Force-stops and starts [packageName] as root.
     *
     * The manager used to shell out to `su` for this, which fails whenever the
     * app's own root grant is missing — and an app that cannot restart a target
     * has no way to make a freshly activated profile visible. The daemon is
     * already root, so it performs the restart itself.
     */
    @Synchronized
    private fun relaunchPackage(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("relaunch_package")
        val pkg = request.packageName ?: return fail("relaunch_package", "missing package")
        if (pkg == MANAGER_PACKAGE) {
            return fail("relaunch_package", "refusing to restart the manager")
        }

        if (!forceStop(pkg)) {
            return fail("relaunch_package", "could not stop $pkg")
        }

        val started = runCommand(
            "monkey",
            "-p", pkg,
            "-c", "android.intent.category.LAUNCHER",
            "1",
        )
        return if (started) {
            Response(true, "relaunch_package", pkg)
        } else {
            fail("relaunch_package", "could not start $pkg")
        }
    }

    /** Stops [pkg] so the next start picks up the current assignment. */
    private fun forceStop(pkg: String): Boolean {
        if (pkg == MANAGER_PACKAGE) return false
        return runCommand("am", "force-stop", pkg)
    }

    /** Runs a command and reports whether it exited zero. */
    private fun runCommand(vararg args: String): Boolean = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)

    @Synchronized
    private fun deleteDoppelisk(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("delete_doppelisk")
        val pkg = request.packageName ?: return fail("delete_doppelisk", "missing package")
        val id = request.payload ?: return fail("delete_doppelisk", "missing id")

        val index = store.readIndex()
        val virtualDefault = id == ConfigPaths.defaultProfileId(pkg) && store.readArtifact(id) == null
        val entry = index.doppelisks[id]

        if (entry == null && !virtualDefault) return fail("delete_doppelisk", "unknown doppelisk $id")
        if (entry != null && entry.packageName != pkg) {
            return fail("delete_doppelisk", "$id belongs to ${entry.packageName}")
        }

        containers.clearProfileData(pkg, id, shouldForceStop(pkg, id, index)) { log("delete: $it") }

        store.deleteArtifact(id)
        return Response(true, "delete_doppelisk", id)
    }

    @Synchronized
    private fun clearProfileData(request: Request, callerUid: Int): Response {
        if (!isMutator(callerUid)) return deny("clear_profile_data")
        val pkg = request.packageName ?: return fail("clear_profile_data", "missing package")
        val id = request.payload ?: return fail("clear_profile_data", "missing id")

        val index = store.readIndex()
        val virtualDefault = id == ConfigPaths.defaultProfileId(pkg) && store.readArtifact(id) == null
        val entry = index.doppelisks[id]

        if (entry == null && !virtualDefault) return fail("clear_profile_data", "unknown doppelisk $id")
        if (entry != null && entry.packageName != pkg) {
            return fail("clear_profile_data", "$id belongs to ${entry.packageName}")
        }

        containers.clearProfileData(pkg, id, shouldForceStop(pkg, id, index)) {
            log("clear_profile_data cleared data for $pkg/$id: $it")
        }
        return Response(true, "clear_profile_data", id)
    }

    private fun isVirtualDefault(pkg: String): Boolean {
        val assigned = store.readIndex().assignments[pkg]
        val defaultId = ConfigPaths.defaultProfileId(pkg)
        return (assigned == null || assigned == defaultId) && store.readArtifact(defaultId) == null
    }

    /**
     * True when clearing [id]'s data would touch the app's real data directory
     * while the app is running — the only case where a force-stop is required.
     */
    private fun shouldForceStop(pkg: String, id: String, index: DoppeliskIndex): Boolean {
        val activeId = index.assignments[pkg]
        if (activeId == id) return true
        if (clearsRealData(pkg, id)) return activeUsesRealData(pkg, activeId)
        return false
    }

    private fun clearsRealData(pkg: String, id: String): Boolean {
        if (id == ConfigPaths.defaultProfileId(pkg)) return true
        return containerMode(id) == ContainerModes.SHARED
    }

    private fun activeUsesRealData(pkg: String, activeId: String?): Boolean {
        if (activeId == null || activeId == ConfigPaths.defaultProfileId(pkg)) return true
        return containerMode(activeId) == ContainerModes.SHARED
    }

    private fun containerMode(id: String): String {
        val artifactJson = store.readArtifact(id) ?: return ContainerModes.SHARED
        return runCatching {
            json.decodeFromString(DoppeliskArtifact.serializer(), artifactJson).container.mode
        }.map(ContainerModes::normalize).getOrDefault(ContainerModes.SHARED)
    }

    /**
     * Reads the values the manager cannot.
     *
     * Cached for the daemon's lifetime: a serial, a MAC and a panel renderer do
     * not change while it runs, and dumping SurfaceFlinger is expensive.
     */
    private val deviceReadings: DeviceReadings by lazy { readDeviceReadings() }

    private fun getDeviceReadings(callerUid: Int): Response =
        if (!isMutator(callerUid)) {
            deny("get_device_readings")
        } else {
            Response(
                ok = true,
                type = "get_device_readings",
                payload = json.encodeToString(DeviceReadings.serializer(), deviceReadings),
            )
        }

    private fun readDeviceReadings(): DeviceReadings {
        val unavailable = mutableListOf<String>()
        val properties = readProperties()

        fun prop(key: String): String = properties[key].orEmpty().ifEmpty {
            unavailable += "$key is not readable"
            ""
        }

        val serial = prop("ro.serialno")

        val wifiRaw = readFirstLine("/sys/class/net/wlan0/address")
        val wifiMac = if (isMac(wifiRaw)) {
            wifiRaw
        } else {
            unavailable += "Wi-Fi MAC is not readable"
            ""
        }

        val bluetoothRaw = command("/system/bin/settings", "get", "secure", "bluetooth_address").trim()
        val bluetoothMac = if (isMac(bluetoothRaw)) {
            bluetoothRaw
        } else {
            unavailable += "Bluetooth MAC is not readable"
            ""
        }

        val gpuRenderer = readGpuRenderer().ifEmpty {
            unavailable += "GPU renderer is not readable"
            ""
        }

        // `uname` is a syscall, so it answers the same in the daemon as on the
        // bare device: the daemon is not hooked and no shim is installed here.
        val uts = runCatching { android.system.Os.uname() }.getOrNull()
        val kernelRelease = uts?.release.orEmpty()
        val kernelVersion = uts?.version.orEmpty()
        val machine = uts?.machine.orEmpty()
        if (kernelRelease.isBlank()) unavailable += "kernel release is not readable"

        return DeviceReadings(
            serial = serial,
            wifiMac = wifiMac,
            bluetoothMac = bluetoothMac,
            gpuRenderer = gpuRenderer,
            kernelRelease = kernelRelease,
            kernelVersion = kernelVersion,
            machine = machine,
            model = prop("ro.product.model"),
            buildId = prop("ro.build.id"),
            buildDisplay = prop("ro.build.display.id"),
            baseband = prop("gsm.version.baseband"),
            debuggable = prop("ro.debuggable"),
            adbPresent = File("/data/adb").exists(),
            unavailable = unavailable,
        )
    }

    /**
     * The whole property table, as the property service reports it.
     *
     * One `getprop` call is much cheaper than one per key. The daemon is not
     * hooked, so this table is the unspoofed one — the property service itself
     * is never rewritten, only the per-process view of it.
     */
    private fun readProperties(): Map<String, String> = runCatching {
        command("/system/bin/getprop")
            .lineSequence()
            .mapNotNull { line ->
                // Format is `[key]: [value]`; a value may contain `]`, so the
                // split is anchored on the `]: [` separator.
                val split = line.indexOf("]: [")
                if (!line.startsWith("[") || split <= 0) return@mapNotNull null
                line.substring(1, split) to line.substring(split + 4).removeSuffix("]")
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun readFirstLine(path: String): String = runCatching {
        File(path).readText().lineSequence().firstOrNull()?.trim().orEmpty()
    }.getOrDefault("")

    private fun isMac(value: String): Boolean =
        value.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    /**
     * The GLES renderer from SurfaceFlinger, e.g. `Mali-G72`.
     *
     * SurfaceFlinger prints one `GLES: <vendor>, <renderer>, <version>` line, and
     * the renderer is the second field.
     */
    private fun readGpuRenderer(): String = runCatching {
        val dump = command("/system/bin/dumpsys", "SurfaceFlinger")
        Regex("GLES:\\s*[^,\\n]+,\\s*([^,\\n]+)").find(dump)?.groupValues?.get(1)?.trim().orEmpty()
    }.getOrDefault("")

    private fun isMutator(uid: Int): Boolean = uid == 0 || uid == managerUid

    private fun deny(type: String) = fail(type, "not permitted")

    private fun fail(type: String, error: String) = Response(false, type, error = error)

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun peerUid(socket: LocalSocket): Int = try {
        socket.peerCredentials.uid
    } catch (t: Throwable) {
        Log.e(TAG, "peer credentials unavailable; denying caller", t)
        -1
    }

    private fun peerPid(socket: LocalSocket): Int = try {
        socket.peerCredentials.pid
    } catch (t: Throwable) {
        Log.e(TAG, "peer pid unavailable", t)
        -1
    }

    /** Maps a uid back to its package, via `/data/system/packages.list` or `cmd`. */
    private fun packageForUid(uid: Int): String? {
        if (uid <= 0) return null
        uidPackages[uid]?.let { return it }

        val pkg = packagesList.entries.firstOrNull { it.value.uid == uid }?.key
            ?: resolvePackageFromCmd(uid)
            ?: return null
        uidPackages[uid] = pkg
        return pkg
    }

    private fun resolveUidOrNull(pkg: String): Int? = resolveUid(pkg).takeIf { it > 0 }

    private fun resolveUid(pkg: String): Int {
        packagesList[pkg]?.let { return it.uid }
        return runCatching {
            val out = command("cmd", "package", "list", "packages", "--user", "0", pkg)
            Regex("uid:(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }

    private fun resolveDataDir(pkg: String): File? {
        packagesList[pkg]?.dataDir?.let { return File(it) }
        return runCatching {
            val out = command("cmd", "package", "list", "packages", "--user", "0", pkg)
            Regex("dataDir=([^\\s]+)").find(out)?.groupValues?.get(1)?.let(::File)
        }.getOrNull()
    }

    private fun resolvePackageFromCmd(uid: Int): String? = runCatching {
        command("cmd", "package", "list", "packages", "--uid", uid.toString())
            .lineSequence()
            .firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
            ?.substringBefore(" ")
            ?.trim()
            ?.ifEmpty { null }
    }.getOrNull()

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).start()
        val out = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.waitFor()
        return out
    }

    /**
     * Parses `/data/system/packages.list`, the authoritative uid/data-dir table.
     *
     * Reading it once is much cheaper than shelling out to `cmd package` per
     * request, and it is world-readable to root.
     */
    private fun loadPackagesList(): Map<String, PackageRecord> = runCatching {
        File("/data/system/packages.list").useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(' ', limit = 6)
                if (parts.size < 2) return@mapNotNull null
                val uid = parts[1].toIntOrNull() ?: return@mapNotNull null
                val dataDir = parts.getOrNull(3)?.takeIf { it.startsWith("/") }
                parts[0] to PackageRecord(uid, dataDir)
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private data class PackageRecord(val uid: Int, val dataDir: String?)

    private companion object {
        const val TAG = "DoppeliskDaemon"
        const val MANAGER_PACKAGE = "com.doppelisk.app"

        /** How often the daemon checks whether its manager was reinstalled. */
        const val INSTALL_WATCH_MS = 5_000L
    }
}
