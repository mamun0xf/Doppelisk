package com.doppelisk.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.container.ContainerModes
import com.doppelisk.core.identity.ChassisCatalog
import com.doppelisk.core.identity.GeoValidator
import com.doppelisk.core.identity.LocationModes
import com.doppelisk.core.identity.NetworkPolicyValidator
import com.doppelisk.core.model.ArtifactList
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.DeviceReadings
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.DoppeliskIndex
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.hook.engine.DaemonClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Everything the manager UI asks the daemon to do, in one place.
 *
 * The UI never touches the daemon socket, the profile files or the index
 * directly. It calls these functions, which translate between the serialized wire
 * formats and the model types, and which raise an exception with a message worth
 * showing instead of returning a half-decoded document.
 *
 * Target discovery is deliberately generic: the manager offers every installed,
 * enabled app. There is no list of "supported" packages, because there is no
 * per-app behaviour to support — a profile describes a device, and any app can be
 * given one.
 */
object ManagerRepository {

    /** One selectable app. */
    data class TargetOption(
        val packageName: String,
        val label: String,
        val isSystem: Boolean = false,
        val installed: Boolean = true,
    )

    /** Root/daemon/framework state, plus the index when the daemon answered. */
    data class Status(
        val root: Boolean,
        val daemon: Boolean,
        val index: DoppeliskIndex? = null,
        val error: String? = null,
    )

    private val json = JsonFormats.profile

    // -------------------------------------------------------------- targets

