package com.doppelisk.hook.generic

import android.app.ActivityManager
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface

/**
 * Scales `ActivityManager.MemoryInfo` to the profile's RAM size.
 *
 * Both overloads are hooked: one fills a caller-supplied struct and one returns a
 * fresh struct, and which one an app uses is essentially random. The scaling is
 * proportional so `availMem` and the low-memory threshold stay believable.
 */
object MemoryHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!context.anyApplies(IdentityFields.BUILD_MODEL, IdentityFields.HARDWARE_SOC)) return

        val total = RootHiding.ramBytesFor(context.artifact.identity.build)
        if (total <= 0) return

        hookLazy(
            xposed,
            context,
            "ActivityManager.getMemoryInfo(MemoryInfo)",
            { ActivityManager::class.java.getMethod("getMemoryInfo", ActivityManager.MemoryInfo::class.java) },
        ) { chain ->
            val result = chain.proceed()
            (chain.arg(0) as? ActivityManager.MemoryInfo)?.let { apply(it, total, context) }
            result
        }

        hookLazy(
            xposed,
            context,
            "ActivityManager.getMemoryInfo()",
            { ActivityManager::class.java.getMethod("getMemoryInfo") },
        ) { chain ->
            val info = chain.proceed() as? ActivityManager.MemoryInfo
            if (info != null) apply(info, total, context)
            info
        }
    }

    private fun apply(info: ActivityManager.MemoryInfo, total: Long, context: HookContext) {
        val realTotal = info.totalMem
        if (realTotal <= 0 || realTotal == total) return

        val scale = total.toDouble() / realTotal
        info.totalMem = total
        info.availMem = (info.availMem * scale).toLong().coerceIn(0L, total)
        info.threshold = (info.threshold * scale).toLong().coerceIn(0L, total)
        record(context, "hardware", "MemoryInfo.totalMem", realTotal, total)
    }
}
