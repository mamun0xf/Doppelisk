package com.doppelisk.hook.api

import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.applies
import java.util.Locale

/** Where a hook reports "the app asked for X, we answered Y". */
fun interface BoundarySink {
    fun record(category: String, field: String, real: String?, returned: String?)
}

/**
 * Everything a hook needs, without depending on Xposed or on the daemon.
 *
 * Keeping this an interface is what lets the same hook code run inside the
 * manager (for previews and fixtures) and inside a hooked app (where the
 * implementation is backed by Xposed).
 */
interface HookContext {

    /** The profile in force for this process. */
    val artifact: DoppeliskArtifact

    /** Sink for boundary evidence. */
    val boundary: BoundarySink

    /** The device's real build values, captured before any hook ran. */
    val realBuild: RealBuildSnapshot

    fun log(message: String)

    /**
     * The mode for one `(category, field)` pair.
     *
     * Defaults to [PolicyMode.FAKE]: a field the policy does not mention is still
     * spoofed, because leaving one real value in place is what makes a disguise
     * detectable. An unknown mode string also degrades to [PolicyMode.FAKE].
     */
    fun modeFor(category: String, field: String): PolicyMode {
        val rule = artifact.policy.rules.firstOrNull {
            it.category == category && it.field == field
        } ?: return PolicyMode.FAKE

        return runCatching { PolicyMode.valueOf(rule.mode.uppercase(Locale.ROOT)) }
            .getOrDefault(PolicyMode.FAKE)
    }
}

/** True when [key] (a dotted identity path) is engine-controlled for this profile. */
fun HookContext.applies(key: String): Boolean = artifact.applies(key)

/** True when any of [keys] is engine-controlled. */
fun HookContext.anyApplies(vararg keys: String): Boolean = keys.any(::applies)
