package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * One audit report for a package, written to `<root>/audit/<pkg>.json`.
 *
 * [observations] records what the hooks saw, [mismatches] records the subset
 * that disagreed with the artifact, and [gaps] lists checks that could not run
 * at all (missing API, unsupported ROM, ...). [notes] is free-form.
 */
@Serializable
data class AuditReport(
    val schema: Int = CURRENT_SCHEMA,
    val packageName: String,
    val doppeliskId: String? = null,
    val observations: List<BoundaryEvidence> = emptyList(),
    val mismatches: List<BoundaryEvidence> = emptyList(),
    val gaps: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /**
         * Assembles a report, deriving [mismatches] from [observations].
         *
         * An observation counts as a mismatch when the real value is known and
         * differs from what was returned. A null real value means "the platform
         * did not tell us", which is not a contradiction.
         */
        fun build(
            packageName: String,
            doppeliskId: String?,
            observations: List<BoundaryEvidence>,
            gaps: List<String>,
            notes: List<String> = emptyList(),
        ): AuditReport = AuditReport(
            schema = CURRENT_SCHEMA,
            packageName = packageName,
            doppeliskId = doppeliskId,
            observations = observations,
            mismatches = observations.filter { it.real != null && it.real != it.returned },
            gaps = gaps,
            notes = notes,
        )
    }
}

/**
 * A single "the app asked for X, we returned Y" record.
 *
 * [source] names the hook that produced it (`hook`, `daemon`, `fixture`), and
 * [timestampMs] defaults to the current clock so fixtures can pin it.
 */
@Serializable
data class BoundaryEvidence(
    val category: String,
    val field: String,
    val real: String?,
    val returned: String?,
    val source: String = "hook",
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * One expected-versus-actual assertion shipped in an audit fixture pack.
 *
 * [expected] and [actual] are nullable because a fixture may only assert the
 * absence or presence of a field.
 */
@Serializable
data class FixtureCase(
    val id: String,
    val category: String,
    val field: String,
    val expected: String? = null,
    val real: String? = null,
    val actual: String? = null,
    val required: Boolean = true,
    val note: String = "",
)

/** A fixture pack: a set of assertions tied to one device and app version. */
@Serializable
data class AuditFixture(
    val schema: Int = CURRENT_SCHEMA,
    val pack: String = "",
    val packVersion: String = "",
    val device: String = "",
    val os: String = "",
    val appVersion: String = "",
    val artifact: String = "",
    val cases: List<FixtureCase> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

/** A failed assertion inside a [FixtureReport]. */
@Serializable
data class FixtureFailure(
    val id: String,
    val category: String,
    val field: String,
    val expected: String? = null,
    val actual: String? = null,
    val reason: String,
)

/** Outcome of running an [AuditFixture]. */
@Serializable
data class FixtureReport(
    val ok: Boolean,
    val pack: String,
    val device: String,
    val os: String,
    val passed: Int,
    val skipped: Int,
    val failures: List<FixtureFailure> = emptyList(),
)
