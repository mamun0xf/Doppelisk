package com.doppelisk.daemon

import android.system.Os
import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.config.FileIpcPaths
import com.doppelisk.core.container.ContainerJournal
import com.doppelisk.core.container.ContainerModes
import com.doppelisk.core.container.ContainerMount
import com.doppelisk.core.container.ContainerPaths
import com.doppelisk.core.container.ContainerPlan
import com.doppelisk.core.container.ContainerPlanner
import com.doppelisk.core.container.DirectorySwap
import com.doppelisk.core.container.JournalRecovery
import com.doppelisk.core.container.JournalStatus
import com.doppelisk.core.container.JournalStore
import com.doppelisk.core.container.RecoveryAction
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Applies and undoes container layouts for a running app.
 *
 * There are two mechanisms, and which one is used is decided by what the caller's
 * process can support:
 *
 *  - **bind mount** — needs the app to have its own mount namespace (which
 *    Android gives it) and a working `nsenter`. This is the strong option: the
 *    real files stay where they are and the container is mounted over them.
 *  - **directory swap** — works everywhere. The real directory is moved aside and
 *    replaced with a symlink into the container store. Weaker, because a root
 *    process could follow the symlink, but it survives reboots.
 *
 * Every attempt is journalled before it starts, so a crash halfway through leaves
 * enough information for [recoverStale] to undo it.
 */
