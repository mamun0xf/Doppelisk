package com.doppelisk.hook.generic

import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.doppelisk.core.model.TelephonyIdentity
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.generic.HookSupport.hookAll
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.security.MessageDigest
import java.util.Locale

/**
 * The SIM surface: presence, slots, phone numbers and the identifiers apps use
 * to bind a session to a SIM.
 *
 * Removing a SIM normally flips `getSimState()` to `ABSENT`, empties
 * `getActiveSubscriptionInfoList()` and makes `getLine1Number()` and
 * `getSubscriberId()` return nothing. Apps that bind a login to the SIM — bKash
 * is the one that prompted this — treat that as "the user changed devices" and
 * log out. A profile that claims a carrier therefore has to claim a present SIM
 * as well, with identifiers derived from the same identity so two profiles on
 * one phone do not look like the same subscriber.
 *
 * `SubscriptionInfo` is patched too, not only `TelephonyManager`: apps that were
 * built against the modern subscription API read the number, ICCID, carrier and
 * slot from the per-SIM objects, so leaving those real would leak the physical
 * SIM even when every `TelephonyManager` getter had been rewritten.
 */
object SimHooks {

    /** The subscription id a single-SIM profile presents. Slot 2 gets 2. */
    private const val DEFAULT_FAKE_SUBSCRIPTION_ID = 1

    /** `TelephonyManager.UNKNOWN_CARRIER_ID`, the documented "not in the list". */
    private const val UNKNOWN_CARRIER_ID = -1

    fun install(xposed: XposedInterface, context: HookContext) {
        val telephony = context.artifact.identity.telephony
        val seed = context.artifact.id
        val slots = telephony.effectiveSimSlots
        val numbers = telephony.phoneNumbers

        // Without a carrier numeric an IMSI cannot be built, and a bare
        // "SIM present" claim is still useful: it is the half bKash checks
        // first. Install the presence hooks either way.
        val hasNumber = numbers.any { it.isNotBlank() }

        hookAll(
            xposed,
            context,
            "TelephonyManager.getSimState",
            TelephonyManager::class.java,
            "getSimState",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getSimState", real, TelephonyManager.SIM_STATE_READY)
            TelephonyManager.SIM_STATE_READY
        }

        hookAll(
            xposed,
            context,
            "TelephonyManager.hasIccCard",
            TelephonyManager::class.java,
            "hasIccCard",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "hasIccCard", real, true)
            true
        }

        // A SIM whose operator is the profile's home carrier is registered on
        // that carrier's network, so roaming cannot be true at the same time.
        // bKash and other anti-fraud SDKs record this next to the carrier.
        hookAll(
            xposed,
            context,
            "TelephonyManager.isNetworkRoaming",
            TelephonyManager::class.java,
            "isNetworkRoaming",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            if (real == false) {
                real
            } else {
                record(context, "telephony", "isNetworkRoaming", real, false)
                false
            }
        }

