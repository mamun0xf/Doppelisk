package com.doppelisk.hook.engine

import android.content.Context
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.applies
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.zip.ZipFile

/**
 * JNI bridge to `libdoppelisk_shim.so`.
 *
 * The shim is the half of the disguise that lives below Java: it rewrites
 * `__system_property_*` lookups, `open`/`stat` results for hidden paths, and
 * `glGetString`, which Java hooks cannot reach at all. Java pushes everything it
 * wants spoofed down as a line-oriented config, so the native side never has to
 * parse JSON or know about profiles.
 *
 * The library is loaded from the hooked app's own code cache, extracted from the
 * manager APK on first use, because a hooked app cannot read the manager's data
 * directory and cannot write to `/data/adb` either.
 *
 * Native symbols are fixed by the prebuilt library:
 *
 *  - `Java_com_doppelisk_hook_engine_NativeShim_nativeInstall(String)I`
 *  - `Java_com_doppelisk_hook_engine_NativeShim_nativeUpdate(String)V`
 *  - `Java_com_doppelisk_hook_engine_NativeShim_nativeRefresh()I`
 *  - `Java_com_doppelisk_hook_engine_NativeShim_nativeRefreshPath(String)I`
 *  - `Java_com_doppelisk_hook_engine_NativeShim_nativeProbe()String`
 */
object NativeShim {

    private const val LIBRARY = "libdoppelisk_shim.so"
    private const val SHIM_ENTRY_SUFFIX = "/$LIBRARY"
    private const val ABI = "arm64-v8a"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var installed: Boolean = false

    fun isInstalled(): Boolean = installed

    /** Installs the shim, using the app's code cache for the library. */
    fun install(
        xposed: XposedInterface,
        context: Context,
        artifact: DoppeliskArtifact,
        log: (String) -> Unit,
    ): Boolean = install(xposed, context.codeCacheDir, artifact, log)

    /**
     * Installs the shim hooks.
     *
     * Returns false when the library cannot be loaded or the native install
     * fails; a false result means the process simply keeps the Java-level
     * disguise, which is degraded but not broken.
     */
    fun install(
        xposed: XposedInterface,
        cacheDir: File,
        artifact: DoppeliskArtifact,
        log: (String) -> Unit,
    ): Boolean {
        if (!ensureLoaded(xposed, cacheDir, log)) return false

        val config = buildConfig(artifact)
        return runCatching {
            val patches = nativeInstall(config)
            installed = true
            log("native shim installed ($patches relocations patched)")
            true
        }.getOrElse { error ->
            log("native install failed: ${error.message}")
            false
        }
    }

    /** Replaces the spoofed set without reinstalling the hooks. */
    fun update(artifact: DoppeliskArtifact) {
        if (!installed) return
        runCatching { nativeUpdate(buildConfig(artifact)) }
    }

    /** Re-applies every cached patch; [path] narrows it to one file. */
    fun refresh(path: String? = null) {
        if (!installed) return

        if (path.isNullOrBlank()) {
            runCatching { nativeRefresh() }
        } else {
            runCatching { nativeRefreshPath(path) }
        }
    }

    /** Diagnostic dump of the installed hook state, or null when unavailable. */
    fun probe(): String? {
        if (!installed) return null
        return runCatching { nativeProbe() }.getOrNull()
    }

    // ------------------------------------------------------------- config

