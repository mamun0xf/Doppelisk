package com.doppelisk.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.doppelisk.core.model.Response
import com.doppelisk.core.container.ContainerModes
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.RealBuildSnapshot
import com.doppelisk.hook.engine.ArtifactRepository
import com.doppelisk.hook.engine.DaemonClient
import com.doppelisk.hook.engine.EvidenceCollector
import com.doppelisk.hook.engine.NativeShim
import com.doppelisk.hook.generic.DisplayHooks
import com.doppelisk.hook.generic.GenericHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.util.Locale

/**
 * The Xposed entry point.
 *
 * Everything the disguise needs is decided here, once per process, before the
 * app's own code runs:
 *
 *  1. ask the daemon for this package's profile;
 *  2. ask it to prepare the container and the network, because those are
 *     privileged operations;
 *  3. install the generic hooks;
 *  4. install the native shim;
 *  5. hook `Application.onCreate` to re-apply the parts that only make sense
 *     once the app has a context (display metrics, native refresh).
 *
 * There is no per-app code: a profile describes a device, and every app gets the
 * same treatment. Which apps are hooked at all is decided by the framework's
 * scope, not by anything in this file.
 */
class DoppeliskModule : XposedModule() {

    private var packageName: String = ""
    private var repository: ArtifactRepository? = null
    private var evidence: EvidenceCollector? = null