        for (method in listOf("getPhoneCount", "getActiveModemCount")) {
            hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, slots)
                slots
            }
        }

        // Which subscription this `TelephonyManager` speaks for. A phone with
        // no SIM answers `INVALID_SUBSCRIPTION_ID`, which a reading SDK takes as
        // "there is no SIM" before it ever asks anything else.
        hookAll(
            xposed,
            context,
            "TelephonyManager.getSubscriptionId",
            TelephonyManager::class.java,
            "getSubscriptionId",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val fake = if (slots > 0) {
                DEFAULT_FAKE_SUBSCRIPTION_ID
            } else {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            if (real == fake) {
                real
            } else {
                record(context, "telephony", "getSubscriptionId", real, fake)
                fake
            }
        }

        // Every phone-number path an app can take. `getMsisdn` and
        // `getLine1NumberForSubscriber` are hidden from the SDK but still exist
        // on the framework class, which is why the lookup is by name.
        if (hasNumber) {
            for (method in listOf("getLine1Number", "getLine1NumberForSubscriber", "getMsisdn")) {
                hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                    val slot = subscriptionSlot(chain.arg(0), slots)
                    val fake = numbers.getOrNull(slot).orEmpty()
                        .ifBlank { numbers.firstOrNull().orEmpty() }
                    if (fake.isBlank()) {
                        runCatching { chain.proceed() }.getOrNull()
                    } else {
                        val real = runCatching { chain.proceed() }.getOrNull()
                        record(context, "telephony", method, real, fake)
                        fake
                    }
                }
            }
        }

        val subscriber = imsi(telephony, seed, 0)
        if (subscriber.isNotBlank()) {
            hookAll(
                xposed,
                context,
                "TelephonyManager.getSubscriberId",
                TelephonyManager::class.java,
                "getSubscriberId",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0), slots)
                val fake = imsi(telephony, seed, slot)
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getSubscriberId", real, fake)
                    fake
                }
            }
        }

        val iccid = iccid(telephony, seed, 0)
        if (iccid.isNotBlank()) {
            hookAll(
                xposed,
                context,
                "TelephonyManager.getSimSerialNumber",
                TelephonyManager::class.java,
                "getSimSerialNumber",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0), slots)
                val fake = iccid(telephony, seed, slot)
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getSimSerialNumber", real, fake)
                    fake
                }
            }
        }

        // A SIM that reports READY while the network type says "unknown" is
        // still suspicious. LTE is the safest claim for a modern profile.
        // `hookAll` also covers the hidden per-subscription `(int)` overloads.
        for (method in listOf("getNetworkType", "getDataNetworkType", "getVoiceNetworkType")) {
            hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, TelephonyManager.NETWORK_TYPE_LTE)
                TelephonyManager.NETWORK_TYPE_LTE
            }
        }

        hookSubscriptionManager(xposed, context, telephony, seed, slots, numbers, hasNumber)
        hookSubscriptionInfo(xposed, context, telephony, seed, slots, numbers, hasNumber)
        hookServiceState(
            xposed,
            context,
            telephony.carrier,
            telephony.mcc.trim() + telephony.mnc.trim(),
        )
        hookCarrierIds(xposed, context, telephony)
    }

    /**
     * The `ServiceState` view of the same carrier.
     *
     * `TelephonyManager.getNetworkOperator*` is only one way to read the
     * serving operator. Anti-fraud SDKs and `TelephonyCallback` clients hold a
     * `ServiceState` and read the alpha and numeric strings from it, so leaving
     * those real while the getters are rewritten makes the two disagree.
     */
    private fun hookServiceState(
        xposed: XposedInterface,
        context: HookContext,
        carrier: String,
        numeric: String,
    ) {
        val values = buildList {
            if (carrier.isNotBlank()) {
                add("getOperatorAlphaLong" to carrier)
                add("getOperatorAlphaShort" to carrier)
            }
            if (numeric.isNotBlank()) add("getOperatorNumeric" to numeric)
        }

        for ((method, fake) in values) {
            hookAll(xposed, context, "ServiceState.$method", ServiceState::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, fake)
                fake
            }
        }

        // A SIM registered on its own home network is never roaming.
        hookAll(xposed, context, "ServiceState.getRoaming", ServiceState::class.java, "getRoaming") { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            if (real == false) {
                real
            } else {
                record(context, "telephony", "getRoaming", real, false)
                false
            }
        }
    }

    /**
     * Hides the framework carrier database ids and the SIM's GID1.
     *
     * The ids are rows in the ROM's carrier list, so a profile cannot name one
     * without copying the whole database. `-1` is the documented "unknown"
     * value, and the name getter still answers with the profile's carrier, which
     * is how an unrecognised but valid SIM looks.
     */
    private fun hookCarrierIds(xposed: XposedInterface, context: HookContext, telephony: TelephonyIdentity) {
        for (method in listOf("getSimCarrierId", "getSimSpecificCarrierId")) {
            hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                val fake = UNKNOWN_CARRIER_ID
                if (real == fake) {
                    real
                } else {
                    record(context, "telephony", method, real, fake)
                    fake
                }
            }
        }

        val carrier = telephony.carrier.trim()
        if (carrier.isNotBlank()) {
            for (method in listOf("getSimCarrierIdName", "getSimSpecificCarrierIdName")) {
                hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", method, real, carrier)
                    carrier
                }
            }
        }

        // GID1 is group data written by the operator, not modelled by a profile.
        hookAll(xposed, context, "TelephonyManager.getGroupIdLevel1", TelephonyManager::class.java, "getGroupIdLevel1") { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real.isNullOrBlank()) {
                real
            } else {
                record(context, "telephony", "getGroupIdLevel1", real, null)
                null
            }
        }
    }

    /**
     * The `SubscriptionManager` surface: counts, default ids, numbers and the
     * length of the per-SIM list.
     *
     * Default ids all answer with [DEFAULT_FAKE_SUBSCRIPTION_ID] so they agree
     * with `SubscriptionInfo.getSubscriptionId()` and with the ids the list
     * hook hands out.
     */
    private fun hookSubscriptionManager(
        xposed: XposedInterface,
        context: HookContext,
        telephony: TelephonyIdentity,
        seed: String,
        slots: Int,
        numbers: List<String>,
        hasNumber: Boolean,
    ) {
        hookLazy(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfoCount",
            { SubscriptionManager::class.java.getMethod("getActiveSubscriptionInfoCount") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getActiveSubscriptionInfoCount", real, slots)
            slots
        }

        hookLazy(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfoCountMax",
            { SubscriptionManager::class.java.getMethod("getActiveSubscriptionInfoCountMax") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getActiveSubscriptionInfoCountMax", real, slots)
            slots
        }

        for (method in listOf(
            "getDefaultSubscriptionId",
            "getDefaultDataSubscriptionId",
            "getDefaultSmsSubscriptionId",
            "getDefaultVoiceSubscriptionId",
        )) {
            hookLazy(
                xposed,
                context,
                "SubscriptionManager.$method",
                { SubscriptionManager::class.java.getMethod(method) },
            ) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                val fake = if (slots > 0) {
                    DEFAULT_FAKE_SUBSCRIPTION_ID
                } else {
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                }
                record(context, "telephony", method, real, fake)
                fake
            }
        }

        // API 33+ prefers this over `SubscriptionInfo.getNumber()`: the number
        // usually arrives over IMS and never reaches the profile object.
        if (hasNumber) {
            hookAll(
                xposed,
                context,
                "SubscriptionManager.getPhoneNumber",
                SubscriptionManager::class.java,
                "getPhoneNumber",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0), slots)
                val fake = numbers.getOrNull(slot).orEmpty()
                    .ifBlank { numbers.firstOrNull().orEmpty() }
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "SubscriptionManager.getPhoneNumber", real, fake)
                    fake
                }
            }
        }

        hookSubscriptionLists(xposed, context, telephony, seed, slots, numbers)

        // The id-based accessors are the third way into the same table: an app
        // that already has an id from an earlier launch asks for its slot and
        // for the subscription itself. Ids outside the profile's range are
        // hidden rather than passed through, because they name the real SIM.
        hookAll(
            xposed,
            context,
            "SubscriptionManager.getSlotIndex",
            SubscriptionManager::class.java,
            "getSlotIndex",
        ) { chain ->
            val id = (chain.arg(0) as? Number)?.toInt() ?: UNKNOWN_CARRIER_ID
            val fake = id - DEFAULT_FAKE_SUBSCRIPTION_ID
            val real = runCatching { chain.proceed() }.getOrNull()

            if (fake in 0 until slots) {
                if (real == fake) real else {
                    record(context, "telephony", "getSlotIndex", real, fake)
                    fake
                }
            } else {
                real
            }
        }

        hookAll(
            xposed,
            context,
            "SubscriptionManager.getSubscriptionIds",
            SubscriptionManager::class.java,
            "getSubscriptionIds",
        ) { chain ->
            val slot = (chain.arg(0) as? Number)?.toInt() ?: 0
            val real = runCatching { chain.proceed() }.getOrNull()
            if (slot !in 0 until slots) {
                if (real != null) record(context, "telephony", "getSubscriptionIds", real, IntArray(0))
                return@hookAll IntArray(0)
            }
            val fake = intArrayOf(DEFAULT_FAKE_SUBSCRIPTION_ID + slot)
            record(context, "telephony", "getSubscriptionIds", real, fake)
            fake
        }

        for (method in listOf("getActiveSubscriptionIdList", "getCompleteActiveSubscriptionIdList")) {
            hookAll(
                xposed,
                context,
                "SubscriptionManager.$method",
                SubscriptionManager::class.java,
                method,
            ) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                val fake = IntArray(slots) { DEFAULT_FAKE_SUBSCRIPTION_ID + it }
                fun same() = (real as? IntArray)?.contentEquals(fake) == true
                if (same()) real else {
                    record(context, "telephony", method, real, fake)
                    fake
                }
            }
        }

        hookAll(
            xposed,
            context,
            "SubscriptionManager.isActiveSubscriptionId",
            SubscriptionManager::class.java,
            "isActiveSubscriptionId",
        ) { chain ->
            val id = (chain.arg(0) as? Number)?.toInt() ?: UNKNOWN_CARRIER_ID
            val fake = id in DEFAULT_FAKE_SUBSCRIPTION_ID until DEFAULT_FAKE_SUBSCRIPTION_ID + slots
            val real = runCatching { chain.proceed() }.getOrNull()
            if (real == fake) {
                real
            } else {
                record(context, "telephony", "isActiveSubscriptionId", real, fake)
                fake
            }
        }

        hookAll(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfo",
            SubscriptionManager::class.java,
            "getActiveSubscriptionInfo",
        ) { chain ->
            val id = (chain.arg(0) as? Number)?.toInt() ?: UNKNOWN_CARRIER_ID
            val slot = id - DEFAULT_FAKE_SUBSCRIPTION_ID
            if (slot !in 0 until slots) {
                val real = runCatching { chain.proceed() }.getOrNull()
                if (real != null) record(context, "telephony", "getActiveSubscriptionInfo", real, null)
                return@hookAll null
            }

            val list = runCatching {
                (chain.thisObject as? SubscriptionManager)?.activeSubscriptionInfoList
            }.getOrNull().orEmpty()
            list.firstOrNull { simSlot(it, slots) == slot }
        }

        // `isValidSubscriptionId` is the cheap gate an SDK puts in front of every
        // other call: an id from a previous launch that fails it is discarded
        // before the list is ever asked for.
        hookAll(
            xposed,
            context,
            "SubscriptionManager.isValidSubscriptionId",
            SubscriptionManager::class.java,
            "isValidSubscriptionId",
        ) { chain ->
            val id = (chain.arg(0) as? Number)?.toInt() ?: UNKNOWN_CARRIER_ID
            val fake = id in DEFAULT_FAKE_SUBSCRIPTION_ID until DEFAULT_FAKE_SUBSCRIPTION_ID + slots
            val real = runCatching { chain.proceed() }.getOrNull()
            if (real == fake) {
                real
            } else {
                record(context, "telephony", "isValidSubscriptionId", real, fake)
                fake
            }
        }

        // `getSubscriptionId(int slotIndex)`: the slot-to-id mapping, the reverse
        // of `getSlotIndex`. Without it, `getSubscriptionId(0)` on a phone with
        // no SIM returns `INVALID_SUBSCRIPTION_ID` even though `getSlotIndex(1)`
        // already claims slot 0.
        hookAll(
            xposed,
            context,
            "SubscriptionManager.getSubscriptionId",
            SubscriptionManager::class.java,
            "getSubscriptionId",
        ) { chain ->
            val slot = (chain.arg(0) as? Number)?.toInt() ?: 0
            val fake = if (slot in 0 until slots) {
                DEFAULT_FAKE_SUBSCRIPTION_ID + slot
            } else {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            val real = runCatching { chain.proceed() }.getOrNull()
            if (real == fake) {
                real
            } else {
                record(context, "telephony", "getSubscriptionId", real, fake)
                fake
            }
        }

        // API 34 moved the default subscription from an id to an object. These
        // three getters are what a modern SDK uses when it wants the "main" SIM.
        for (method in listOf(
            "getDefaultDataSubscriptionInfo",
            "getDefaultVoiceSubscriptionInfo",
        )) {
            hookAll(
                xposed,
                context,
                "SubscriptionManager.$method",
                SubscriptionManager::class.java,
                method,
            ) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                if (real != null) return@hookAll real
                val fake = runCatching {
                    (chain.thisObject as? SubscriptionManager)?.activeSubscriptionInfoList
                }.getOrNull().orEmpty().firstOrNull()
                if (fake != null) record(context, "telephony", method, null, fake)
                fake
            }
        }
    }

    /**
     * Reshapes the subscription lists to the profile's slot count.
     *
     * Trimming is not enough. bKash — the app these hooks were written for —
     * reads the subscription id of each slot from this list and reports both to
     * its backend, so a profile that claims a SIM has to return entries even
     * when the phone has no SIM at all. Missing slots are filled by copying a
     * real `SubscriptionInfo` when there is one, and by building a fresh one
     * when there is not; the two paths keep the entry coherent with
     * `getActiveSubscriptionInfoCount()` and the `TelephonyManager` getters.
     */
    private fun hookSubscriptionLists(
        xposed: XposedInterface,
        context: HookContext,
        telephony: TelephonyIdentity,
        seed: String,
        slots: Int,
        numbers: List<String>,
    ) {
        for (method in listOf(
            "getActiveSubscriptionInfoList",
            "getAvailableSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getCompleteActiveSubscriptionInfoList",
        )) {
            hookAll(xposed, context, "SubscriptionManager.$method", SubscriptionManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                val list = (real as? List<*>)?.filterIsInstance<SubscriptionInfo>() ?: return@hookAll real
                val fake = synthesized(list, telephony, seed, slots, numbers)
                if (fake.size != list.size) record(context, "telephony", "$method.size", list.size, fake.size)
                fake
            }
        }

        // The single-slot lookup is a separate JNI-free path: an app that asks
        // for slot 1 directly (bKash does) never calls the list method and would
        // otherwise get null on a phone with one SIM.
        hookAll(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex",
            SubscriptionManager::class.java,
            "getActiveSubscriptionInfoForSimSlotIndex",
        ) { chain ->
            val slot = (chain.arg(0) as? Number)?.toInt() ?: 0
            val real = runCatching { chain.proceed() }.getOrNull()

            if (slot !in 0 until slots) {
                if (real != null) {
                    record(context, "telephony", "getActiveSubscriptionInfoForSimSlotIndex", real, null)
                }
                return@hookAll null
            }
            if (real != null) return@hookAll real

            // Reuse the synthesized list so this object agrees with the list
            // method: same slot, same id, same carrier.
            val list = runCatching {
                (chain.thisObject as? SubscriptionManager)?.activeSubscriptionInfoList
            }.getOrNull().orEmpty()
            val fake = list.firstOrNull { simSlot(it, slots) == slot }
                ?: list.firstOrNull()?.let { copyForSlot(it, slot) }
                ?: fabricated(telephony, seed, slot, numbers.getOrNull(slot).orEmpty())
            if (fake != null) {
                record(context, "telephony", "getActiveSubscriptionInfoForSimSlotIndex", null, fake)
            }
            fake
        }
    }

    /** [list] with exactly one entry per profile slot, real entries kept as-is. */
    private fun synthesized(
        list: List<SubscriptionInfo>,
        telephony: TelephonyIdentity,
        seed: String,
        slots: Int,
        numbers: List<String>,
    ): List<SubscriptionInfo> {
        if (slots <= 0) return emptyList()

        val template = list.firstOrNull()
        val bySlot = list.groupBy { simSlot(it, slots) }
        return (0 until slots).mapNotNull { slot ->
            bySlot[slot]?.first()
                ?: template?.let { copyForSlot(it, slot) }
                ?: fabricated(telephony, seed, slot, numbers.getOrNull(slot).orEmpty())
        }
    }

    /**
     * A `SubscriptionInfo` for [slot] built from the profile alone.
     *
     * This is the no-SIM case: nothing real exists to copy, so the object has
     * to be constructed. `SubscriptionInfo` has no public constructor, so three
     * routes are tried in order of stability: the hidden `Builder` that Android
     * 15 added, the classic hidden constructor, and finally `Unsafe` with the
     * two fields the other hooks read. Whichever works, the getter hooks decide
     * the values the app actually sees.
     */
    private fun fabricated(
        telephony: TelephonyIdentity,
        seed: String,
        slot: Int,
        number: String,
    ): SubscriptionInfo? {
        val id = DEFAULT_FAKE_SUBSCRIPTION_ID + slot
        val icc = iccid(telephony, seed, slot)
        val carrier = telephony.carrier.trim()
        val mcc = telephony.mcc.filter(Char::isDigit)
        val mnc = telephony.mnc.filter(Char::isDigit)
        val country = telephony.countryIso.trim().uppercase(Locale.ROOT)

        return buildWithBuilder(id, slot, icc, number, carrier, mcc, mnc, country)
            ?: buildWithConstructor(id, slot, icc, number, carrier, mcc, mnc, country)
            ?: buildWithUnsafe(id, slot, icc, number, carrier, mcc, mnc, country)
    }

    /** The `SubscriptionInfo$Builder` route, available from Android 15. */
    private fun buildWithBuilder(
        id: Int,
        slot: Int,
        icc: String,
        number: String,
        carrier: String,
        mcc: String,
        mnc: String,
        country: String,
    ): SubscriptionInfo? = runCatching {
        val builderClass = Class.forName("android.telephony.SubscriptionInfo\$Builder")
        val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        fun set(name: String, type: Class<*>, value: Any?) {
            runCatching { builderClass.getMethod(name, type).invoke(builder, value) }
        }

        set("setId", Int::class.javaPrimitiveType!!, id)
        set("setSimSlotIndex", Int::class.javaPrimitiveType!!, slot)
        set("setPortIndex", Int::class.javaPrimitiveType!!, 0)
        set("setEmbedded", Boolean::class.javaPrimitiveType!!, false)
        if (icc.isNotBlank()) set("setIccId", String::class.java, icc)
        if (number.isNotBlank()) set("setNumber", String::class.java, number)
        if (carrier.isNotBlank()) {
            set("setCarrierName", CharSequence::class.java, carrier)
            set("setDisplayName", CharSequence::class.java, carrier)
        }
        if (mcc.isNotBlank()) set("setMcc", String::class.java, mcc)
        if (mnc.isNotBlank()) set("setMnc", String::class.java, mnc)
        if (country.isNotBlank()) set("setCountryIso", String::class.java, country)

        builderClass.getMethod("build").invoke(builder) as? SubscriptionInfo
    }.getOrNull()

    /**
     * The pre-Android-15 constructor.
     *
     * `(int id, String iccId, int simSlotIndex, CharSequence displayName,
     * CharSequence carrierName, int nameSource, int iconTint, String number,
     * int roaming, Bitmap icon, String mcc, String mnc, String countryIso,
     * boolean embedded, UiccAccessRule[] accessRules, String groupId)` has been
     * stable since Android 5.1, which is why it is matched by exact type names
     * rather than by parameter count.
     */
    private fun buildWithConstructor(
        id: Int,
        slot: Int,
        icc: String,
        number: String,
        carrier: String,
        mcc: String,
        mnc: String,
        country: String,
    ): SubscriptionInfo? = runCatching {
        val signature = listOf(
            "int",
            "java.lang.String",
            "int",
            "java.lang.CharSequence",
            "java.lang.CharSequence",
            "int",
            "int",
            "java.lang.String",
            "int",
            "android.graphics.Bitmap",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String",
            "boolean",
            "[Landroid.telephony.UiccAccessRule;",
            "java.lang.String",
        )
        val constructor = SubscriptionInfo::class.java.declaredConstructors
            .firstOrNull { it.parameterTypes.map(Class<*>::getName) == signature }
            ?: return null

        constructor.isAccessible = true
        constructor.newInstance(
            id,
            icc,
            slot,
            carrier,
            carrier,
            0,
            0,
            number,
            0,
            null,
            mcc,
            mnc,
            country,
            false,
            null,
            null,
        ) as SubscriptionInfo
    }.getOrNull()

    /**
     * Last resort: an `Unsafe`-allocated instance filled from the profile.
     *
     * The getter hooks overwrite most of these values anyway, but populating the
     * fields keeps a caller that reads them directly (or through a getter this
     * module does not know) from seeing a null where a SIM should be.
     */
    private fun buildWithUnsafe(
        id: Int,
        slot: Int,
        icc: String,
        number: String,
        carrier: String,
        mcc: String,
        mnc: String,
        country: String,
    ): SubscriptionInfo? = runCatching {
        val instance = allocateSubscriptionInfo() ?: return null

        setField(instance, "mId", id)
        setField(instance, "mSimSlotIndex", slot)
        setField(instance, "mPortIndex", 0)
        setField(instance, "mIsEmbedded", false)
        setField(instance, "mAreUiccApplicationsEnabled", true)
        setField(instance, "mIconTint", 0)
        setField(instance, "mDisplayNameSource", 0)
        setField(instance, "mDataRoaming", 0)
        if (icc.isNotBlank()) setField(instance, "mIccId", icc)
        if (number.isNotBlank()) setField(instance, "mNumber", number)
        if (carrier.isNotBlank()) {
            setField(instance, "mCarrierName", carrier)
            setField(instance, "mDisplayName", carrier)
        }
        if (mcc.isNotBlank()) setField(instance, "mMcc", mcc)
        if (mnc.isNotBlank()) setField(instance, "mMnc", mnc)
        if (country.isNotBlank()) setField(instance, "mCountryIso", country)
        instance
    }.getOrNull()

    /**
     * A copy of [template] that presents itself as [slot].
     *
     * `SubscriptionInfo` is `Parcelable`, not `Cloneable`, so `Object.clone()`
     * throws `CloneNotSupportedException` and cannot be used here. The copy is
     * instead allocated with `Unsafe` and filled field by field, which keeps
     * every value the framework wrote (icon, group, access rules) while letting
     * this module rewrite the two the other hooks do not already compute:
     * `mSimSlotIndex` feeds the hooked slot getter and `mId` keeps a caller that
     * reads the field directly from repeating the real id.
     *
     * The hidden `Builder` copies a `SubscriptionInfo` as well, but only from
     * Android 15, so it is the fallback rather than the main route.
     */
    private fun copyForSlot(template: SubscriptionInfo, slot: Int): SubscriptionInfo? =
        copyWithUnsafe(template, slot)
            ?: copyWithBuilder(template, slot)

    /** `Unsafe.allocateInstance` plus a field-by-field copy of [template]. */
    private fun copyWithUnsafe(template: SubscriptionInfo, slot: Int): SubscriptionInfo? = runCatching {
        val clone = allocateSubscriptionInfo() ?: return null
        copyFields(template, clone)
        setField(clone, "mSimSlotIndex", slot)
        setField(clone, "mId", DEFAULT_FAKE_SUBSCRIPTION_ID + slot)
        clone
    }.getOrNull()

    /** The Android 15+ `Builder(SubscriptionInfo)` copy constructor. */
    private fun copyWithBuilder(template: SubscriptionInfo, slot: Int): SubscriptionInfo? = runCatching {
        val builderClass = Class.forName("android.telephony.SubscriptionInfo\$Builder")
        val builder = builderClass
            .getDeclaredConstructor(SubscriptionInfo::class.java)
            .apply { isAccessible = true }
            .newInstance(template)
        val clone = builderClass.getMethod("build").invoke(builder) as? SubscriptionInfo
        if (clone != null) {
            setField(clone, "mSimSlotIndex", slot)
            setField(clone, "mId", DEFAULT_FAKE_SUBSCRIPTION_ID + slot)
        }
        clone
    }.getOrNull()

    /** Every instance field of [from] written into [to], both of the same class. */
    private fun copyFields(from: SubscriptionInfo, to: SubscriptionInfo) {
        var type: Class<*>? = SubscriptionInfo::class.java
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                runCatching {
                    field.isAccessible = true
                    field.set(to, field.get(from))
                }
            }
            type = type.superclass
        }
    }

    /** A zeroed `SubscriptionInfo`, the only route when no real one exists. */
    private fun allocateSubscriptionInfo(): SubscriptionInfo? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SubscriptionInfo::class.java) as SubscriptionInfo
    }.getOrNull()

    /** Writes a private field found anywhere in [target]'s class hierarchy. */
    private fun setField(target: Any, name: String, value: Any) {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        error("no $name field on ${target.javaClass.name}")
    }

    /**
     * Makes each `SubscriptionInfo` describe the profile's SIM for its slot.
     *
     * The slot comes from the object itself (`getSimSlotIndex`), not from a
     * subscription id, because ids are allocated by the framework and are not
     * slots. `getSimSlotIndex` is hooked first so every later read sees a slot
     * inside the profile's range, and `getSubscriptionId` then reports the
     * deterministic id derived from that slot.
     */
    private fun hookSubscriptionInfo(
        xposed: XposedInterface,
        context: HookContext,
        telephony: TelephonyIdentity,
        seed: String,
        slots: Int,
        numbers: List<String>,
        hasNumber: Boolean,
    ) {
        hookAll(
            xposed,
            context,
            "SubscriptionInfo.getSimSlotIndex",
            SubscriptionInfo::class.java,
            "getSimSlotIndex",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val raw = (real as? Number)?.toInt() ?: -1
            val fake = clampSlot(raw, slots)
            if (raw == fake) {
                real
            } else {
                record(context, "telephony", "getSimSlotIndex", real, fake)
                fake
            }
        }

        // `getNumber()` only takes the profile's number when that slot has one;
        // a real number is better than inventing one the profile never claimed.
        if (hasNumber) {
            hookAll(
                xposed,
                context,
                "SubscriptionInfo.getNumber",
                SubscriptionInfo::class.java,
                "getNumber",
            ) { chain ->
                val raw = rawSimSlot(chain.thisObject)
                val slot = if (raw in 0 until slots) raw else 0
                val fake = numbers.getOrNull(slot).orEmpty()
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getNumber", real, fake)
                    fake
                }
            }
        }

        val carrier = telephony.carrier.trim()
        val country = telephony.countryIso.trim().uppercase(Locale.ROOT)
        val mcc = telephony.mcc.filter(Char::isDigit)
        val mnc = telephony.mnc.filter(Char::isDigit)

        // String getters that share one value for every slot. Blank values are
        // not installed as fakes: the real one is then returned untouched.
        val strings = mutableListOf<Pair<String, String>>()
        if (carrier.isNotBlank()) {
            strings += "getCarrierName" to carrier
            strings += "getDisplayName" to carrier
        }
        if (country.isNotBlank()) {
            strings += "getCountryIso" to country
        }
        if (mcc.isNotBlank()) {
            strings += "getMccString" to mcc
        }
        if (mnc.isNotBlank()) {
            strings += "getMncString" to mnc
        }

        for ((method, fake) in strings) {
            hookAll(xposed, context, "SubscriptionInfo.$method", SubscriptionInfo::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, fake)
                fake
            }
        }

        // The ICCID is the one string value that differs per slot.
        val iccids = (0 until slots).map { slot -> iccid(telephony, seed, slot) }
        if (iccids.any { it.isNotBlank() }) {
            hookAll(
                xposed,
                context,
                "SubscriptionInfo.getIccId",
                SubscriptionInfo::class.java,
                "getIccId",
            ) { chain ->
                val fake = iccids.getOrNull(simSlot(chain.thisObject, slots)).orEmpty()
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getIccId", real, fake)
                    fake
                }
            }
        }

        // The int form does not carry leading zeros, which is why the string
        // form above keeps the profile's digits verbatim.
        val numerics = listOfNotNull(
            mcc.toIntOrNull()?.let { "getMcc" to it },
            mnc.toIntOrNull()?.let { "getMnc" to it },
        )
        for ((method, value) in numerics) {
            hookAll(xposed, context, "SubscriptionInfo.$method", SubscriptionInfo::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                // Very old releases returned a String from these methods.
                val fake: Any = if (real is String) value.toString() else value
                record(context, "telephony", method, real, fake)
                fake
            }
        }

        hookAll(
            xposed,
            context,
            "SubscriptionInfo.getSubscriptionId",
            SubscriptionInfo::class.java,
            "getSubscriptionId",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val fake = simSlot(chain.thisObject, slots) + DEFAULT_FAKE_SUBSCRIPTION_ID
            record(context, "telephony", "getSubscriptionId", real, fake)
            fake
        }
    }

    /**
     * Maps an overload's subscription-id argument to a slot index.
     *
     * Subscription ids are not slots. The framework allocates them and they are
     * usually 1 and 2, but reinserting a SIM can move them to 3 and 4. The real
     * mapping is asked first; the `subId % slots` fallback only runs when that
     * lookup is unavailable (no READ_PHONE_STATE). Ids in `1..slots` are checked
     * as `id - 1` first because those are exactly the ids this module hands out
     * from `SubscriptionInfo.getSubscriptionId()`.
     */
    private fun subscriptionSlot(argument: Any?, slots: Int): Int {
        val id = (argument as? Number)?.toInt() ?: return 0
        if (slots <= 1) return 0

        val index = runCatching { SubscriptionManager.getSlotIndex(id) }.getOrNull()
        if (index != null && index in 0 until slots) return index

        if (id in 1..slots) return id - 1
        return (id % slots).coerceIn(0, slots - 1)
    }

    /**
     * The object's own slot, read through `getSimSlotIndex()`, or `-1` when it
     * cannot be read. That hook clamps the value, so the result is normally
     * already inside the profile's range.
     */
    private fun rawSimSlot(receiver: Any?): Int {
        if (receiver == null) return -1
        return runCatching {
            (SubscriptionInfo::class.java.getMethod("getSimSlotIndex").invoke(receiver) as? Number)?.toInt()
        }.getOrNull() ?: -1
    }

    /** [rawSimSlot] with out-of-range values pushed into `0 until slots`. */
    private fun simSlot(receiver: Any?, slots: Int): Int = clampSlot(rawSimSlot(receiver), slots)

    /** Clamps [slot] into `0 until slots`; a single-SIM profile always says 0. */
    private fun clampSlot(slot: Int, slots: Int): Int {
        if (slots <= 1) return 0
        return slot.coerceIn(0, slots - 1)
    }

    /**
     * A 15-digit IMSI for [slot], built from the profile's MCC/MNC.
     *
     * `MCC + MNC + subscriber` is the E.212 layout. The subscriber half is
     * derived from the profile id, so it is stable across launches but differs
     * between profiles and slots.
     */
    private fun imsi(telephony: TelephonyIdentity, seed: String, slot: Int): String {
        val mcc = telephony.mcc.filter(Char::isDigit)
        val mnc = telephony.mnc.filter(Char::isDigit)
        if (mcc.length != 3 || mnc.length !in 2..3) return ""
        val length = 15 - mcc.length - mnc.length
        return mcc + mnc + digits(seed, "imsi", slot, length)
    }

    /**
     * An ICCID for [slot]: `89` + MCC + derived digits + Luhn check digit.
     *
     * The leading `89` is the telecom issuer identifier every SIM uses. The
     * rest is not a real issuer code, but the length and check digit are
     * correct, which is what format validators look at.
     */
    private fun iccid(telephony: TelephonyIdentity, seed: String, slot: Int): String {
        val mcc = telephony.mcc.filter(Char::isDigit)
        if (mcc.length != 3) return ""
        val body = "89" + mcc + digits(seed, "iccid", slot, 13)
        return body + luhn(body)
    }

    private fun digits(seed: String, salt: String, slot: Int, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$seed|$salt|$slot".toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(count)
        var index = 0
        while (builder.length < count) {
            val byte = digest[index % digest.size].toInt() and 0xFF
            builder.append(byte % 10)
            index++
        }
        return builder.toString()
    }

    private fun luhn(body: String): Int {
        var sum = 0
        var double = true
        for (index in body.indices.reversed()) {
            var digit = body[index] - '0'
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return (10 - sum % 10) % 10
    }
}
