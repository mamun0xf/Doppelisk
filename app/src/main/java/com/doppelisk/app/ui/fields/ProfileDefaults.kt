package com.doppelisk.app.ui.fields

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.model.BuildIdentity
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.ChassisRef
import com.doppelisk.core.model.DeviceReadings
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.DisplayIdentity
import com.doppelisk.core.model.HardwareIdentity
import com.doppelisk.core.model.Identity
import com.doppelisk.core.model.Ids
import com.doppelisk.core.model.NetworkIds
import com.doppelisk.core.model.Target
import com.doppelisk.core.model.TelephonyIdentity
import com.doppelisk.core.model.withNoOverrides
import com.doppelisk.app.ManagerRepository
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * The real device, read once and reused.
 *
 * It serves two purposes: it is the value behind every unpinned field in the
 * editor, and it is the identity of the implicit `Default` profile — the one that
 * says "leave this app alone". Reading it is deliberately best-effort: on a
 * hardened ROM several of these calls throw, and a missing value is better than a
 * crash in the manager.
 */
data class DeviceSnapshot(
    val build: BuildIdentity,
    val ids: Ids,
    val display: DisplayIdentity,
    val hardware: HardwareIdentity,
    val networkIds: NetworkIds,
    val telephony: TelephonyIdentity,
)

object ProfileDefaults {

    /** The implicit profile id of [packageName]. */
    fun defaultId(packageName: String): String = ConfigPaths.defaultProfileId(packageName)

    /** True when [id] names an implicit `Default` profile rather than a stored one. */
    fun isDefaultId(id: String): Boolean = ConfigPaths.isDefaultProfileId(id)

    /**
     * Reads the real device. Safe to call from a background thread.
     *
     * [readings] carries the values only root can see (serial, MACs, renderer)
     * and [chassis] supplies facts the app APIs do not expose at all, such as the
     * Wi-Fi chip vendor. Both are optional: without them the snapshot simply has
     * more empty fields.
     */
    fun snapshot(
        context: Context,
        readings: DeviceReadings? = null,
        chassis: List<Chassis> = emptyList(),
    ): DeviceSnapshot {
        val build = BuildIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            board = Build.BOARD.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            bootloader = Build.BOOTLOADER.orEmpty(),
            baseband = Build.getRadioVersion().orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            id = Build.ID.orEmpty(),
            displayId = Build.DISPLAY.orEmpty(),
            incremental = Build.VERSION.INCREMENTAL.orEmpty(),
            type = Build.TYPE.orEmpty(),
            tags = Build.TAGS.orEmpty(),
            host = Build.HOST.orEmpty(),
            user = Build.USER.orEmpty(),
            release = Build.VERSION.RELEASE.orEmpty(),
            securityPatch = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH.orEmpty() else "",
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )

        val ids = Ids(
            ssaid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
            gaid = "",
            serial = serial().ifEmpty { readings?.serial.orEmpty() },
            bootId = bootId(),
            imei = null,
            appSetId = "",
        )

        val display = display(context)

        val hardware = HardwareIdentity(
            soc = soc(),
            gpuRenderer = readings?.gpuRenderer.orEmpty(),
            cpuHardware = Build.HARDWARE.orEmpty(),
            kernel = System.getProperty("os.version").orEmpty(),
            cameraIds = cameraIds(context),
        )

        val networkIds = NetworkIds(
            wifiMac = readings?.wifiMac.orEmpty(),
            bluetoothMac = readings?.bluetoothMac.orEmpty(),
            macOuiVendor = wifiVendor(build, chassis),
        )

        val telephony = telephony(context)

