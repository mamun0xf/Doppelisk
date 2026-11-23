package com.doppelisk.hook.engine

import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.json.Json

/**
 * Fetches this process's profile from the daemon, once.
 *
 * The cache is important: every hook that needs the artifact would otherwise do
 * a file round-trip, and hooks run on the app's hot paths. Only a [Loaded] state
 * is cached, so a failed lookup is retried on the next call — which is what lets
 * the hook survive the daemon starting a moment after the app.
 */
class ArtifactRepository(private val packageName: String) {

    private val json: Json = JsonFormats.wire

    @Volatile
    private var cached: ArtifactState? = null

    fun state(): ArtifactState {
        cached?.let { return it }

        val response = DaemonClient.getConfigResponse(packageName)
            ?: return ArtifactState.Unavailable

        if (!response.ok) {
            if (response.error == "virtual-default") {
                return ArtifactState.VirtualDefault.also { cached = it }
            }
            return ArtifactState.Unavailable
        }

        val raw = response.payload ?: return ArtifactState.Unavailable
        val state = runCatching {
            ArtifactState.Loaded(json.decodeFromString(DoppeliskArtifact.serializer(), raw))
        }.getOrDefault(ArtifactState.Unavailable)

        if (state is ArtifactState.Loaded) cached = state
        return state
    }

    /** The profile, or null when this process should not be hooked. */
    fun get(): DoppeliskArtifact? = (state() as? ArtifactState.Loaded)?.artifact

    /** True when the app is explicitly assigned to no profile. */
    fun isVirtualDefault(): Boolean = state() == ArtifactState.VirtualDefault

    /** Drops the cache so the next [state] call re-reads from the daemon. */
    fun invalidate() {
        cached = null
    }
}
