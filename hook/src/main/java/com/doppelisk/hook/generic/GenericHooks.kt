package com.doppelisk.hook.generic

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.applies
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.engine.IdentityProperties
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.asString
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookAll
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.intArg
import com.doppelisk.hook.generic.HookSupport.longArg
import com.doppelisk.hook.generic.HookSupport.record
import com.doppelisk.hook.generic.HookSupport.setStaticFinal
import com.doppelisk.hook.generic.HookSupport.staticStringField
import com.doppelisk.hook.generic.HookSupport.stringArg
import io.github.libxposed.api.XposedInterface
import java.util.Locale

/**
 * The hooks that every app needs, regardless of what it is.
 *
 * This is the layer that makes the disguise hold together: it rewrites
 * `android.os.Build`, the property tables, `Os.uname`, `android_id`, the serial,
 * the IMEI and the carrier strings, and it records what each of them looked like
 * before it was changed.
 *
 * The order matters. `Build` is patched first because so much else derives from
 * it, and the property table is computed from the same artifact so the two never
 * disagree.
 */
object GenericHooks {

    fun install(xposed: XposedInterface, context: HookContext, classLoader: ClassLoader?) {
        val artifact = context.artifact
        val build = artifact.identity.build
        val ids = artifact.identity.ids

        captureAndSetBuildFields(context)

        val baseband = if (context.applies(IdentityFields.BUILD_BASEBAND)) {
            RootHiding.basebandFor(build)
        } else {
            ""
        }
        val kernel = if (context.applies(IdentityFields.HARDWARE_KERNEL)) {
            RootHiding.kernelRelease(artifact.identity.hardware.kernel, build)
        } else {
            ""
        }
        val kernelVersion = if (kernel.isBlank()) "" else RootHiding.kernelVersion(build)
        val userAgent = if (
            context.anyApplies(
                IdentityFields.BUILD_MODEL,
                IdentityFields.BUILD_RELEASE,
                IdentityFields.BUILD_ID,
            )
        ) {
            "Dalvik/2.1.0 (Linux; U; Android ${build.release}; ${build.model} Build/${build.id})"
        } else {
            ""
        }

        hookBaseband(xposed, context, baseband)
        hookJavaSystemProperties(xposed, context, kernel, userAgent)
        hookOsUname(xposed, context, kernel, kernelVersion)

        // The hard-coded "this device is not rooted" properties come first so a
        // profile-derived value can override them, never the other way round.
        val props = LinkedHashMap<String, String>()
        props.putAll(RootHiding.PROPERTIES)
        props.putAll(IdentityProperties.forArtifact(artifact) { context.applies(it) })

        hookAndroidSystemProperties(xposed, context, props)
        ShellHooks.install(xposed, context, props)

        if (context.applies(IdentityFields.ID_SSAID) && ids.ssaid.isNotBlank()) {
            hookAndroidId(xposed, context, ids.ssaid)
        }

        GmsHooks.install(xposed, context, classLoader)

        if (context.applies(IdentityFields.ID_SERIAL) && ids.serial.isNotBlank()) {
            hookSerial(xposed, context, ids.serial)
        }

        val imei = ids.imei
        if (context.applies(IdentityFields.ID_IMEI) && imei != null) {
            hookTelephony(xposed, context, imei)
        }

        if (
            context.anyApplies(
                IdentityFields.TELEPHONY_MCC,
                IdentityFields.TELEPHONY_MNC,
                IdentityFields.TELEPHONY_CARRIER,
                IdentityFields.TELEPHONY_COUNTRY,
            )
        ) {
            hookTelephonyNetwork(xposed, context)
        }

        if (
            context.anyApplies(
                IdentityFields.TELEPHONY_MCC,
                IdentityFields.TELEPHONY_MNC,
                IdentityFields.TELEPHONY_CARRIER,
                IdentityFields.TELEPHONY_COUNTRY,
                IdentityFields.TELEPHONY_PHONE_1,
                IdentityFields.TELEPHONY_PHONE_2,
                IdentityFields.TELEPHONY_SIM_SLOTS,
            )
        ) {
            SimHooks.install(xposed, context)
        }

        LocationHooks.install(xposed, context)
        NetworkHooks.install(xposed, context)
        RootHidingHooks.install(xposed, context)
        DisplayHooks.install(xposed, context)
        CameraHooks.install(xposed, context)
        SensorHooks.install(xposed, context)
        StorageHooks.install(xposed, context)
        CpuHooks.install(xposed, context)
        JavaFileHooks.install(xposed, context)
        MemoryHooks.install(xposed, context)
        WebViewHooks.install(xposed, context)
        HttpHeaderHooks.install(xposed, context, classLoader)
        KeystoreHooks.install(xposed, context)
    }

