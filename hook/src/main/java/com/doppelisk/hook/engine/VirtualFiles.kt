package com.doppelisk.hook.engine

import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields

/**
 * The read-only files whose contents are synthesised instead of hooked.
 *
 * Most spoofing works by intercepting a Java method, but `/proc` and `/sys` are
 * read with plain file I/O — often from native code — so the only reliable answer
 * is to replace the file contents. Each entry can be:
 *
 *  - [Entry.content] — the whole file, returned verbatim;
 *  - [Entry.replacements] — a set of `KEY=value` lines to substitute inside a real
 *    file, used when most of the file is device-specific and only a few lines
 *    reveal the truth;
 *  - [Entry.fallback] — what to return when the real file cannot be read at all.
 */
object VirtualFiles {

    /**
     * One virtual file.
     *
     * [content] and [fallback] are nullable on purpose: `uevent` has no full
     * replacement, only line substitutions plus a generated fallback.
     */
    data class Entry(
        val content: String? = null,
        val replacements: Map<String, String> = emptyMap(),
        val fallback: String? = null,
    )

    /** Every virtual file this profile needs, keyed by absolute path. */
    fun forArtifact(
        artifact: DoppeliskArtifact,
        applies: (String) -> Boolean = { true },
    ): Map<String, Entry> {
        val build = artifact.identity.build
        val ids = artifact.identity.ids
        val net = artifact.identity.networkIds
        val hardware = artifact.identity.hardware

        val kernel = RootHiding.kernelRelease(hardware.kernel, build)
        val kernelVersion = RootHiding.kernelVersion(build)

        val result = LinkedHashMap<String, Entry>()

        if (applies(IdentityFields.NETWORK_WIFI_MAC)) {
            net.wifiMac.takeIf { it.isNotBlank() }?.let {
                result["/sys/class/net/wlan0/address"] = Entry(content = "$it\n")
            }
        }

        if (applies(IdentityFields.ID_BOOT)) {
            ids.bootId.takeIf { it.isNotBlank() }?.let {
                result["/proc/sys/kernel/random/boot_id"] = Entry(content = "$it\n")
            }
        }

        if (applies(IdentityFields.ID_SERIAL)) {
            ids.serial.takeIf { it.isNotBlank() }?.let {
                result["/sys/class/android_usb/android0/iSerial"] = Entry(content = "$it\n")
            }
        }

        if (applies(IdentityFields.HARDWARE_KERNEL)) {
            result["/proc/sys/kernel/osrelease"] = Entry(content = "$kernel\n")
            result["/proc/version"] = Entry(
                content = "Linux version $kernel (android-build@localhost) " +
                    "(Android clang) $kernelVersion\n",
            )
        }

        // The CPU files only exist for SoCs we have a layout for; without one,
        // inventing a layout would be more suspicious than leaving the real one.
        val cpuApplies = listOf(
            IdentityFields.HARDWARE_SOC,
            IdentityFields.HARDWARE_CPU,
            IdentityFields.HARDWARE_KERNEL,
        ).any(applies)

        if (cpuApplies) {
            CpuIdentity.procCpuInfo(artifact)?.let { result["/proc/cpuinfo"] = Entry(content = it) }
            CpuIdentity.meminfo(RootHiding.ramMbFor(build))?.let {
                result["/proc/meminfo"] = Entry(content = it)
            }
            for ((path, content) in CpuIdentity.sysfs(artifact)) {
                result[path] = Entry(content = content)
            }
        }

        if (applies(IdentityFields.BUILD_MODEL) || applies(IdentityFields.HARDWARE_SOC)) {
            val fullUah = RootHiding.batteryFullMahFor(build) * 1000
            val designUah = RootHiding.batteryMahFor(build) * 1000

            val replacements = mapOf(
                "POWER_SUPPLY_CHARGE_FULL_DESIGN" to designUah.toString(),
                "POWER_SUPPLY_CHARGE_FULL" to fullUah.toString(),
            )
            val fallback = buildString {
                append("POWER_SUPPLY_NAME=battery\n")
                for ((key, value) in replacements) append(key).append('=').append(value).append('\n')
            }

            result["/sys/class/power_supply/battery/uevent"] =
                Entry(replacements = replacements, fallback = fallback)
            result["/sys/class/power_supply/battery/charge_full"] = Entry(content = "$fullUah\n")
            result["/sys/class/power_supply/battery/charge_full_design"] = Entry(content = "$designUah\n")
            result["/sys/class/power_supply/battery/batt_full_capacity"] = Entry(content = "$fullUah\n")
        }

        return result
    }

    /** The subset of [forArtifact] whose whole content is known. */
    fun staticContent(
        artifact: DoppeliskArtifact,
        applies: (String) -> Boolean = { true },
    ): Map<String, String> = forArtifact(artifact, applies)
        .mapNotNull { (path, entry) -> entry.content?.let { path to it } }
        .toMap()
}
