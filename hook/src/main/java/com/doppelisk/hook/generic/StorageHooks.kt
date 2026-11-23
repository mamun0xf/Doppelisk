package com.doppelisk.hook.generic

import android.os.StatFs
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface

/**
 * Rewrites the reported capacity of the data partition.
 *
 * `StatFs` reports both a byte count and a block count, and a consistent disguise
 * has to change both: an SDK that sees `getTotalBytes()` disagree with
 * `getBlockCountLong() * getBlockSizeLong()` knows something is wrong. Block
 * counts are recomputed from the *real* block size so the arithmetic holds.
 */
object StorageHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!context.anyApplies(IdentityFields.BUILD_MODEL, IdentityFields.HARDWARE_SOC)) return

        val total = RootHiding.storageBytesFor(context.artifact.identity.build)
        if (total <= 0) return

        hookLazy(
            xposed,
            context,
            "StatFs.getTotalBytes",
            { StatFs::class.java.getMethod("getTotalBytes") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            if (chain.thisObject is StatFs) {
                record(context, "storage", "getTotalBytes", real, total)
                total
            } else {
                real
            }
        }

        hookBlocks(xposed, context, "getBlockCountLong", total, long = true)
        hookBlocks(xposed, context, "getBlockCount", total, long = false)
    }

    /**
     * Hooks one of the block-count getters.
     *
     * [long] selects `Long` versus `Int`; the `int` getter saturates at
     * `Int.MAX_VALUE` exactly like the real one does.
     */
    private fun hookBlocks(
        xposed: XposedInterface,
        context: HookContext,
        name: String,
        total: Long,
        long: Boolean,
    ) {
        hookLazy(xposed, context, "StatFs.$name", { StatFs::class.java.getMethod(name) }) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val stat = chain.thisObject as? StatFs ?: return@hookLazy real

            val blockSize = runCatching { stat.blockSizeLong }.getOrDefault(4096L)
            val fakeBlocks = total / maxOf(blockSize, 1L)
            val returned: Any = if (long) fakeBlocks else fakeBlocks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            record(context, "storage", name, real, returned)
            returned
        }
    }
}
