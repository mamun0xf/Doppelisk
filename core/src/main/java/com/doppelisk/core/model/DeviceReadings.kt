package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * Device values only a privileged process can read.
 *
 * The manager is an ordinary app, so several fingerprinting surfaces are closed
 * to it even with a root grant: `Build.SERIAL` needs `READ_PHONE_STATE`, a MAC
 * is not exposed through any app API, and the GL renderer is only reported by
 * SurfaceFlinger. The daemon runs as root and answers those reads instead.
 *
 * The same applies to the kernel and property values a consistency check needs
 * as the *real* side of a comparison. A hooked process can only report them if
 * it manages to read them before it starts spoofing, and some surfaces are not
 * reachable from inside a target app at all. The daemon is not hooked, so its
 * reads are the unspoofed device state.
 *
 * Every field is optional: a value that the platform refuses to report stays
 * empty rather than being invented, and the manager falls back to its own
 * best-effort read.
 */
@Serializable
data class DeviceReadings(
    val serial: String = "",
    val wifiMac: String = "",
    val bluetoothMac: String = "",
    val gpuRenderer: String = "",
    /** `uname -r` as the root daemon sees it, e.g. `4.14.357-Everline-KSUN`. */
    val kernelRelease: String = "",
    /** `uname -v`, the kernel build string. */
    val kernelVersion: String = "",
    /** `uname -m`, e.g. `aarch64`. */
    val machine: String = "",
    /** `ro.product.model`. */
    val model: String = "",
    /** `ro.build.id`. */
    val buildId: String = "",
    /** `ro.build.display.id`. */
    val buildDisplay: String = "",
    /** `gsm.version.baseband`. */
    val baseband: String = "",
    /** `ro.debuggable`, `0` or `1`. */
    val debuggable: String = "",
    /**
     * Whether `/data/adb` exists on the real device.
     *
     * A rooted device always answers true here; the interesting half of the
     * comparison is that the hook layer reports it as hidden.
     */
    val adbPresent: Boolean = false,
    /**
     * Why a field is empty, for display next to it. Empty when everything was
     * read.
     */
    val unavailable: List<String> = emptyList(),
)
