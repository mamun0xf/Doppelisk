package com.doppelisk.hook.generic

import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.engine.CpuIdentity
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.intArg
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface

/**
 * Makes the process report the profile's core count instead of the phone's.
 *
 * `Runtime.availableProcessors()` is the one CPU number apps can read with no
 * permission and no parsing, and it is checked against `/proc/cpuinfo` — both are
 * rewritten from the same [CpuIdentity] table so they cannot disagree.
 */
object CpuHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!context.anyApplies(
                IdentityFields.HARDWARE_SOC,
                IdentityFields.HARDWARE_CPU,
                IdentityFields.HARDWARE_KERNEL,
            )
        ) {
            return
        }

        val spec = CpuIdentity.forArtifact(context.artifact) ?: return

        hookLazy(
            xposed,
            context,
            "Runtime.availableProcessors",
            { Runtime::class.java.getMethod("availableProcessors") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "hardware", "Runtime.availableProcessors", real, spec.cores)
            spec.cores
        }
    }
}