    /** The shim probe is recorded once; refreshes would only add duplicates. */
    private var nativeReported: Boolean = false

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)

        if (!param.isFirstPackage) {
            log("ignoring embedded package: ${param.packageName}")
            return
        }

        packageName = param.packageName
        log("onPackageLoaded: $packageName (first=${param.isFirstPackage})")

        // The daemon writes into this app's mailbox, which is the only place both
        // sides can reach without root.
        DaemonClient.configure(File(param.applicationInfo.dataDir, "files"))

        val repo = ArtifactRepository(packageName)
        val collector = EvidenceCollector(packageName) { repository?.get() }
        repository = repo
        evidence = collector

        val artifact = repo.get()
        if (artifact == null) {
            if (repo.isVirtualDefault()) {
                // The package is deliberately unassigned. Recording the pass-through
                // state is still useful: it is how the UI shows "nothing applied".
                collector.record("container", "mechanism", null, ContainerModes.SHARED)
                collector.record("profile", "overrides", null, "0 (Default pass-through)")
                collector.report()
                log("virtual Default for $packageName; hooks skipped")
                return
            }

            collector.gap("daemon did not return a doppelisk for $packageName")
            collector.report()
            return
        }

        prepareContainer(collector, artifact)
        prepareNetwork(collector, artifact)
        recordLocation(collector, artifact)

        val realBuild = RealBuildSnapshot(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            buildId = Build.ID,
        )

        // Below-Java values have to be captured before anything starts rewriting
        // them: the generic hooks already intercept `Os.uname`, and the shim goes
        // below that. Reading them here is what lets the audit show what the app
        // would have seen without the disguise.
        val realNative = realNativeValues()

        val context = ModuleContext(artifact, realBuild, collector, ::log)
        val classLoader = hostClassLoader(param)

        runCatching { GenericHooks.install(this, context, classLoader) }
            .onFailure {
                collector.gap("generic hooks failed: ${it.message}")
                log("generic hooks failed", it)
            }

        installNative(param, artifact, collector, realNative)
        hookApplicationStart(artifact, context, realNative)
        collector.report()
    }

    // ------------------------------------------------------------ daemon

    private fun prepareContainer(collector: EvidenceCollector, artifact: com.doppelisk.core.model.DoppeliskArtifact) {
        val response: Response? = DaemonClient.prepareContainer(packageName)

        if (response?.ok == true) {
            val mechanism = response.payload ?: "unknown"
            collector.record("container", "mechanism", null, mechanism)
            collector.record(
                "container",
                "keystoreNamespace",
                null,
                artifact.container.keystoreNamespace,
            )
            log("container ready: $mechanism")
        } else {
            collector.gap("container prepare failed: ${response?.error ?: "daemon unavailable"}")
        }
    }

    private fun prepareNetwork(collector: EvidenceCollector, artifact: com.doppelisk.core.model.DoppeliskArtifact) {
        val mode = artifact.network.mode.trim().lowercase(Locale.ROOT)

        if (mode == "direct" && !artifact.network.killSwitch) {
            collector.record("network", "mode", null, "direct")
            return
        }

        val response = DaemonClient.prepareNetwork(packageName)
        if (response?.ok == true) {
            collector.record("network", "mode", null, response.payload ?: mode)
            collector.record("network", "killSwitch", null, artifact.network.killSwitch.toString())
            log("network ready: ${response.payload ?: mode}")
        } else {
            collector.gap("network prepare failed: ${response?.error ?: "daemon unavailable"}")
        }
    }

    private fun recordLocation(collector: EvidenceCollector, artifact: com.doppelisk.core.model.DoppeliskArtifact) {
        val mode = artifact.location.mode.trim().lowercase(Locale.ROOT)
        if (mode != "off") collector.record("location", "mode", null, artifact.location.mode)
    }

    // ------------------------------------------------------------ native

    private fun installNative(
        param: XposedModuleInterface.PackageLoadedParam,
        artifact: com.doppelisk.core.model.DoppeliskArtifact,
        collector: EvidenceCollector,
        realNative: Map<String, String>,
    ) {
        val cacheDir = runCatching { File(param.applicationInfo.dataDir, "code_cache") }.getOrNull()

        val installed = cacheDir != null &&
            NativeShim.install(this, cacheDir, artifact) { log(it) }

        if (!installed) {
            collector.gap("native shim not installed at package load")
            return
        }
        recordNativeProbe(collector, realNative)
    }

    /**
     * The below-Java values as the real device reports them.
     *
     * The shim's probe is the only reliable way to see what the app will get, and
     * it only reports the hooked view. Capturing the plain values before the hook
     * layer is installed gives every probe field a `real` counterpart, so the
     * audit can actually say what changed instead of just echoing the fake.
     */
    private fun realNativeValues(): Map<String, String> = runCatching {
        val uts = android.system.Os.uname()
        mapOf(
            "model" to Build.MODEL.orEmpty(),
            "release" to uts.release.orEmpty(),
            "kernel_version" to uts.version.orEmpty(),
            "machine" to uts.machine.orEmpty(),
            "build_id" to Build.ID.orEmpty(),
            "build_display" to Build.DISPLAY.orEmpty(),
            "baseband" to Build.getRadioVersion().orEmpty(),
        )
    }.getOrDefault(emptyMap())

    /** Records the shim's probe once; later refreshes would only duplicate it. */
    private fun recordNativeProbe(collector: EvidenceCollector, realNative: Map<String, String>) {
        if (nativeReported) return

        val probe = NativeShim.probe()
        if (probe == null) {
            collector.gap("native probe unavailable")
            return
        }
        nativeReported = true

        for (line in probe.lineSequence().filter { it.isNotBlank() }) {
            val split = line.indexOf('\t')
            if (split <= 0) continue
            val field = line.substring(0, split)
            collector.record("native", field, realNative[field], line.substring(split + 1))
        }
    }

    // -------------------------------------------------------- application

    /**
     * Runs the context-dependent half of the disguise.
     *
     * `Application.onCreate` is the first point where the app has a real context,
     * so it is where the display metrics are recomputed from that context and
     * where the native shim is refreshed for the resources the app has opened.
     * The refreshes are repeated on a timer because the first frames happen
     * before some of the app's own caches are filled.
     */
    private fun hookApplicationStart(
        artifact: com.doppelisk.core.model.DoppeliskArtifact,
        context: HookContext,
        realNative: Map<String, String>,
    ) {
        val onCreate = runCatching {
            Application::class.java.getMethod("onCreate")
        }.getOrNull()

        if (onCreate == null) {
            log("Application.onCreate not found")
            return
        }

        runCatching {
            hook(onCreate).intercept { chain ->
                val app = chain.thisObject as? Application
                val result = chain.proceed()

                if (app != null) {
                    NativeShim.install(this, app, artifact) { log(it) }
                    DisplayHooks.refreshCachedPhysicalDpi(app, context)
                    scheduleDisplayRefresh(app, context)
                    scheduleNativeRefresh()
                    evidence?.let { recordNativeProbe(it, realNative) }
                }

                evidence?.report()
                result
            }
        }.onFailure { log("Application.onCreate hook failed: ${it.message}") }
    }

    private fun scheduleDisplayRefresh(app: Application, context: HookContext) {
        val handler = Handler(Looper.getMainLooper())
        for (delay in listOf(250L, 1000L, 3000L)) {
            handler.postDelayed({ DisplayHooks.refreshCachedPhysicalDpi(app, context) }, delay)
        }
    }

    private fun scheduleNativeRefresh() {
        val handler = Handler(Looper.getMainLooper())
        for (delay in listOf(250L, 1000L, 3000L, 6000L)) {
            handler.postDelayed({ NativeShim.refresh() }, delay)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun hostClassLoader(param: XposedModuleInterface.PackageLoadedParam): ClassLoader? =
        runCatching { param.defaultClassLoader }.getOrNull()

    private fun log(message: String) = log(LOG_LEVEL_INFO, TAG, message)

    private fun log(message: String, error: Throwable) = log(LOG_LEVEL_ERROR, TAG, message, error)

    /**
     * The profile and evidence sink for one hooked process.
     *
     * A plain object rather than an anonymous class so it can be referenced from
     * the `onCreate` closure without capturing the module's mutable fields.
     */
    private class ModuleContext(
        override val artifact: com.doppelisk.core.model.DoppeliskArtifact,
        override val realBuild: RealBuildSnapshot,
        override val boundary: EvidenceCollector,
        private val logger: (String) -> Unit,
    ) : HookContext {
        override fun log(message: String) = logger(message)
    }

    private companion object {
        const val TAG = "Doppelisk"
        const val LOG_LEVEL_INFO = 4
        const val LOG_LEVEL_ERROR = 5
    }
}