    /**
     * Every installed app the user could assign a profile to.
     *
     * The manager itself is excluded: hooking the manager would let a profile
     * rewrite the UI that configures it. User apps come before system apps, and
     * each group is sorted by label.
     */
    fun installedTargets(context: Context): List<TargetOption> {
        val pm = context.packageManager
        val self = context.packageName

        return pm.getInstalledPackages(0)
            .asSequence()
            .filter { it.packageName != self }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                if (!app.enabled) return@mapNotNull null

                TargetOption(
                    packageName = info.packageName,
                    label = runCatching { app.loadLabel(pm).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(
                compareBy<TargetOption> { it.isSystem }
                    .thenBy { it.label.lowercase(Locale.ROOT) },
            )
            .toList()
    }

    /** True when [packageName] is installed and enabled. */
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.enabled
    }.getOrDefault(false)

    // --------------------------------------------------------------- status

    /**
     * Asks the daemon for the index, starting it if necessary.
     *
     * The daemon is asked first: when it answers, root is obviously available and
     * there is no need to pay for an `su` probe on every refresh. Probing first is
     * also what made a cold start look like "root offline" on a device where the
     * root manager takes a while to answer.
     */
    fun status(context: Context): Status {
        val cached = DaemonClient.getIndex()?.let(::decodeIndex)
        if (cached != null) return Status(root = true, daemon = true, index = cached)

        val daemon = DaemonLauncher.ensureRunning(context)
        val root = daemon || DaemonLauncher.isRootAvailable()

        if (!daemon) {
            return Status(root = root, daemon = false, error = "daemon not running (root=$root)")
        }

        val index = DaemonClient.getIndex()?.let(::decodeIndex)
        return Status(root = root, daemon = true, index = index)
    }

    /**
     * Values only the root daemon can read.
     *
     * Returns null when it cannot answer: the caller then keeps whatever the
     * ordinary app APIs produced instead of failing the whole refresh over an
     * optional field.
     */
    fun deviceReadings(): DeviceReadings? = runCatching {
        val payload = DaemonClient.deviceReadings() ?: return null
        JsonFormats.wire.decodeFromString(DeviceReadings.serializer(), payload)
    }.getOrNull()

    // ------------------------------------------------------------ documents

    fun loadAudit(packageName: String): AuditReport? =
        DaemonClient.getAudit(packageName)?.let { payload ->
            runCatching { json.decodeFromString(AuditReport.serializer(), payload) }.getOrNull()
        }?.let(::resolveNativeReals)

    /**
     * Fills in the `real` side of native observations from the root daemon.
     *
     * The hook can only record a real value when it can read it before the
     * spoofing starts, and the kernel strings, the Wi-Fi MAC and `ro.debuggable`
     * are not reachable from inside a target app at all. The daemon is root and
     * is not hooked, so asking it on demand gives the true value for every
     * native row, including audits recorded by an older build.
     *
     * A resolved value can turn an observation into a mismatch — `machine` stays
     * `aarch64` on both sides, while `/data/adb` really is present and the hook
     * reports it hidden — so [AuditReport.mismatches] is rebuilt rather than
     * trusted from the stored report.
     */
    private fun resolveNativeReals(report: AuditReport): AuditReport {
        if (report.observations.none { it.real == null }) return report

        val readings = deviceReadings() ?: return report
        val facts = mapOf(
            "model" to readings.model,
            "release" to readings.kernelRelease,
            "kernel_version" to readings.kernelVersion,
            "machine" to readings.machine,
            "build_id" to readings.buildId,
            "build_display" to readings.buildDisplay,
            "baseband" to readings.baseband,
            "mac" to readings.wifiMac,
            "debuggable" to readings.debuggable,
            "hidden_data_adb" to if (readings.adbPresent) "present" else "absent",
        )

        val filled = report.observations.map { observation ->
            val real = facts[observation.field]?.takeIf { it.isNotBlank() }
            if (observation.real == null && observation.category == "native" && real != null) {
                observation.copy(real = real)
            } else {
                observation
            }
        }

        return report.copy(
            observations = filled,
            mismatches = filled.filter { it.real != null && it.real != it.returned },
        )
    }

    fun loadArtifact(packageName: String): DoppeliskArtifact? =
        DaemonClient.getArtifact(packageName)?.let(::decodeArtifact)

    fun loadProfile(id: String): DoppeliskArtifact? =
        DaemonClient.getDoppelisk(id)?.let(::decodeArtifact)

    /** Every stored profile assigned to [packageName]. */
    fun listProfiles(packageName: String): List<DoppeliskArtifact> {
        val payload = DaemonClient.listArtifacts(packageName) ?: return emptyList()
        val envelope = runCatching {
            json.decodeFromString(ArtifactList.serializer(), payload)
        }.getOrNull() ?: return emptyList()

        return envelope.artifacts.mapNotNull(::decodeArtifact)
    }

    // ------------------------------------------------------------- mutators

    fun activate(packageName: String, id: String): Boolean =
        DaemonClient.activateDoppelisk(packageName, id)

    fun deleteProfile(packageName: String, id: String): Boolean {
        check(DaemonClient.deleteDoppelisk(packageName, id)) { "daemon rejected profile deletion" }
        return true
    }

    fun clearProfileData(packageName: String, id: String): Boolean {
        check(DaemonClient.clearProfileData(packageName, id)) { "daemon rejected data clear" }
        return true
    }

    /**
     * Validates [artifact] before it is written.
     *
     * These are the contradictions that would make the daemon accept a profile it
     * cannot apply: a default profile with a private data directory, a second
     * shared profile for an app that already has one, a SOCKS5 mode without an
     * exit, or coordinates that disagree with the carrier's timezone.
     */
    fun validateProfile(context: Context, artifact: DoppeliskArtifact) {
        check(DaemonLauncher.ensureRunning(context)) { "root daemon unavailable" }

        val target = artifact.target.packageName
        require(target.isNotBlank()) { "profile has no target package" }

        val container = artifact.container.mode.trim().lowercase(Locale.ROOT)
        require(container in ContainerModes.ALL) { "unknown container mode ${artifact.container.mode}" }

        val isDefault = ConfigPaths.isDefaultProfileId(artifact.id)
        val index = DaemonClient.getIndex()?.let(::decodeIndex)
        val existing = index?.doppelisks?.containsKey(artifact.id) == true

        if (isDefault) {
            require(container == ContainerModes.SHARED) {
                "The Default profile must use the app's real data"
            }
        } else if (!existing && container == ContainerModes.SHARED) {
            require(false) { "Shared data cannot separate profiles; choose Hybrid or Isolated" }
        }

        val network = NetworkPolicyValidator.validate(artifact.network)
        require(network.ok) { network.errors.joinToString("; ") }

        val rawLocation = artifact.location.mode.trim().lowercase(Locale.ROOT)
        val location = LocationModes.normalize(rawLocation)
        if (location == LocationModes.OFF) {
            require(rawLocation == LocationModes.OFF) {
                "unknown location mode ${artifact.location.mode}"
            }
        }

        if (location != LocationModes.OFF) {
            require(artifact.location.lat != null && artifact.location.lon != null) {
                "latitude and longitude are required for $location mode"
            }
        }

        artifact.location.lat?.let {
            require(it in -90.0..90.0) { "latitude must be between -90 and 90" }
        }
        artifact.location.lon?.let {
            require(it in -180.0..180.0) { "longitude must be between -180 and 180" }
        }

        val geo = GeoValidator.validate(
            artifact.location,
            artifact.identity.telephony,
            artifact.location.timezone,
        )
        require(geo.ok) { geo.errors.joinToString("; ") }
    }

    /** Serializes and stores [artifact]; returns its id. */
    fun saveArtifact(context: Context, artifact: DoppeliskArtifact): String {
        check(DaemonLauncher.ensureRunning(context)) { "root daemon unavailable" }

        val payload = json.encodeToString(DoppeliskArtifact.serializer(), artifact)
        check(DaemonClient.putArtifact(artifact.id, payload)) { "daemon rejected artifact" }
        return artifact.id
    }

    // ------------------------------------------------------------- transfer

    /**
     * Exports [id]'s profile to the document the user picked.
     *
     * The daemon owns `/data/adb`, so the file is assembled there first — under
     * this app's own staging directory, the only place the daemon may write —
     * and then streamed into the SAF document. Neither the archive nor the
     * container data is ever held in memory: a zip with data can be gigabytes.
     */
    fun exportProfile(context: Context, id: String, destination: Uri, includeData: Boolean) {
        check(DaemonLauncher.ensureRunning(context)) { "root daemon unavailable" }

        val transferDir = File(context.filesDir, TRANSFER_DIR)
        if (!transferDir.mkdirs() && !transferDir.isDirectory) error("cannot create the staging directory")

        val extension = if (includeData) "zip" else "json"
        val staging = File(transferDir, "export-${ConfigPaths.sanitize(id)}.$extension")

        try {
            // The daemon replaces this file in place, so it has to exist and be
            // empty before the call; a leftover from a crashed export would
            // otherwise be shipped as the new one.
            check(staging.delete() || !staging.exists()) { "could not clear the staging file" }
            check(staging.createNewFile()) { "could not create a staging file" }

            check(DaemonClient.exportProfile(id, staging.path, includeData)) {
                "the daemon could not export this profile"
            }
            check(staging.length() > 0L) { "the daemon produced an empty export" }

            val output = context.contentResolver.openOutputStream(destination)
                ?: error("cannot open the chosen destination")
            output.use { out ->
                FileInputStream(staging).use { input -> input.copyTo(out, TRANSFER_BUFFER) }
            }
        } finally {
            staging.delete()
        }
    }

    /**
     * Imports a profile from the document the user picked and returns the id it
     * was stored under.
     *
     * The chosen stream is copied into this app's staging directory because the
     * daemon cannot open a SAF URI. The extension is only a hint: the daemon
     * detects a zip by its magic bytes.
     */
    fun importProfile(context: Context, source: Uri): String {
        check(DaemonLauncher.ensureRunning(context)) { "root daemon unavailable" }

        val transferDir = File(context.filesDir, TRANSFER_DIR)
        if (!transferDir.mkdirs() && !transferDir.isDirectory) error("cannot create the staging directory")

        val extension = transferExtension(context, source)
        val staging = File(transferDir, "import-${System.currentTimeMillis()}.$extension")

        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("cannot open the selected file")
            input.use { stream ->
                FileOutputStream(staging).use { out -> stream.copyTo(out, TRANSFER_BUFFER) }
            }

            check(staging.length() > 0L) { "the selected file is empty" }

            return DaemonClient.importProfile(staging.path)
                ?: error("the daemon could not import the selected file")
        } finally {
            staging.delete()
        }
    }