class ContainerManager(
    private val root: String = ContainerPaths.DEFAULT_ROOT,
    private val dataRoot: String = "/data/user/0",
) {

    private val journals = JournalStore(root)

    private val json: Json = JsonFormats.wire

    // ---------------------------------------------------------------- recovery

    /**
     * Rolls back or finalises every journal left behind by a previous daemon.
     *
     * Journals whose process is still alive are left alone: the container is
     * still in use. Everything else that was mid-operation is undone, because a
     * half-applied container is worse than no container.
     */
    fun recoverStale(log: (String) -> Unit) {
        for ((file, journal) in journals.list()) {
            when (val action = recoveryAction(journal, forceInFlight = true)) {
                is RecoveryAction.RollbackMounts -> {
                    rollbackMounts(journal, log)
                    journals.delete(journal)
                    log("container: rolled back crashed mounts for ${journal.plan.packageName}")
                }
                is RecoveryAction.RollbackSwaps -> {
                    rollbackSwaps(journal, log)
                    journals.delete(journal)
                    log("container: rolled back crashed swap for ${journal.plan.packageName}")
                }
                is RecoveryAction.RollbackAll -> {
                    rollbackMounts(journal, log)
                    rollbackSwaps(journal, log)
                    journals.delete(journal)
                    log("container: rolled back unfinished container for ${journal.plan.packageName}")
                }
                is RecoveryAction.FinalizeMounted, is RecoveryAction.FinalizeSwapped -> journals.delete(journal)
                RecoveryAction.Keep -> Unit
            }
            file.exists()
        }
    }

    // ---------------------------------------------------------------- prepare

    fun prepare(
        artifact: DoppeliskArtifact,
        packageName: String,
        callerUid: Int,
        callerPid: Int,
        log: (String) -> Unit,
    ): ContainerResult {
        if (callerPid <= 0) return refused("invalid caller pid")

        validateCaller(packageName, callerUid, callerPid)?.let { return refused(it) }

        val dataDir = findDataDir(packageName)
            ?: return refused("app data dir not found for $packageName")
        val appLabel = selinuxLabel(dataDir)

        val plan = try {
            ContainerPlanner.plan(artifact, dataDir, root)
        } catch (t: Throwable) {
            return refused(t.message ?: "invalid container plan")
        }

        if (plan.mode == ContainerModes.SHARED) {
            recoverForPrepare(plan, log)
            restoreToShared(packageName, log)
            return ContainerResult(true, null, plan, ContainerModes.SHARED)
        }

        recoverForPrepare(plan, log)
        restoreToShared(packageName, log, keepContainer = plan.swapRoot)

        val journal = ContainerJournal(
            plan = plan,
            pid = callerPid,
            uid = callerUid,
            pidStartTicks = processStartTicks(callerPid),
        )
        journals.save(journal)

        try {
            val privateNs = isPrivateMountNamespace(callerPid)
            val canMount = privateNs && nsenterWorks(callerPid)

            if (plan.usesMounts && canMount) {
                val mounted = applyMounts(journal, appLabel, log)
                if (!mounted.ok && !plan.requiresPrivateNamespace) {
                    log("container: bind mount failed, falling back to dir swap: ${mounted.error}")
                    return applySwaps(journal, appLabel, log)
                }
                return mounted
            }

            if (plan.requiresPrivateNamespace) {
                val reason = if (!privateNs) {
                    "Isolated mode refused: caller shares the init mount namespace"
                } else {
                    "Isolated mode refused: nsenter cannot enter the caller mount namespace"
                }
                return fail(journal, reason, "refused")
            }

            return applySwaps(journal, appLabel, log)
        } catch (t: Throwable) {
            runCatching { rollbackMounts(journal, log) }
            runCatching { rollbackSwaps(journal, log) }
            return fail(journal, t.message ?: t.javaClass.simpleName, JournalStatus.FAILED)
        }
    }

    private fun recoverForPrepare(plan: ContainerPlan, log: (String) -> Unit) {
        val current = journals.load(plan.doppeliskId, plan.packageName) ?: return
        when (recoveryAction(current, forceInFlight = false)) {
            is RecoveryAction.RollbackMounts -> {
                rollbackMounts(current, log)
                journals.delete(current)
            }
            is RecoveryAction.RollbackSwaps -> {
                rollbackSwaps(current, log)
                journals.delete(current)
            }
            is RecoveryAction.RollbackAll -> {
                rollbackMounts(current, log)
                rollbackSwaps(current, log)
                journals.delete(current)
            }
            is RecoveryAction.FinalizeMounted, is RecoveryAction.FinalizeSwapped -> journals.delete(current)
            RecoveryAction.Keep -> Unit
        }
    }

    private fun applyMounts(
        journal: ContainerJournal,
        appLabel: String?,
        log: (String) -> Unit,
    ): ContainerResult {
        var current = journal.copy(mechanism = "mount").withStatus(JournalStatus.MOUNTING)
        journals.save(current)

        for (mount in journal.plan.mounts) {
            val target = File(mount.target)
            if (target.exists() && !target.isDirectory) {
                rollbackMounts(current, log)
                return fail(current, "mount target is not a directory: ${mount.target}", JournalStatus.FAILED)
            }
            target.mkdirs()
            prepareSource(mount.source, journal.uid, appLabel)

            val result = runCommand(
                nsenterPrefix() + listOf(
                    "-t", journal.pid.toString(), "-m", "--",
                    "mount", "--bind", mount.source, mount.target,
                ),
            )
            if (result.code != 0) {
                rollbackMounts(current, log)
                return fail(
                    current,
                    "bind mount failed for ${mount.target}: ${result.output}",
                    JournalStatus.FAILED,
                )
            }
            current = current.withStatus(JournalStatus.MOUNTING, mountTarget = mount.target)
            journals.save(current)
        }

        current = current.withStatus(JournalStatus.MOUNTED)
        journals.save(current)
        log("container: mounted ${journal.plan.mounts.size} paths for ${journal.plan.packageName}")
        return ContainerResult(true, null, journal.plan, "mount", visibleMailbox(journal.plan, "mount"))
    }

    private fun applySwaps(
        journal: ContainerJournal,
        appLabel: String?,
        log: (String) -> Unit,
    ): ContainerResult {
        var current = journal.copy(mechanism = "swap").withStatus(JournalStatus.SWAPPING)
        journals.save(current)

        for (swap in journal.plan.swaps) {
            val error = applySwap(swap, journal.uid, appLabel)
            if (error != null) {
                rollbackSwaps(current, log)
                return fail(current, "directory swap failed for ${swap.target}: $error", JournalStatus.FAILED)
            }
            current = current.withStatus(JournalStatus.SWAPPING, swapTarget = swap.target)
            journals.save(current)
        }

        current = current.withStatus(JournalStatus.SWAPPED)
        journals.save(current)
        log("container: swapped ${journal.plan.swaps.size} paths for ${journal.plan.packageName}")
        return ContainerResult(true, null, journal.plan, "swap", visibleMailbox(journal.plan, "swap"))
    }

    /**
     * Replaces [swap]'s target with a symlink into the container store.
     *
     * Returns null on success, or a human-readable reason for refusing. The
     * refusals matter: replacing a symlink we did not create, or clobbering a
     * backup from a previous run, would lose data.
     */
    private fun applySwap(swap: DirectorySwap, uid: Int, appLabel: String?): String? {
        val target = Paths.get(swap.target)
        val container = Paths.get(swap.container)
        val backup = Paths.get(swap.backup)

        container.toFile().mkdirs()
        backup.parent?.toFile()?.mkdirs()
        prepareSource(swap.container, uid, appLabel)

        if (Files.isSymbolicLink(target)) {
            val link = resolvedSymlink(File(swap.target))?.toString() ?: ""
            if (link.isNotBlank() && Paths.get(link).normalize() == container.normalize()) return null
            if (!link.contains("/.doppelisk-store/")) {
                return "refusing to replace foreign symlink $target -> $link"
            }
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return "target is not a directory"
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                return "backup already exists for $target; previous swap was not recovered"
            }
            try {
                move(backup, target)
            } catch (t: Throwable) {
                return "could not preserve original $target: ${t.message}"
            }
        }

        return installSymlink(target, container)
    }

    private fun installSymlink(target: Path, container: Path): String? {
        val temp = target.resolveSibling(".${target.fileName}.doppelisk-new")
        return try {
            Files.deleteIfExists(temp)
            Files.createSymbolicLink(temp, container)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            null
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            "could not install symlink $target: ${t.message}"
        }
    }

    // ------------------------------------------------------------ restore/clear

    /**
     * Undoes every symlink under [packageName]'s data dir that points into the
     * container store, restoring the preserved directory.
     *
     * [keepContainer] protects one container root (used when preparing a new
     * layout for the same profile); [onlyContainer] restricts the operation to
     * one container (used when deleting a profile).
     */
    fun restoreToShared(
        packageName: String,
        log: (String) -> Unit,
        keepContainer: String? = null,
        onlyContainer: String? = null,
    ) {
        val dataDirPath = findDataDir(packageName) ?: return
        val dataDir = File(dataDirPath)
        val safePkg = ContainerPaths.safeComponent(packageName)
        val backupRoot = File(root, "backups/$safePkg")
        val storeRoot = File(dataDir, ".doppelisk-store").toPath().normalize()

        val keep = keepContainer?.let { Paths.get(it).normalize() }
        val only = onlyContainer?.let { Paths.get(it).normalize() }

        val children = dataDir.listFiles() ?: return
        for (file in children) {
            if (!Files.isSymbolicLink(file.toPath())) continue

            val resolved = resolvedSymlink(file) ?: continue
            if (!resolved.startsWith(storeRoot)) continue
            if (keep != null && resolved.startsWith(keep)) continue
            if (only != null && !resolved.startsWith(only)) continue

            val targetName = file.name
            val target = File(dataDir, targetName)
            val backup = File(backupRoot, targetName)

            val detached = runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()
            if (detached != null) {
                log("container: could not detach $target: ${detached.message}")
                continue
            }
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                log("container: refusing to overwrite $target while restoring Default")
                continue
            }

            if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move(target.toPath(), backup.toPath())
                } catch (t: Throwable) {
                    log("container: restore failed for $target: ${t.message}")
                }
            } else {
                target.mkdirs()
            }
            log("container: restored $targetName to Default data for $packageName")
        }
    }

    /** Stops [packageName] if asked, then removes one profile's container data. */
    fun clearProfileData(
        packageName: String,
        id: String,
        forceStop: Boolean = true,
        log: (String) -> Unit,
    ) {
        if (forceStop && packageName != MANAGER_PACKAGE) {
            runCatching { runCommand(listOf("am", "force-stop", packageName)) }
        }

        val file = File(root, "doppelisks/$id${FileIpcPaths.SUFFIX}")
        val artifact = if (file.exists()) {
            runCatching { parseArtifact(file.readText()) }.getOrNull()
        } else {
            null
        }

        if (ConfigPaths.isDefaultProfileId(id)) {
            clearDefaultData(packageName, log)
        } else if (artifact != null) {
            clearCustomData(packageName, artifact, log)
        }

        runCatching { journals.delete(id, packageName) }
    }

    private fun clearDefaultData(packageName: String, log: (String) -> Unit) {
        val dataDirPath = findDataDir(packageName) ?: return
        val dataDir = File(dataDirPath)
        val safePkg = ContainerPaths.safeComponent(packageName)
        val backupRoot = File(root, "backups/$safePkg")

        dataDir.listFiles()?.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            if (file.name == ".doppelisk-store") return@forEach
            if (file.name.startsWith(".doppelisk-")) return@forEach
            runCatching { file.deleteRecursively() }
        }

        runCatching { backupRoot.deleteRecursively() }
        runCatching { backupRoot.mkdirs() }
        log("container: cleared Default internal data for $packageName")
    }

    /**
     * Removes a custom profile's container data.
     *
     * The profile's swapped directories are restored to Default first, otherwise
     * deleting the store would leave the app with dangling symlinks.
     */
    private fun clearCustomData(packageName: String, artifact: DoppeliskArtifact, log: (String) -> Unit) {
        val dataDir = findDataDir(packageName) ?: return
        val plan = runCatching { ContainerPlanner.plan(artifact, dataDir, root) }.getOrNull() ?: return

        restoreToShared(packageName, log, onlyContainer = plan.swapRoot)
        runCatching { File(plan.swapRoot).deleteRecursively() }
        runCatching { File(plan.mountRoot).deleteRecursively() }
        log("container: cleared container data for $packageName/${plan.doppeliskId}")
    }

    // ---------------------------------------------------------------- rollback

    private fun rollbackMounts(journal: ContainerJournal, log: (String) -> Unit) {
        if (!nsenterAvailable()) return
        for (mount in journal.plan.mounts.asReversed()) {
            val result = runCommand(
                nsenterPrefix() + listOf("-t", journal.pid.toString(), "-m", "--", "umount", "-l", mount.target),
            )
            if (result.code == 0) log("container: unmounted ${mount.target}")
        }
    }

    private fun rollbackSwaps(journal: ContainerJournal, log: (String) -> Unit) {
        for (swap in journal.plan.swaps.asReversed()) {
            val target = Paths.get(swap.target)
            val backup = Paths.get(swap.backup)
            val container = Paths.get(swap.container)

            runCatching { Files.deleteIfExists(target.resolveSibling(".${target.fileName}.doppelisk-new")) }

            if (Files.isSymbolicLink(target) && resolvedSymlink(File(swap.target)) == container.normalize()) {
                runCatching { Files.delete(target) }
            }
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move(target, backup)
                } catch (t: Throwable) {
                    log("container: restore failed for $target: ${t.message}")
                }
            }
        }
    }

    private fun fail(journal: ContainerJournal, error: String, mechanism: String): ContainerResult {
        journals.save(journal.withStatus(JournalStatus.FAILED, error = error))
        return ContainerResult(false, error, journal.plan, mechanism)
    }

    private fun refused(error: String) = ContainerResult(false, error, null, "refused")

    // --------------------------------------------------------------- utilities

    /**
     * Where the hooked process should look for its mailbox once the container is
     * applied, or null when the real mailbox is still correct.
     */
    private fun visibleMailbox(plan: ContainerPlan, mechanism: String): String? {
        val filesTarget = "${plan.dataDir}/files"

        if (mechanism == "mount") {
            val mount = plan.mounts.firstOrNull { it.target == plan.dataDir }
                ?: plan.mounts.firstOrNull { it.target == filesTarget }
                ?: return null
            return if (mount.target == plan.dataDir) {
                File(File(mount.source, "files"), FileIpcPaths.DIR).path
            } else {
                File(mount.source, FileIpcPaths.DIR).path
            }
        }

        if (mechanism != "swap") return null

        val swap = plan.swaps.firstOrNull { it.target == plan.dataDir }
            ?: plan.swaps.firstOrNull { it.target == filesTarget }
            ?: return null
        return if (swap.target == plan.dataDir) {
            File(File(swap.container, "files"), FileIpcPaths.DIR).path
        } else {
            File(swap.container, FileIpcPaths.DIR).path
        }
    }

    private fun findDataDir(packageName: String): String? {
        File(dataRoot, packageName).let { if (it.isDirectory) return it.path }
        File("/data/data/$packageName").let { if (it.isDirectory) return it.path }
        return null
    }

    /**
     * Confirms that [pid] really is [packageName] running as [uid].
     *
     * The socket gives us a uid and pid, but neither is proof of identity on its
     * own: a uid can be shared and a pid reused. `/proc` is authoritative.
     */
    private fun validateCaller(packageName: String, uid: Int, pid: Int): String? {
        val proc = File("/proc/$pid")
        if (!proc.isDirectory) return "caller pid $pid is gone"

        val status = runCatching { File(proc, "status").readText() }.getOrNull()
            ?: return "cannot read caller status"
        val statusUid = Regex("(?m)^Uid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull()
        if (statusUid != uid) return "caller uid mismatch"

        val cmdline = runCatching { File(proc, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull()
        if (cmdline != null && cmdline.isNotEmpty() && !cmdline.startsWith(packageName)) {
            return "caller process does not belong to $packageName"
        }
        return null
    }

    /** Field 22 of `/proc/<pid>/stat`: the process start time in clock ticks. */
    private fun processStartTicks(pid: Int): Long = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val index = stat.lastIndexOf(')')
        if (index < 0) {
            -1L
        } else {
            stat.substring(index + 2).split(' ').getOrNull(19)?.toLongOrNull() ?: -1L
        }
    }.getOrDefault(-1L)

    private fun pidAlive(journal: ContainerJournal): Boolean {
        if (!File("/proc/${journal.pid}").isDirectory) return false
        return JournalRecovery.pidStillBelongsTo(journal, processStartTicks(journal.pid))
    }

    private fun recoveryAction(journal: ContainerJournal, forceInFlight: Boolean): RecoveryAction {
        if (forceInFlight && journal.status in JournalStatus.IN_FLIGHT) {
            if (journal.mechanism == "mount") return RecoveryAction.RollbackMounts(journal)
            if (journal.mechanism != "swap" && journal.status != JournalStatus.SWAPPING) {
                return if (journal.status == JournalStatus.MOUNTING) {
                    RecoveryAction.RollbackMounts(journal)
                } else {
                    RecoveryAction.RollbackAll(journal)
                }
            }
            return RecoveryAction.RollbackSwaps(journal)
        }
        return JournalRecovery.decide(journal, pidAlive(journal))
    }

    private fun nsInode(pid: Int): Long? = runCatching { Os.stat("/proc/$pid/ns/mnt").st_ino }.getOrNull()

    /**
     * True when [pid] has a mount namespace of its own, i.e. neither init's nor
     * the daemon's. Bind-mounting only helps if the app cannot see init's view.
     */
    private fun isPrivateMountNamespace(pid: Int): Boolean {
        val target = nsInode(pid) ?: return false
        val init = nsInode(1) ?: return false
        val self = nsInode(Os.getpid())
        if (target == init) return false
        return self == null || target != self
    }

    private fun nsenterAvailable(): Boolean = nsenterPrefix().isNotEmpty()

    private fun nsenterWorks(pid: Int): Boolean {
        val prefix = nsenterPrefix()
        if (prefix.isEmpty()) return false
        val result = runCommand(prefix + listOf("-t", pid.toString(), "-m", "--", "true"))
        return result.code == 0
    }

    /** Locates an `nsenter`, preferring the system one over a busybox applet. */
    private fun nsenterPrefix(): List<String> {
        if (commandExists("nsenter")) return listOf("nsenter")
        if (File("/system/bin/nsenter").canExecute()) return listOf("/system/bin/nsenter")
        if (File("/data/adb/magisk/busybox").canExecute()) return listOf("/data/adb/magisk/busybox", "nsenter")
        if (commandExists("busybox")) return listOf("busybox", "nsenter")
        return emptyList()
    }

    private fun commandExists(name: String): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "command -v $name").start()
        p.waitFor(1, TimeUnit.SECONDS)
        p.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    /**
     * Hands [path] to the app: ownership, SELinux label and mode.
     *
     * Without the label the app would be denied access to its own container even
     * though the files are owned by it, because SELinux checks the context and
     * not the uid.
     */
    private fun prepareSource(path: String, uid: Int, appLabel: String?) {
        File(path).mkdirs()

        val owned = runCommand(listOf("chown", "-R", "$uid:$uid", path))
        if (owned.code != 0) throw IOException("chown failed for $path: ${owned.output}")

        if (appLabel != null) {
            val labeled = runCommand(listOf("chcon", "-R", appLabel, path))
            if (labeled.code != 0) throw IOException("chcon failed for $path: ${labeled.output}")
        }

        runCommand(listOf("chmod", "0770", path))
    }

    private fun selinuxLabel(path: String): String? = runCatching {
        Os.getxattr(path, "security.selinux")
    }.getOrNull()
        ?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') }
        ?.takeIf(String::isNotBlank)

    private fun resolvedSymlink(file: File): Path? = runCatching {
        val parent = file.parentFile ?: return null
        val link = Files.readSymbolicLink(file.toPath())
        (if (link.isAbsolute) link else parent.toPath().resolve(link)).normalize()
    }.getOrNull()

    private fun move(target: Path, source: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, *arrayOf<CopyOption>())
        }
    }

    private fun parseArtifact(jsonText: String): DoppeliskArtifact =
        json.decodeFromString(DoppeliskArtifact.serializer(), jsonText)

    private fun runCommand(command: List<String>): CommandResult = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = InputStreamReader(process.inputStream, Charsets.UTF_8).use { it.readText() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            CommandResult(-1, "timeout: ${command.joinToString(" ")}")
        } else {
            CommandResult(process.exitValue(), output.trim())
        }
    } catch (t: Throwable) {
        CommandResult(-1, t.message ?: t.javaClass.simpleName)
    }

    private data class CommandResult(val code: Int, val output: String)

    private companion object {
        const val TAG = "DoppeliskContainer"
        const val MANAGER_PACKAGE = "com.doppelisk.app"
    }
}
