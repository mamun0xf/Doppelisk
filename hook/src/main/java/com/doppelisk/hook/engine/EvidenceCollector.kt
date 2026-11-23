package com.doppelisk.hook.engine

import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.BoundaryEvidence
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.hook.api.BoundarySink
import kotlinx.serialization.json.Json
import java.util.Collections

/**
 * Collects boundary evidence inside a hooked process and reports it to the daemon.
 *
 * Evidence is what makes the audit trustworthy: it records the *real* value next
 * to the value the app received, so a mismatch can be spotted even when the app
 * itself hides the result. Both lists are synchronized because hooks run on
 * arbitrary threads.
 */
class EvidenceCollector(
    private val packageName: String,
    private val artifactProvider: () -> DoppeliskArtifact?,
) : BoundarySink {

    private val json: Json = JsonFormats.compact

    private val observations: MutableList<BoundaryEvidence> = Collections.synchronizedList(mutableListOf())
    private val gaps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun record(category: String, field: String, real: String?, returned: String?) {
        observations += BoundaryEvidence(category = category, field = field, real = real, returned = returned)
    }

    /** Records something the hook layer could not observe. */
    fun gap(message: String) {
        gaps += message
    }

    /** A consistent copy of everything collected so far. */
    fun snapshot(): AuditReport {
        val all = synchronized(observations) { observations.toList() }
        val missing = synchronized(gaps) { gaps.toList() }
        return AuditReport.build(
            packageName = packageName,
            doppeliskId = artifactProvider()?.id,
            observations = all,
            gaps = missing,
        )
    }

    /** Sends [snapshot] to the daemon, swallowing any failure. */
    fun report() {
        runCatching {
            DaemonClient.reportAudit(
                packageName,
                json.encodeToString(AuditReport.serializer(), snapshot()),
            )
        }
    }
}