    /**
     * Renders the artifact as the shim's line protocol.
     *
     * One directive per line, tab-separated:
     *
     *  - `V` — protocol version, always `1`;
     *  - `P` — a system property (`P<TAB>key<TAB>value`);
     *  - `H` — a path to hide (`H<TAB>path`);
     *  - `U` — a replacement `uname` (`U<TAB>node<TAB>release<TAB>version<TAB>machine`);
     *  - `F` — a whole fake file (`F<TAB>path<TAB>content`);
     *  - `R` — a fallback body for a file that may not exist (`R<TAB>path<TAB>body`);
     *  - `X` — one replaced `key=value` line in a real file (`X<TAB>path<TAB>key<TAB>value`);
     *  - `M` — an interface MAC (`M<TAB>iface<TAB>mac`);
     *  - `G` — one GL/EGL string (`G<TAB>field<TAB>value`).
     *
     * Values that can contain tabs or newlines are escaped, because a newline in
     * a file body would otherwise look like the next directive.
     */
    fun buildConfig(artifact: DoppeliskArtifact): String {
        val build = artifact.identity.build
        val ids = artifact.identity.ids
        val hardware = artifact.identity.hardware
        val network = artifact.identity.networkIds

        val out = StringBuilder(65536)

        line(out, "V", "1")

        for ((key, value) in RootHiding.PROPERTIES) line(out, "P", key, value)
        for ((key, value) in IdentityProperties.forArtifact(artifact) { artifact.applies(it) }) {
            line(out, "P", key, value)
        }

        for (path in RootHiding.HIDDEN_PATHS) line(out, "H", path)
        // The manager's own on-disk store must never be visible to a hooked app.
        line(out, "H", "/.doppelisk-store")
        line(out, "H", ".doppelisk-new")

        if (artifact.applies(IdentityFields.HARDWARE_KERNEL)) {
            val release = RootHiding.kernelRelease(hardware.kernel, build)
            val version = RootHiding.kernelVersion(build)
            line(out, "U", "localhost", release, version, "aarch64")
        }

        for ((path, entry) in VirtualFiles.forArtifact(artifact) { artifact.applies(it) }) {
            entry.content?.let { line(out, "F", path, escaped(it)) }
            entry.fallback?.let { line(out, "R", path, escaped(it)) }
            for ((key, value) in entry.replacements) line(out, "X", path, key, value)
        }

        if (artifact.applies(IdentityFields.NETWORK_WIFI_MAC) && network.wifiMac.isNotBlank()) {
            line(out, "M", "wlan0", network.wifiMac)
        }

        if (artifact.applies(IdentityFields.HARDWARE_GPU)) {
            val gl = RootHiding.glIdentity(build, hardware.gpuRenderer)
            line(out, "G", "vendor", gl.vendor)
            line(out, "G", "renderer", gl.renderer)
            line(out, "G", "version", gl.version)
            line(out, "G", "glsl", gl.glsl)
            line(out, "G", "egl_vendor", gl.eglVendor)
            line(out, "G", "egl_version", gl.eglVersion)
        }

        return out.toString()
    }

    private fun line(out: StringBuilder, vararg parts: String) {
        out.append(parts.joinToString("\t")).append('\n')
    }

    /** Escapes the three characters that would break the line protocol. */
    private fun escaped(value: String): String {
        if (value.none { it == '\t' || it == '\n' || it == '\\' }) return value

        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\\' -> out.append("\\\\")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    // -------------------------------------------------------------- loader

    /**
     * Loads the shim into this process.
     *
     * Three sources are tried in order: the manager's extracted copy in the
     * app's code cache, the copy inside the APK (extracted on the spot), and
     * finally the normal library search path, for a build that ships the shim in
     * `jniLibs` of the target app.
     */
    private fun ensureLoaded(
        @Suppress("UNUSED_PARAMETER") xposed: XposedInterface,
        cacheDir: File,
        log: (String) -> Unit,
    ): Boolean {
        if (loaded) return true

        synchronized(this) {
            if (loaded) return true

            val cached = File(cacheDir, LIBRARY)
            if (cached.isFile && cached.canRead()) {
                runCatching {
                    System.load(cached.path)
                    loaded = true
                }.onFailure { log("native load from cache failed: ${it.message}") }
            }
            if (loaded) return true

            val apk = System.getenv("DOPPELISK_APK")
                ?: System.getenv("CLASSPATH")?.substringBefore(':')
            if (apk != null && File(apk).isFile) {
                runCatching {
                    cacheDir.mkdirs()
                    ZipFile(apk).use { zip ->
                        val entry = zip.getEntry("lib/$ABI$SHIM_ENTRY_SUFFIX")
                            ?: zip.entries().asSequence()
                                .firstOrNull { it.name.endsWith(SHIM_ENTRY_SUFFIX) }
                            ?: error("APK has no $LIBRARY")
                        zip.getInputStream(entry).use { input ->
                            cached.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    cached.setExecutable(true, false)
                    System.load(cached.path)
                    loaded = true
                }.onFailure { log("native extraction failed: ${it.message}") }
            }
            if (loaded) return true

            runCatching {
                System.loadLibrary("doppelisk_shim")
                loaded = true
            }.onFailure { log("native shim not available: ${it.message}") }

            return loaded
        }
    }

    @JvmStatic
    private external fun nativeInstall(config: String): Int

    @JvmStatic
    private external fun nativeUpdate(config: String)

    @JvmStatic
    private external fun nativeRefresh(): Int

    @JvmStatic
    private external fun nativeRefreshPath(path: String): Int

    @JvmStatic
    private external fun nativeProbe(): String?
}
