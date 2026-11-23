package com.doppelisk.hook.engine

import com.doppelisk.core.model.BuildIdentity
import com.doppelisk.core.model.HardwareIdentity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Derives the "boring, stock" values that a rooted device would otherwise leak.
 *
 * Two jobs live here:
 *
 *  - **hiding** — lists of packages, paths, properties and shell markers that
 *    only exist on a rooted device, so the hook layer can pretend they do not;
 *  - **derivation** — values that are not in the profile but can be computed from
 *    it (kernel release, baseband, battery size, storage size, sensor names,
 *    manufacture date). Deriving them matters because a chassis that claims to be
 *    a Pixel 8 Pro but reports a 6000 mAh battery is a contradiction.
 *
 * Every table is keyed by model string and has a generic fallback, so a chassis
 * the tables do not know still produces plausible values.
 */
object RootHiding {

    /** Root managers and frameworks. Seeing any of these is proof of rooting. */
    val HIDDEN_PACKAGES: Set<String> = setOf(
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "com.kingroot.kinguser",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.ramdroid.appquarantine",
        "com.devadvance.rootcloak",
        "com.formyhm.hideroot",
        "com.dergoogler.mmrl",
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "de.robv.android.xposed.installer",
        "com.solohsu.android.edxp.manager",
    )

    /** Files and directories that only exist on a rooted device. */
    val HIDDEN_PATHS: List<String> = listOf(
        "/data/adb",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/adb/ksu",
        "/data/adb/ap",
        "/data/adb/lspd",
        "/data/adb/doppelisk",
        "/debug_ramdisk",
        "/dev/.magisk",
        "/sbin/.magisk",
        "/sbin/su",
        "/sbin/magisk",
        "/sbin/ksu",
        "/sbin/ksud",
        "/sbin/busybox",
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/product/bin/su",
        "/system/sbin/su",
        "/data/local/su",
        "/su/bin",
        "/system/bin/magisk",
        "/system/bin/ksu",
        "/system/bin/ksud",
        "/system/bin/busybox",
        "/system/bin/supersu",
        "/system/bin/daemonsu",
        "/system/bin/resetprop",
        "/system/bin/shamiko",
        "/system/bin/magiskpolicy",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU",
        "/system/app/Superuser",
        "/system/etc/init.d",
        "/data/magisk",
        "/cache/magisk.log",
    )

    /** Property prefixes that only a root framework sets. */
    val HIDDEN_PROPERTY_PREFIXES: List<String> = listOf(
        "ro.lsposed",
        "persist.lsposed",
        "ro.magisk",
        "persist.magisk",
        "ro.kernelsu",
        "ro.apatch",
        "ro.zygisk",
        "ro.doppelisk",
        "persist.doppelisk",
    )

