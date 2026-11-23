package com.doppelisk.app

import android.content.Context
import android.os.Process
import com.doppelisk.core.config.FileIpcPaths
import com.doppelisk.hook.engine.DaemonClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Starts the root daemon.
 *
 * The daemon is the privileged half: it reads `/data/system/packages.list`,
 * creates mount namespaces, and writes the profiles that hooked apps read. It
 * runs as `app_process` under root, not as an Android service, so nothing starts
 * it automatically — this object does, on demand, from the manager.
 *
 * `app_process` cannot run a dex inside an APK directly, so the APK's classes are
 * extracted to a directory first and that directory is used as the classpath.
 */
object DaemonLauncher {

    private val DEX_NAME = Regex("classes\\d*\\.dex")

    /** Places a `su` binary might live, in the order they are tried. */
    private const val SU_PROBE_SECONDS = 4L

    /** How long a failed launch is remembered before another is attempted. */
    private const val RETRY_COOLDOWN_MS = 15_000L

    private val ROOT_COMMANDS = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/debug_ramdisk/su",
    )

    @Volatile
    private var rootAvailable: Boolean = false

    @Volatile
    private var lastLaunchAttemptMs: Long = 0L

    /** True when some `su` on the device answers, cached after the first success. */
    fun isRootAvailable(): Boolean {
        if (rootAvailable) return true

        val available = ROOT_COMMANDS
            .filter { it == "su" || File(it).canExecute() }
            .any { command ->
                runCatching {
                    val process = ProcessBuilder(command, "-c", "id")
                        .redirectErrorStream(true)
                        .start()

                    if (process.waitFor(SU_PROBE_SECONDS, TimeUnit.SECONDS)) {
                        process.exitValue() == 0
                    } else {
                        process.destroyForcibly()
                        false
                    }
                }.getOrDefault(false)
            }

        if (available) rootAvailable = true
        return available
    }

    /**
     * Ensures the daemon is up, starting it if necessary.
     *
     * The first attempt uses `su -M` (a fresh mount namespace); if the daemon does
     * not answer within four seconds the launch is retried without it, because a
     * kernel that does not support mount namespaces fails the whole command.
     */
    fun ensureRunning(context: Context): Boolean {
        if (DaemonClient.ping()) return true
        // A refresh that runs while the previous launch is still starting would
        // fork a second `app_process` that immediately loses the lock file, so a
        // failed attempt is not retried for a few seconds.
        if (System.currentTimeMillis() - lastLaunchAttemptMs < RETRY_COOLDOWN_MS) return false
        if (!isRootAvailable()) return false
        lastLaunchAttemptMs = System.currentTimeMillis()

        val ipc = File(context.filesDir, FileIpcPaths.DIR)
        ipc.mkdirs()

        val apk = File(context.applicationInfo.sourceDir)
        val classpath = daemonClasspath(context, apk)
        val uid = Process.myUid()

        val command = "nohup sh -c 'DOPPELISK_MANAGER_UID=$uid " +
            "DOPPELISK_MANAGER_IPC=${ipc.absolutePath} " +
            "DOPPELISK_APK=${apk.absolutePath} " +
            "CLASSPATH=$classpath " +
            "app_process /system/bin com.doppelisk.daemon.DoppeliskDaemon' >/dev/null 2>&1 &"

        for (mountMaster in listOf(true, false)) {
            launch(command, mountMaster)
            if (awaitDaemon()) return true
        }
        return false
    }

    private fun awaitDaemon(): Boolean {
        repeat(25) {
            Thread.sleep(100L)
            if (DaemonClient.ping()) return true
        }
        return false
    }

    // ---------------------------------------------------------- classpath

    /**
     * Returns the classpath `app_process` needs to find the daemon.
     *
     * The dex files are extracted once and reused until the APK changes; the
     * stamp is length plus mtime, which is enough to notice a reinstall without
     * hashing a multi-megabyte file on every launch.
     */
    private fun daemonClasspath(context: Context, apk: File): String {
        val dir = File(context.filesDir, "doppelisk/daemon-dex")
        val stamp = "${apk.length()}:${apk.lastModified()}"
        val marker = File(dir, ".apk-stamp")
        val cached = dexFiles(dir)

        if (marker.isFile && marker.readText() == stamp && cached.isNotEmpty()) {
            return cached.joinToString(":") { it.absolutePath }
        }

        val extracted = runCatching {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }

            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX_NAME.matches(it.name) }
                    .sortedBy { dexOrdinal(it.name) }
                    .forEach { entry ->
                        val out = File(dir, entry.name)
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
            }

            dexFiles(dir)
        }.getOrDefault(emptyList())

        if (extracted.isEmpty()) {
            throw IllegalArgumentException("APK contains no dex files")
        }

        marker.writeText(stamp)
        return extracted.joinToString(":") { it.absolutePath }
    }

    private fun dexFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && DEX_NAME.matches(it.name) }
            ?.sortedBy { dexOrdinal(it.name) }
            ?: emptyList()

    /** `classes.dex` sorts before `classes2.dex`, `classes10.dex` after both. */
    private fun dexOrdinal(name: String): Int {
        val ordinal = name.removePrefix("classes").removeSuffix(".dex").ifBlank { "1" }
        return ordinal.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun launch(command: String, mountMaster: Boolean) {
        val args = if (mountMaster) {
            arrayOf("su", "-M", "-c", command)
        } else {
            arrayOf("su", "-c", command)
        }
        runCatching { Runtime.getRuntime().exec(args) }
    }
}
