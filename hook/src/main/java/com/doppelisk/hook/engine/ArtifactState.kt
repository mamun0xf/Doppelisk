package com.doppelisk.hook.engine

import com.doppelisk.core.model.DoppeliskArtifact

/**
 * What the hook layer knows about this process's profile.
 *
 * The three cases matter because they lead to very different behaviour:
 *
 *  - [Loaded] — a real profile is assigned; install the hooks;
 *  - [VirtualDefault] — the app is deliberately unassigned, so leave it alone;
 *  - [Unavailable] — the daemon could not be reached or the profile is corrupt.
 *    Treated like [VirtualDefault] for safety: hooking with no profile would
 *    mean hooking with default values, which is worse than not hooking at all.
 */
sealed interface ArtifactState {

    data class Loaded(val artifact: DoppeliskArtifact) : ArtifactState

    data object VirtualDefault : ArtifactState

    data object Unavailable : ArtifactState
}