    /** Properties that reveal an unlocked or debuggable build. */
    val PROPERTIES: Map<String, String> = linkedMapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.adb.secure" to "1",
        "service.adb.root" to "0",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "ro.build.selinux" to "1",
        "ro.boot.selinux" to "enforcing",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.vbmeta.avb_version" to "1.2",
        "init.svc.adbd" to "running",
        "sys.usb.state" to "mtp,adb",
    )

    private val buildDateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss 'UTC' yyyy", Locale.US)

    private val MANUFACTURE_DATES: Map<String, String> = mapOf(
        "Pixel 8 Pro" to "20231004",
        "Pixel 8a" to "20240507",
        "Pixel 7 Pro" to "20221006",
        "SM-S921B" to "20240117",
        "SM-A346E" to "20230315",
        "SM-A536E" to "20220317",
        "SM-A245F" to "20230419",
        "SM-A736B" to "20220422",
        "SM-M215F" to "20200813",
        "2211133G" to "20221211",
        "M2101K6G" to "20210304",
        "RMX3890" to "20231216",
        "V2231" to "20230301",
        "DE2117" to "20210615",
        "CPH2653" to "20241031",
    )

    private val STORAGE_BYTES: Map<String, Long> = mapOf(
        "Pixel 8 Pro" to 274_877_906_944L,
        "Pixel 8a" to 137_438_953_472L,
        "Pixel 7 Pro" to 274_877_906_944L,
        "SM-S921B" to 274_877_906_944L,
        "SM-A346E" to 137_438_953_472L,
        "SM-A536E" to 274_877_906_944L,
        "SM-A245F" to 68_719_476_736L,
        "SM-A736B" to 137_438_953_472L,
        "SM-M215F" to 68_719_476_736L,
        "2211133G" to 274_877_906_944L,
        "M2101K6G" to 274_877_906_944L,
        "RMX3890" to 274_877_906_944L,
        "V2231" to 274_877_906_944L,
        "DE2117" to 137_438_953_472L,
        "CPH2653" to 274_877_906_944L,
    )

    /** Sensor type → (name, vendor). */
    private val GENERIC_SENSOR_NAMES: Map<Int, Pair<String, String>> = mapOf(
        1 to ("ICM42605M" to "Bosch"),
        2 to ("AK09918C" to "Asahi Kasei"),
        4 to ("ICM42605M" to "Bosch"),
        5 to ("TCS3408" to "ams OSRAM"),
        8 to ("TCS3408" to "ams OSRAM"),
        34 to ("egis_fingerprint" to "Egis"),
    )

    private val SENSOR_NAMES: Map<String, Map<Int, Pair<String, String>>> = mapOf(
        "SM-S921B" to mapOf(
            1 to ("ICM42605M" to "Bosch"),
            2 to ("AK09918C" to "Asahi Kasei"),
            4 to ("ICM42605M" to "Bosch"),
            5 to ("TCS3408" to "ams OSRAM"),
            6 to ("LPS22DF" to "STMicroelectronics"),
            8 to ("TCS3408" to "ams OSRAM"),
            34 to ("samsung_slsi" to "Samsung"),
        ),
        "Pixel 8 Pro" to mapOf(
            1 to ("BMI160" to "Bosch"),
            2 to ("AK09916" to "Asahi Kasei"),
            4 to ("BMI160" to "Bosch"),
            5 to ("TCS3414" to "ams OSRAM"),
            8 to ("STK3311" to "Sitronix"),
            34 to ("goodix_fp" to "Goodix"),
        ),
    )

    private val BATTERY_MAH: Map<String, Int> = mapOf(
        "Pixel 8 Pro" to 5050,
        "Pixel 8a" to 4492,
        "Pixel 7 Pro" to 5000,
        "SM-S921B" to 4000,
        "SM-A346E" to 5000,
        "SM-A536E" to 5000,
        "SM-A245F" to 5000,
        "SM-A736B" to 5000,
        "SM-M215F" to 6000,
        "2211133G" to 4500,
        "M2101K6G" to 5020,
        "RMX3890" to 5000,
        "V2231" to 4600,
        "DE2117" to 5000,
        "CPH2653" to 6000,
    )

    private val RAM_MB: Map<String, Int> = mapOf(
        "Pixel 8 Pro" to 12288,
        "Pixel 8a" to 8192,
        "Pixel 7 Pro" to 12288,
        "SM-S921B" to 8192,
        "SM-A346E" to 8192,
        "SM-A536E" to 8192,
        "SM-A245F" to 6144,
        "SM-A736B" to 8192,
        "SM-M215F" to 6144,
        "2211133G" to 12288,
        "M2101K6G" to 8192,
        "RMX3890" to 8192,
        "V2231" to 8192,
        "DE2117" to 4096,
        "CPH2653" to 12288,
    )

    /** SoC slug → (manufacturer, marketing name). */
    private val SOC_NAMES: Map<String, Pair<String, String>> = mapOf(
        "google/tensor-g3" to ("Google" to "Tensor G3"),
        "google/tensor-g2" to ("Google" to "Tensor G2"),
        "samsung/exynos2400" to ("Samsung" to "Exynos 2400"),
        "samsung/exynos1280" to ("Samsung" to "Exynos 1280"),
        "samsung/exynos9611" to ("Samsung" to "Exynos 9611"),
        "mediatek/mt6877" to ("MediaTek" to "Dimensity 1080"),
        "mediatek/mt6789" to ("MediaTek" to "Helio G99"),
        "mediatek/mt6886" to ("MediaTek" to "Dimensity 7200"),
        "qualcomm/sm7325" to ("Qualcomm" to "Snapdragon 778G"),
        "qualcomm/sm8550" to ("Qualcomm" to "Snapdragon 8 Gen 2"),
        "qualcomm/sm7150" to ("Qualcomm" to "Snapdragon 732G"),
        "qualcomm/sm6225" to ("Qualcomm" to "Snapdragon 685"),
        "qualcomm/sm4350" to ("Qualcomm" to "Snapdragon 480"),
        "qualcomm/sm8750" to ("Qualcomm" to "Snapdragon 8 Elite"),
    )

    // ------------------------------------------------------------------ hiding

    fun isHiddenPackage(packageName: String?): Boolean =
        packageName != null && packageName in HIDDEN_PACKAGES

    /**
     * True when [path] names a root artifact.
     *
     * Two special cases are handled before the table lookup:
     *
     *  - `path` may be a NUL-terminated buffer, so everything after the first NUL
     *    is ignored;
     *  - `/proc/<pid>/root/...` is rewritten, because an app can reach the real
     *    filesystem that way and must not be able to dodge the check.
     */
    fun isHiddenPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false

        val head = path.substringBefore('\u0000')
        var normalized = Regex("^/proc/(self|thread-self|\\d+)/root").replace(head, "")
        if (normalized.isEmpty()) normalized = "/"

        // Our own container store is as revealing as a root manager.
        if (normalized.split('/').any { it.contains(".doppelisk") }) return true

        return HIDDEN_PATHS.any { normalized == it || normalized.startsWith("$it/") }
    }

    fun isHiddenProperty(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return HIDDEN_PROPERTY_PREFIXES.any { name.startsWith(it) }
    }

    /** True when [command] looks like a root probe, e.g. `which su`. */
    fun isRootCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        val lower = command.lowercase(Locale.ROOT)
        return ROOT_COMMAND_MARKERS.any { lower.contains(it) }
    }

    private val ROOT_COMMAND_MARKERS = listOf(
        "which su",
        "type su",
        "command -v su",
        "su -c",
        "su 0",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        " magisk",
        "magisk ",
        "ksud",
        "supersu",
        "busybox",
    )

    // -------------------------------------------------------------- derivation

    /**
     * Kernel release for a chassis.
     *
     * The `-androidNN-` part of a release string is the only part apps can map
     * back to an Android version, so a mismatched pair is a strong signal.
     */
    fun kernelReleaseFor(build: BuildIdentity): String = when (build.release) {
        "10" -> "4.14.190-android10-11-g6a7b8c9d0e1f"
        "11" -> "5.4.254-android11-11-g5f6a7b8c9d0e"
        "12" -> "5.10.209-android12-11-g4e5f6a7b8c9d"
        "13" -> "5.15.144-android13-11-g3d4e5f6a7b8c"
        "14" -> "6.1.75-android14-11-g2c3d4e5f6a7b"
        "15" -> "6.6.30-android15-11-g1b2c3d4e5f6a"
        "16" -> "6.6.30-android16-11-g0a1b2c3d4e5f"
        else -> "5.10.209-android12-11-g4e5f6a7b8c9d"
    }

    fun basebandFor(build: BuildIdentity): String {
        if (build.baseband.isNotBlank()) return build.baseband

        return when (build.model) {
            "Pixel 8 Pro" -> "g5300q-250505-250605-B-15081906"
            "Pixel 8a" -> "g5300q-250405-250505-B-13578606"
            "Pixel 7 Pro" -> "g5300i-250405-250505-B-15001963"
            "2211133G" -> "MPSS.DE.2.0-00045-KALAMA_GEN1"
            "M2101K6G" -> "MPSS.AT.4.0-00045-SM7150"
            "RMX3890" -> "MPSS.DE.3.0-00045-SM6225"
            "V2231" -> "MOLY.NR16.R1.TC8.PR1.SP.V1.P20"
            "DE2117" -> "MPSS.AT.4.0-00045-SM4350"
            "CPH2653" -> "MPSS.DE.3.0-00045-SM8750"
            else -> if (build.brand.equals("samsung", ignoreCase = true) && build.bootloader.isNotBlank()) {
                build.bootloader
            } else {
                ""
            }
        }
    }

    /**
     * Normalises a kernel string so it agrees with [build]'s Android version.
     *
     * A stored kernel that already carries an `-androidNN-` tag has it rewritten;
     * a bare version such as `5.10` is expanded into a full release with a
     * deterministic build hash, so the same profile always yields the same kernel.
     */
    fun kernelRelease(kernel: String, build: BuildIdentity): String {
        val stored = kernel.trim()
        if (stored.isBlank()) return kernelReleaseFor(build)

        if (stored.contains('-')) {
            val release = build.release.trim()
            if (release.isBlank()) return stored
            return Regex("-android\\d+-").replace(stored, "-android$release-")
        }

        val base = when {
            stored.startsWith("4.14") -> "4.14.190"
            stored.startsWith("5.4") -> "5.4.254"
            stored.startsWith("5.10") -> "5.10.209"
            stored.startsWith("5.15") -> "5.15.153"
            stored.startsWith("6.1") -> "6.1.75"
            stored.startsWith("6.6") -> "6.6.30"
            stored.startsWith("6.12") -> "6.12.23"
            else -> stored
        }

        val release = build.release.ifBlank { "14" }
        val suffix = Integer.toHexString("$stored:$release".hashCode()).padStart(8, '0')
        return "$base-android$release-11-g$suffix"
    }

    /** The `#1 SMP PREEMPT ...` banner, dated from the security patch. */
    fun kernelVersion(build: BuildIdentity): String {
        val date = runCatching {
            if (build.securityPatch.isBlank()) null else LocalDate.parse(build.securityPatch)
        }.getOrNull() ?: LocalDate.of(2025, 1, 7)

        return "#1 SMP PREEMPT " + date.atTime(LocalTime.of(10, 23, 45)).format(buildDateFormat)
    }

    /** The date shown as `ro.build.date`, derived from the security patch. */
    fun buildDate(build: BuildIdentity): BuildDate {
        val date = runCatching { LocalDate.parse(build.securityPatch) }.getOrNull()
            ?: LocalDate.of(2025, 1, 7)
        val timestamp = date.atTime(LocalTime.of(10, 23, 45))

        return BuildDate(
            text = timestamp.format(buildDateFormat),
            epochSeconds = timestamp.toEpochSecond(ZoneOffset.UTC),
        )
    }

    /** `ro.vendor.build.date`, as the eight-digit `YYYYMMDD` form. */
    fun manufactureDate(build: BuildIdentity): String =
        MANUFACTURE_DATES[build.model] ?: build.securityPatch.replace("-", "")

    fun ramMbFor(build: BuildIdentity): Int = RAM_MB[build.model] ?: when {
        build.model.contains("Ultra", ignoreCase = true) -> 12288
        build.model.contains("Pro Max", ignoreCase = true) -> 12288
        build.model.contains("Pro", ignoreCase = true) -> 12288
        else -> 8192
    }

    fun ramBytesFor(build: BuildIdentity): Long = ramMbFor(build).toLong() * 1024 * 1024

    fun batteryMahFor(build: BuildIdentity): Int = BATTERY_MAH[build.model] ?: 5000

    /** Rated capacity is reported slightly below design capacity on real devices. */
    fun batteryFullMahFor(build: BuildIdentity): Int =
        maxOf((batteryMahFor(build) * 0.97).toInt(), 1)

    fun storageBytesFor(build: BuildIdentity): Long =
        STORAGE_BYTES[build.model] ?: 274_877_906_944L

    fun sensorNamesFor(build: BuildIdentity): Map<Int, Pair<String, String>> =
        SENSOR_NAMES[build.model] ?: GENERIC_SENSOR_NAMES

    /**
     * Turns a SoC slug such as `samsung/exynos9611` into a manufacturer and a
     * marketing name.
     *
     * Known slugs come from the table. Unknown ones are still split on `/` so a
     * custom chassis degrades into `("Samsung", "Exynos 9999")` instead of leaking
     * an internal slug.
     */
    fun socIdentity(hardware: HardwareIdentity): SocIdentity {
        val raw = hardware.soc.trim()
        if (raw.isBlank()) return SocIdentity("", "")

        SOC_NAMES[raw]?.let { (manufacturer, model) -> return SocIdentity(manufacturer, model) }

        val vendor = raw.substringBefore('/').trim()
        val slug = raw.substringAfter('/', "").trim()

        val manufacturer = when (vendor.lowercase(Locale.ROOT)) {
            "qualcomm" -> "Qualcomm"
            "google" -> "Google"
            "mediatek" -> "MediaTek"
            "samsung" -> "Samsung"
            else -> vendor.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }

        val model = slug
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase(Locale.ROOT) } }

        return SocIdentity(manufacturer, model)
    }

    /**
     * GPU identity for a chassis.
     *
     * [gpuRenderer] is the value the app would have seen; when it is blank the
     * renderer is inferred from the SoC family, because an app that reads the
     * renderer before we hook it must still get something coherent.
     */
    fun glIdentity(build: BuildIdentity, gpuRenderer: String): GlIdentity {
        val renderer = gpuRenderer.ifBlank {
            when {
                socOrModelContains(build, "tensor") -> "Mali-G715"
                socOrModelContains(build, "snapdragon") || build.model.startsWith("SM-") -> "Adreno (TM) 750"
                build.brand.equals("samsung", ignoreCase = true) -> "Xclipse 940"
                else -> ""
            }
        }

        val vendor = when {
            renderer.contains("Adreno", ignoreCase = true) -> "Qualcomm"
            renderer.contains("Mali", ignoreCase = true) -> "ARM"
            renderer.contains("Xclipse", ignoreCase = true) -> "Samsung"
            renderer.contains("PowerVR", ignoreCase = true) -> "Imagination Technologies"
            renderer.isBlank() -> ""
            else -> "Google"
        }

        return GlIdentity(
            vendor = vendor,
            renderer = renderer,
            version = "OpenGL ES 3.2 v1.r38p1-01eac0",
            glsl = "OpenGL ES GLSL ES 3.20",
            eglVendor = "Android",
            eglVersion = "1.4 Android META-EGL",
        )
    }

    private fun socOrModelContains(build: BuildIdentity, needle: String): Boolean =
        build.model.contains(needle, ignoreCase = true) || build.board.contains(needle, ignoreCase = true)

    /** `ro.build.date` as both display text and an epoch. */
    data class BuildDate(val text: String, val epochSeconds: Long)

    data class SocIdentity(val manufacturer: String, val model: String)

    data class GlIdentity(
        val vendor: String,
        val renderer: String,
        val version: String,
        val glsl: String,
        val eglVendor: String,
        val eglVersion: String,
    )
}