    /**
     * The best extension to give the staging file.
     *
     * SAF providers may report no name at all, so a missing or unexpected one
     * falls back to JSON — the format the daemon detects by content anyway.
     */
    private fun transferExtension(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        return when (name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
            "zip" -> "zip"
            "json" -> "json"
            else -> "json"
        }
    }

    // ---------------------------------------------------------------- misc

    /** The shipped chassis catalog. */
    fun chassis(): List<Chassis> = runCatching { ChassisCatalog.all() }.getOrDefault(emptyList())

    /** Asks the daemon to force-stop and start [packageName]. */
    fun relaunch(packageName: String): Boolean = DaemonClient.relaunch(packageName)

    fun defaultProfileId(packageName: String): String = ConfigPaths.defaultProfileId(packageName)

    fun isDefaultId(id: String): Boolean = ConfigPaths.isDefaultProfileId(id)

    /** A plain-text rendering of an audit report, for the copy button. */
    fun formatAudit(report: AuditReport?): String {
        if (report == null) return "No audit yet. Launch the target app once."

        val out = StringBuilder()
        out.appendLine("doppelisk: ${report.doppeliskId ?: "?"}")
        out.appendLine("package:   ${report.packageName}")
        out.appendLine()
        out.appendLine("${"field".padEnd(30)} real -> returned")

        for (observation in report.observations) {
            val field = "${observation.category}.${observation.field}".take(29).padEnd(29)
            val real = observation.real ?: "(n/a)"
            out.appendLine("$field $real -> ${observation.returned}")
        }

        if (report.gaps.isNotEmpty()) {
            out.appendLine()
            out.appendLine("gaps:")
            for (gap in report.gaps) out.appendLine("- $gap")
        }

        return out.toString()
    }

    // ------------------------------------------------------------ internals

    private fun decodeIndex(payload: String): DoppeliskIndex? =
        runCatching { json.decodeFromString(DoppeliskIndex.serializer(), payload) }.getOrNull()

    private fun decodeArtifact(payload: String): DoppeliskArtifact? =
        runCatching { json.decodeFromString(DoppeliskArtifact.serializer(), payload) }.getOrNull()

    /** Staging directory for export/import files, inside this app's own data. */
    private const val TRANSFER_DIR = "transfer"

    /** Copy buffer for archives, which can be much larger than a JSON document. */
    private const val TRANSFER_BUFFER = 64 * 1024
}