        return DeviceSnapshot(build, ids, display, hardware, networkIds, telephony)
    }

    /** The implicit `Default` profile for [target]: the untouched device. */
    fun realArtifact(
        target: ManagerRepository.TargetOption,
        snapshot: DeviceSnapshot,
    ): DoppeliskArtifact = DoppeliskArtifact(
        id = defaultId(target.packageName),
        label = "Default",
        target = Target(packageName = target.packageName),
        chassis = ChassisRef(id = "real", hash = ""),
        identity = Identity(
            build = snapshot.build,
            ids = snapshot.ids,
            display = snapshot.display,
            hardware = snapshot.hardware,
            networkIds = snapshot.networkIds,
            telephony = snapshot.telephony,
            provenance = mapOf("identity" to "real-device"),
        ),
        overrides = emptyList(),
    ).withNoOverrides()

    /** True when the profile pins at least one field. */
    fun isCustomized(artifact: DoppeliskArtifact): Boolean =
        artifact.overrides?.isNotEmpty() ?: true

    /** How many identity fields the profile pins. */
    fun overrideCount(artifact: DoppeliskArtifact): Int =
        artifact.overrides?.size ?: com.doppelisk.core.model.IdentityFields.ALL.size

    // ------------------------------------------------------------- internals

    /**
     * The Wi-Fi chip vendor for this device, from the chassis catalog.
     *
     * Android randomises the address an app sees, so the MAC's prefix cannot be
     * looked up reliably. The catalog already knows which vendor ships this
     * model, which is the same fact without guessing from a random prefix.
     */
    private fun wifiVendor(build: BuildIdentity, chassis: List<Chassis>): String =
        chassis.firstOrNull {
            it.build.model.equals(build.model, ignoreCase = true) &&
                it.build.manufacturer.equals(build.manufacturer, ignoreCase = true)
        }?.hardware?.wifiChipVendor.orEmpty()

    private fun serial(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 26) Build.getSerial() else Build.SERIAL
    }.getOrDefault("")

    private fun soc(): String = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
    } else {
        ""
    }

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun cameraIds(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        manager?.cameraIdList?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun display(context: Context): DisplayIdentity {
        val metrics = context.resources.displayMetrics
        return DisplayIdentity(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.densityDpi,
            refresh = refreshRate(context),
            screenInches = inches(metrics),
        )
    }

    /**
     * The panel's refresh rate.
     *
     * [DisplayMetrics] does not carry it, so it is read from the display itself;
     * the manager runs on the default display and does not need to care about
     * secondary ones.
     */
    private fun refreshRate(context: Context): Int = runCatching {
        val manager = context.getSystemService(DisplayManager::class.java)
        manager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.toInt()
    }.getOrNull()?.coerceIn(1, 360) ?: 60

    /** Physical diagonal in inches, from the panel's own pixel density. */
    private fun inches(metrics: DisplayMetrics): String {
        val xdpi = metrics.xdpi.takeIf { it > 0f } ?: return ""
        val ydpi = metrics.ydpi.takeIf { it > 0f } ?: return ""
        val diagonal = hypot(metrics.widthPixels / xdpi, metrics.heightPixels / ydpi)
        if (diagonal <= 0.0) return ""
        return String.format(Locale.US, "%.1f", diagonal)
    }

    private fun telephony(context: Context): TelephonyIdentity {
        val manager = context.getSystemService(TelephonyManager::class.java)
        val subscriptions = activeSubscriptions(context)
        val primary = subscriptions.firstOrNull { it.simSlotIndex == 0 } ?: subscriptions.firstOrNull()

        // `networkOperator*` describes the *serving* network and is empty without
        // coverage or while roaming registration is pending. The SIM's own
        // operator is still readable, and it is the identity the profile wants.
        val simOperator = runCatching { manager?.simOperator }.getOrNull().orEmpty().trim()
        val networkOperator = runCatching { manager?.networkOperator }.getOrNull().orEmpty().trim()
        val operator = simOperator.ifBlank { networkOperator }

        val mcc = subscriptionMcc(primary)
            .ifBlank { operator.take(3) }
        val mnc = subscriptionMnc(primary)
            .ifBlank { operator.drop(3) }

        val carrier = primary?.carrierName?.toString().orEmpty().trim()
            .ifBlank { runCatching { manager?.simOperatorName }.getOrNull().orEmpty().trim() }
            .ifBlank { runCatching { manager?.networkOperatorName }.getOrNull().orEmpty().trim() }

        val countryIso = primary?.countryIso.orEmpty().trim()
            .ifBlank { runCatching { manager?.simCountryIso }.getOrNull().orEmpty().trim() }
            .ifBlank { runCatching { manager?.networkCountryIso }.getOrNull().orEmpty().trim() }
            .uppercase(Locale.ROOT)

        val numbers = simNumbers(context, manager)
        val modems = runCatching { manager?.activeModemCount }.getOrNull() ?: 1
        val inserted = maxOf(subscriptions.size, numbers.count { it.isNotBlank() })
        val slots = maxOf(inserted, 1)
            .coerceAtMost(modems.coerceIn(1, TelephonyIdentity.MAX_SIM_SLOTS))

        return TelephonyIdentity(
            mcc = mcc,
            mnc = mnc,
            carrier = carrier,
            countryIso = countryIso,
            phoneNumbers = numbers,
            simSlots = slots.coerceIn(1, TelephonyIdentity.MAX_SIM_SLOTS),
        )
    }

    /**
     * The subscriptions Android reports for inserted SIMs.
     *
     * Requires `READ_PHONE_STATE`; without it the platform throws and the list
     * is empty, which is why the manager asks for the permission at startup.
     */
    private fun activeSubscriptions(context: Context): List<android.telephony.SubscriptionInfo> = runCatching {
        context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            .orEmpty()
    }.getOrDefault(emptyList())

    /** `SubscriptionInfo.mcc`, which only exists from API 29. */
    private fun subscriptionMcc(info: android.telephony.SubscriptionInfo?): String {
        if (info == null || Build.VERSION.SDK_INT < 29) return ""
        return runCatching { info.mcc.toString() }.getOrNull()
            .orEmpty()
            .trim()
            .takeIf { it.isNotEmpty() && it != "0" }
            .orEmpty()
    }

    /** `SubscriptionInfo.mnc`, padded to the two-digit form MCC/MNC strings use. */
    private fun subscriptionMnc(info: android.telephony.SubscriptionInfo?): String {
        if (info == null || Build.VERSION.SDK_INT < 29) return ""
        return runCatching { info.mnc.toString() }.getOrNull()
            .orEmpty()
            .trim()
            .takeIf { it.isNotEmpty() && it != "0" }
            ?.padStart(2, '0')
            .orEmpty()
    }

    /**
     * The number each inserted SIM reports, indexed by slot.
     *
     * `line1Number` is only the *default* SIM's number, so a dual-SIM phone
     * would hide the second one. Reading the subscription list gives one entry
     * per SIM with its own slot index. Every path needs `READ_PHONE_NUMBERS`;
     * when the permission is missing the list is simply empty and the editor
     * falls back to generated numbers.
     */
    fun simNumbers(context: Context, manager: TelephonyManager? = null): List<String> {
        val bySlot = sortedMapOf<Int, String>()
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)

        activeSubscriptions(context).forEachIndexed { index, info ->
            // Four sources, most reliable first. `SubscriptionManager
            // .getPhoneNumber` is the modern one: on Android 13+ the number
            // usually arrives over IMS and never reaches `SubscriptionInfo
            // .number`, which is why reading the field alone reports nothing on
            // a perfectly normal SIM. The per-subscription manager is the only
            // path that works for a non-default SIM on older releases.
            val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { subscriptions?.getPhoneNumber(info.subscriptionId) }.getOrNull()
            } else {
                null
            }
            val direct = runCatching { info.number }.getOrNull()
            val perSubscription = runCatching {
                manager?.createForSubscriptionId(info.subscriptionId)?.line1Number
            }.getOrNull()

            val number = modern.orEmpty()
                .ifBlank { msisdn(manager, info.subscriptionId) }
                .ifBlank { direct.orEmpty() }
                .ifBlank { perSubscription.orEmpty() }
                .trim()

            if (number.isNotBlank()) {
                val slot = info.simSlotIndex
                    .takeIf { it in 0 until TelephonyIdentity.MAX_SIM_SLOTS }
                    ?: index.takeIf { it in 0 until TelephonyIdentity.MAX_SIM_SLOTS }
                    ?: 0
                bySlot[slot] = number
            }
        }

        if (bySlot.isEmpty()) {
            val single = runCatching { manager?.line1Number }.getOrNull().orEmpty().trim()
                .ifBlank { msisdn(manager) }
            if (single.isNotBlank()) bySlot[0] = single
        }

        val last = bySlot.keys.maxOrNull() ?: return emptyList()
        return (0..last).map { bySlot[it].orEmpty() }
    }

    /**
     * `TelephonyManager.getMsisdn`, the second name the same value has.
     *
     * It is hidden from the SDK but present on every modem, and it is the path
     * apps like bKash reach for when `line1Number` is empty. Going through
     * reflection also means the manager keeps working when a ROM removes one of
     * the two getters.
     */
    private fun msisdn(manager: TelephonyManager?, subId: Int? = null): String = runCatching {
        val method = if (subId == null) {
            manager?.javaClass?.getMethod("getMsisdn")
        } else {
            manager?.javaClass?.getMethod("getMsisdn", Int::class.javaPrimitiveType!!)
        }
        if (subId == null) method?.invoke(manager) as? String
        else method?.invoke(manager, subId) as? String
    }.getOrNull().orEmpty().trim()
}