    // --------------------------------------------------------------- Build

    /**
     * Rewrites the `android.os.Build` static finals and records the real values.
     *
     * These fields are `static final`, so they cannot be changed normally; the
     * reflection write is what makes the disguise visible to code that reads
     * `Build.MODEL` directly instead of calling a getter.
     */
    private fun captureAndSetBuildFields(context: HookContext) {
        val build = context.artifact.identity.build
        val ids = context.artifact.identity.ids

        val realModel = Build.MODEL
        val realBrand = Build.BRAND
        val realManufacturer = Build.MANUFACTURER
        val realDevice = Build.DEVICE
        val realProduct = Build.PRODUCT
        val realFingerprint = Build.FINGERPRINT
        val realId = Build.ID
        val realDisplay = Build.DISPLAY
        val realIncremental = Build.VERSION.INCREMENTAL

        apply(context, Build::class.java, "MANUFACTURER", build.manufacturer, IdentityFields.BUILD_MANUFACTURER)
        apply(context, Build::class.java, "BRAND", build.brand, IdentityFields.BUILD_BRAND)
        apply(context, Build::class.java, "MODEL", build.model, IdentityFields.BUILD_MODEL)
        apply(context, Build::class.java, "DEVICE", build.device, IdentityFields.BUILD_DEVICE)
        apply(context, Build::class.java, "PRODUCT", build.product, IdentityFields.BUILD_PRODUCT)
        apply(context, Build::class.java, "BOARD", build.board, IdentityFields.BUILD_BOARD)
        apply(context, Build::class.java, "HARDWARE", build.hardware, IdentityFields.BUILD_HARDWARE)
        apply(context, Build::class.java, "BOOTLOADER", build.bootloader, IdentityFields.BUILD_BOOTLOADER)
        apply(context, Build::class.java, "FINGERPRINT", build.fingerprint, IdentityFields.BUILD_FINGERPRINT)
        apply(context, Build::class.java, "ID", build.id, IdentityFields.BUILD_ID)
        apply(context, Build::class.java, "DISPLAY", build.displayId, IdentityFields.BUILD_DISPLAY_ID)
        apply(context, Build::class.java, "TYPE", build.type, IdentityFields.BUILD_TYPE)
        apply(context, Build::class.java, "TAGS", build.tags, IdentityFields.BUILD_TAGS)
        apply(context, Build::class.java, "HOST", build.host, IdentityFields.BUILD_HOST)
        apply(context, Build::class.java, "USER", build.user, IdentityFields.BUILD_USER)
        apply(context, Build.VERSION::class.java, "INCREMENTAL", build.incremental, IdentityFields.BUILD_INCREMENTAL)
        apply(context, Build.VERSION::class.java, "RELEASE", build.release, IdentityFields.BUILD_RELEASE)
        apply(context, Build.VERSION::class.java, "SECURITY_PATCH", build.securityPatch, IdentityFields.BUILD_SECURITY_PATCH)
        apply(context, Build::class.java, "SERIAL", ids.serial, IdentityFields.ID_SERIAL)

        if (context.applies(IdentityFields.BUILD_BASEBAND)) {
            val realRadio = runCatching { Build.getRadioVersion() }.getOrNull()
            val baseband = RootHiding.basebandFor(build)
            apply(context, Build::class.java, "RADIO", baseband, IdentityFields.BUILD_BASEBAND)
            if (baseband.isNotBlank()) {
                context.boundary.record("build", "RADIO", realRadio, baseband)
            }
        }

        if (context.applies(IdentityFields.HARDWARE_SOC)) {
            val realSocModel = staticStringField(Build::class.java, "SOC_MODEL")
            val realSocManufacturer = staticStringField(Build::class.java, "SOC_MANUFACTURER")
            val soc = RootHiding.socIdentity(context.artifact.identity.hardware)

            if (soc.manufacturer.isNotBlank()) {
                setStaticFinal(Build::class.java, "SOC_MANUFACTURER", soc.manufacturer)
                context.boundary.record("build", "SOC_MANUFACTURER", realSocManufacturer, soc.manufacturer)
            }
            if (soc.model.isNotBlank()) {
                setStaticFinal(Build::class.java, "SOC_MODEL", soc.model)
                context.boundary.record("build", "SOC_MODEL", realSocModel, soc.model)
            }
        }

        if (context.applies(IdentityFields.BUILD_MODEL)) {
            context.boundary.record("build", "MODEL", realModel, build.model)
        }
        if (context.applies(IdentityFields.BUILD_BRAND)) {
            context.boundary.record("build", "BRAND", realBrand, build.brand)
        }
        if (context.applies(IdentityFields.BUILD_MANUFACTURER)) {
            context.boundary.record("build", "MANUFACTURER", realManufacturer, build.manufacturer)
        }
        if (context.applies(IdentityFields.BUILD_DEVICE)) {
            context.boundary.record("build", "DEVICE", realDevice, build.device)
        }
        if (context.applies(IdentityFields.BUILD_PRODUCT)) {
            context.boundary.record("build", "PRODUCT", realProduct, build.product)
        }
        if (context.applies(IdentityFields.BUILD_FINGERPRINT)) {
            context.boundary.record("build", "FINGERPRINT", realFingerprint, build.fingerprint)
        }
        if (context.applies(IdentityFields.BUILD_ID)) {
            context.boundary.record("build", "ID", realId, build.id)
        }
        if (context.applies(IdentityFields.BUILD_DISPLAY_ID)) {
            context.boundary.record("build", "DISPLAY", realDisplay, build.displayId)
        }
        if (context.applies(IdentityFields.BUILD_INCREMENTAL)) {
            context.boundary.record("build", "INCREMENTAL", realIncremental, build.incremental)
        }
        if (context.applies(IdentityFields.ID_SERIAL)) {
            context.boundary.record("build", "SERIAL", null, ids.serial)
        }
    }

