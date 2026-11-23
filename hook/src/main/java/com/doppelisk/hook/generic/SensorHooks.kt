package com.doppelisk.hook.generic

import android.hardware.Sensor
import android.hardware.SensorManager
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.intArg
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Renames sensors and hides the ones the profile's chassis does not have.
 *
 * Sensor *names* are surprisingly identifying: `LSM6DSO Accelerometer` pins both
 * the vendor and the model, and the set of present sensors differs between a
 * flagship and a mid-range phone. The profile therefore supplies a
 * `type → (name, vendor)` table, and sensors outside it are removed from the
 * list entirely.
 */
object SensorHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!context.anyApplies(IdentityFields.BUILD_MODEL, IdentityFields.HARDWARE_SOC)) return

        val names = RootHiding.sensorNamesFor(context.artifact.identity.build)
        if (names.isEmpty()) return

        hookGetters(xposed, context, names)
        hookList(xposed, context, names.keys)
    }

    private fun hookGetters(
        xposed: XposedInterface,
        context: HookContext,
        names: Map<Int, Pair<String, String>>,
    ) {
        for (name in listOf("getName", "getVendor", "toString")) {
            val method: Executable? = runCatching {
                if (name == "toString") {
                    Sensor::class.java.getDeclaredMethod(name)
                } else {
                    Sensor::class.java.getMethod(name)
                }
            }.getOrNull()

            hook(xposed, context, "Sensor.$name", method) { chain ->
                val sensor = chain.thisObject as? Sensor
                val fake = sensor?.let { names[it.type] }

                if (sensor == null || fake == null) {
                    chain.proceed()
                } else {
                    when (name) {
                        "getName" -> fake.first
                        "getVendor" -> fake.second
                        else -> "${fake.first} | ${fake.second} | type=${sensor.type}"
                    }
                }
            }
        }
    }

    private fun hookList(xposed: XposedInterface, context: HookContext, allowed: Set<Int>) {
        hookLazy(
            xposed,
            context,
            "SensorManager.getSensorList",
            { SensorManager::class.java.getMethod("getSensorList", Int::class.javaPrimitiveType) },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? List<*>
            val filtered = real?.filterIsInstance<Sensor>()?.filter { it.type in allowed }.orEmpty()

            context.boundary.record(
                "sensor",
                "getSensorList",
                real?.size?.toString(),
                filtered.size.toString(),
            )
            filtered
        }

        hookLazy(
            xposed,
            context,
            "SensorManager.getDefaultSensor",
            { SensorManager::class.java.getMethod("getDefaultSensor", Int::class.javaPrimitiveType) },
        ) { chain ->
            val type = chain.intArg(0)
            if (type == null || type !in allowed) null else chain.proceed()
        }
    }
}
