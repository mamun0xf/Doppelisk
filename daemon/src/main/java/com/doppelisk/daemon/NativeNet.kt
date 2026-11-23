package com.doppelisk.daemon

import java.io.File
import java.util.zip.ZipFile

/**
 * Access to the native network helper.
 *
 * The daemon runs as root through `app_process`, so it cannot rely on the
 * normal `System.loadLibrary` search path. The shim is therefore resolved in
 * three steps:
 *
 *  1. the cached copy under `/data/adb/doppelisk/lib/`,
 *  2. extracted from the APK named by `DOPPELISK_APK` (or `CLASSPATH`),
 *  3. `System.loadLibrary` as a last resort.
 *
 * The JNI symbol in `libdoppelisk_shim.so` is
 * `Java_com_doppelisk_daemon_NativeNet_originalDst`, so the native method must
 * keep exactly that name.
 */
object NativeNet {

    private const val ROOT = "/data/adb/doppelisk"
    private const val SHIM_RELATIVE = "lib/libdoppelisk_shim.so"
    private const val SHIM_ENTRY_SUFFIX = "/libdoppelisk_shim.so"
    private const val ABI = "arm64-v8a"

    @Volatile
    private var loaded: Boolean = false

    /** Original destination of a redirected TCP socket, or null when unknown. */
    fun dst(fd: Int): String? {
        if (!ensureLoaded()) return null
        return runCatching { originalDst(fd) }.getOrNull()
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true

            val cached = File(ROOT, SHIM_RELATIVE)
            if (cached.isFile && cached.canRead()) {
                runCatching {
                    System.load(cached.path)
                    loaded = true
                }
            }
            if (loaded) return true

            val apk = System.getenv("DOPPELISK_APK")
                ?: System.getenv("CLASSPATH")?.substringBefore(':')
                ?: return false
            if (!File(apk).isFile) return false

            runCatching {
                val out = File(ROOT, SHIM_RELATIVE)
                out.parentFile?.mkdirs()
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/$ABI$SHIM_ENTRY_SUFFIX")
                        ?: zip.entries().asSequence()
                            .firstOrNull { it.name.endsWith(SHIM_ENTRY_SUFFIX) }
                        ?: error("APK has no libdoppelisk_shim.so")
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                System.load(out.path)
                loaded = true
            }

            if (loaded) return true
            runCatching {
                System.loadLibrary("doppelisk_shim")
                loaded = true
            }
            return loaded
        }
    }

    private external fun originalDst(fd: Int): String?
}