    private fun apply(context: HookContext, owner: Class<*>, name: String, value: String, key: String) {
        if (!context.applies(key) || value.isBlank()) return
        setStaticFinal(owner, name, value)
    }

    // ---------------------------------------------------------------- uname

    private fun hookOsUname(
        xposed: XposedInterface,
        context: HookContext,
        kernel: String,
        kernelVersion: String,
    ) {
        if (kernel.isBlank()) return

        hookLazy(xposed, context, "Os.uname", { android.system.Os::class.java.getMethod("uname") }) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            context.boundary.record("build", "Os.uname().release", utsField(real, "release"), kernel)
            fakeUtsname(real, kernel, kernelVersion) ?: real
        }
    }

    /**
     * Builds a `StructUtsname` with [release] and [version] replaced.
     *
     * The class is `@hide`, so it cannot be referenced at compile time and has to
     * be built reflectively. Its other fields are copied from the real struct so
     * the hostname and machine type stay truthful.
     */
    private fun fakeUtsname(real: Any?, release: String, version: String): Any? = runCatching {
        val clazz = Class.forName("android.system.StructUtsname")
        clazz.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).newInstance(
            utsField(real, "sysname") ?: "Linux",
            utsField(real, "nodename") ?: "localhost",
            release,
            version,
            utsField(real, "machine") ?: "aarch64",
        )
    }.getOrNull()

    private fun utsField(real: Any?, name: String): String? = runCatching {
        Class.forName("android.system.StructUtsname").getField(name).get(real) as? String
    }.getOrNull()

    // ------------------------------------------------------------- baseband

    private fun hookBaseband(xposed: XposedInterface, context: HookContext, baseband: String) {
        if (baseband.isBlank()) return

        hookLazy(xposed, context, "Build.getRadioVersion", { Build::class.java.getMethod("getRadioVersion") }) { chain ->
            record(context, "build", "getRadioVersion()", runCatching { chain.proceed() }.getOrNull(), baseband)
            baseband
        }
        hookLazy(
            xposed,
            context,
            "TelephonyManager.getBasebandVersion",
            { TelephonyManager::class.java.getMethod("getBasebandVersion") },
        ) { chain ->
            record(
                context,
                "build",
                "TelephonyManager.getBasebandVersion()",
                runCatching { chain.proceed() }.getOrNull(),
                baseband,
            )
            baseband
        }
    }

    // ------------------------------------------------------ Java properties

    private fun hookJavaSystemProperties(
        xposed: XposedInterface,
        context: HookContext,
        kernel: String,
        userAgent: String,
    ) {
        val property = runCatching { System::class.java.getMethod("getProperty", String::class.java) }.getOrNull()

        hook(xposed, context, "System.getProperty", property) { chain ->
            when (chain.stringArg(0)) {
                "os.version" -> if (kernel.isBlank()) {
                    chain.proceed()
                } else {
                    record(context, "build", "System.getProperty(os.version)", runCatching { chain.proceed() }.getOrNull(), kernel)
                    kernel
                }

                "http.agent" -> if (userAgent.isBlank()) {
                    chain.proceed()
                } else {
                    record(
                        context,
                        "build",
                        "System.getProperty(http.agent)",
                        runCatching { chain.proceed() }.getOrNull(),
                        userAgent,
                    )
                    userAgent
                }

                else -> chain.proceed()
            }
        }
    }

    // --------------------------------------------------- Android properties

    private fun hookAndroidSystemProperties(
        xposed: XposedInterface,
        context: HookContext,
        props: Map<String, String>,
    ) {
        val systemProperties = runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
        if (systemProperties == null) {
            context.log("SystemProperties hook skipped: class not found")
            return
        }

        for (method in runCatching {
            listOf(
                systemProperties.getMethod("get", String::class.java),
                systemProperties.getMethod("get", String::class.java, String::class.java),
            )
        }.getOrDefault(emptyList())) {
            hook(xposed, context, "SystemProperties.get", method) { chain ->
                val key = chain.stringArg(0)
                val real = runCatching { chain.proceed() }.getOrNull()
                val fake = fakeProperty(key, props)
                if (fake != null) {
                    context.boundary.record("properties", key ?: "", asString(real), fake)
                    fake
                } else {
                    real
                }
            }
        }

        hookLazy(
            xposed,
            context,
            "SystemProperties.getBoolean",
            { systemProperties.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType) },
        ) { chain ->
            val key = chain.stringArg(0)
            val fake = fakeProperty(key, props)
            if (fake != null) {
                val real = runCatching { chain.proceed() }.getOrNull()
                context.boundary.record("properties", key ?: "", real?.toString(), fake)
                fake == "1" || fake.equals("true", ignoreCase = true)
            } else {
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "SystemProperties.getInt",
            { systemProperties.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType) },
        ) { chain ->
            val key = chain.stringArg(0)
            val fake = fakeProperty(key, props)
            if (fake != null) {
                val real = runCatching { chain.proceed() }.getOrNull()
                context.boundary.record("properties", key ?: "", real?.toString(), fake)
                fake.toIntOrNull() ?: chain.intArg(1)
            } else {
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "SystemProperties.getLong",
            { systemProperties.getMethod("getLong", String::class.java, Long::class.javaPrimitiveType) },
        ) { chain ->
            val key = chain.stringArg(0)
            val fake = fakeProperty(key, props)
            if (fake != null) {
                val real = runCatching { chain.proceed() }.getOrNull()
                context.boundary.record("properties", key ?: "", real?.toString(), fake)
                fake.toLongOrNull() ?: chain.longArg(1)
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * The value a property should report, or null to leave it alone.
     *
     * A property whose *name* is itself a giveaway (a Magisk or LSPosed prefix)
     * reports empty rather than its real value, because returning the real value
     * would confirm the framework is installed.
     */
    private fun fakeProperty(key: String?, props: Map<String, String>): String? {
        if (key == null) return null
        props[key]?.let { return it }
        return if (RootHiding.isHiddenProperty(key)) "" else null
    }

    // ------------------------------------------------------------ android_id

    private fun hookAndroidId(xposed: XposedInterface, context: HookContext, ssaid: String) {
        hookLazy(
            xposed,
            context,
            "Settings.Secure.getString",
            { Settings.Secure::class.java.getMethod("getString", ContentResolver::class.java, String::class.java) },
        ) { chain ->
            val name = chain.stringArg(1)
            val real = runCatching { chain.proceed() }.getOrNull()
            if (name == "android_id") {
                context.boundary.record("ids", "ANDROID_ID", asString(real), ssaid)
                ssaid
            } else {
                real
            }
        }
    }

    // --------------------------------------------------------------- serial

    private fun hookSerial(xposed: XposedInterface, context: HookContext, serial: String) {
        hookLazy(xposed, context, "Build.getSerial", { Build::class.java.getMethod("getSerial") }) { chain ->
            record(context, "build", "getSerial()", runCatching { chain.proceed() }.getOrNull(), serial)
            serial
        }
    }

    // ------------------------------------------------------------- telephony

    private fun hookTelephony(xposed: XposedInterface, context: HookContext, imei: String) {
        val telephony = TelephonyManager::class.java

        for (name in listOf("getImei", "getDeviceId", "getMeid")) {
            hookAll(xposed, context, "TelephonyManager.$name", telephony, name) { chain ->
                record(context, "telephony", name, runCatching { chain.proceed() }.getOrNull(), imei)
                imei
            }
        }
    }

    /**
     * Rewrites the carrier fields and getters.
     *
     * The static `OPERATOR_*` constants are set as well as the getters, because
     * apps read both: an app that caches `TelephonyManager.OPERATOR_NUMERIC` at
     * startup would otherwise keep the real carrier.
     */
    private fun hookTelephonyNetwork(xposed: XposedInterface, context: HookContext) {
        val telephony = context.artifact.identity.telephony
        val country = telephony.countryIso.trim().uppercase(Locale.ROOT)
        val numeric = telephony.mcc.trim() + telephony.mnc.trim()

        val fields = listOf(
            Field("OPERATOR_ALPHA", telephony.carrier, listOf(IdentityFields.TELEPHONY_CARRIER)),
            Field("OPERATOR_ISO_COUNTRY", country, listOf(IdentityFields.TELEPHONY_COUNTRY)),
            Field("OPERATOR_NUMERIC", numeric, listOf(IdentityFields.TELEPHONY_MCC, IdentityFields.TELEPHONY_MNC)),
            Field("SIM_OPERATOR_ALPHA", telephony.carrier, listOf(IdentityFields.TELEPHONY_CARRIER)),
            Field("SIM_OPERATOR_ISO_COUNTRY", country, listOf(IdentityFields.TELEPHONY_COUNTRY)),
            Field("SIM_OPERATOR_NUMERIC", numeric, listOf(IdentityFields.TELEPHONY_MCC, IdentityFields.TELEPHONY_MNC)),
            Field("NETWORK_COUNTRY_ISO", country, listOf(IdentityFields.TELEPHONY_COUNTRY)),
            Field("SIM_COUNTRY_ISO", country, listOf(IdentityFields.TELEPHONY_COUNTRY)),
        )

        for (field in fields) {
            if (field.value.isBlank() || field.keys.none { context.applies(it) }) continue
            setStaticFinal(TelephonyManager::class.java, field.name, field.value)
        }

        val getters = mapOf(
            "getNetworkOperator" to numeric,
            "getSimOperator" to numeric,
            "getNetworkOperatorName" to telephony.carrier,
            "getSimOperatorName" to telephony.carrier,
            "getNetworkCountryIso" to country,
            "getSimCountryIso" to country,
        )

        for ((name, value) in getters) {
            if (value.isBlank()) continue
            hookAll(xposed, context, "TelephonyManager.$name", TelephonyManager::class.java, name) { chain ->
                record(context, "telephony", name, runCatching { chain.proceed() }.getOrNull(), value)
                value
            }
        }
    }
}

/** One telephony constant and the identity fields that control it. */
internal data class Field(val name: String, val value: String, val keys: List<String>)
