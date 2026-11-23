package com.doppelisk.core.audit

import com.doppelisk.core.model.AuditFixture
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.BoundaryEvidence
import com.doppelisk.core.model.FixtureCase
import com.doppelisk.core.model.FixtureFailure
import com.doppelisk.core.model.FixtureReport
import com.doppelisk.core.model.JsonFormats

/**
 * Runs and compares audit fixtures.
 *
 * A fixture is a list of `(category, field)` assertions with an expected value.
 * The same fixture can be:
 *
 *  - [evaluate]d against its own captured `actual` values, to check that a pack
 *    still passes on a given device;
 *  - [capture]d from a live [AuditReport], to record what the hooks actually
 *    returned;
 *  - [diff]ed against a golden fixture, to see what changed between devices or
 *    app versions.
 */
object FixtureHarness {

    private val json = JsonFormats.profile

    /** Evaluates every case in [fixture] against its captured actual values. */
    fun evaluate(fixture: AuditFixture): FixtureReport {
        var passed = 0
        var skipped = 0
        val failures = mutableListOf<FixtureFailure>()

        for (case in fixture.cases) {
            val actual = case.actual
            when {
                actual == null && !case.required -> skipped++
                actual == null -> failures += failure(case, "missing actual value")
                case.expected == null -> passed++
                case.expected == actual -> passed++
                else -> failures += failure(case, "expected '${case.expected}' but observed '$actual'")
            }
        }

        return FixtureReport(
            ok = failures.isEmpty(),
            pack = fixture.pack,
            device = fixture.device,
            os = fixture.os,
            passed = passed,
            skipped = skipped,
            failures = failures,
        )
    }

    /**
     * Fills in each case's real/actual values from [report].
     *
     * When the report has several observations for the same field, the last one
     * wins — hooks run in order, so the final value is the one the app saw.
     */
    fun capture(fixture: AuditFixture, report: AuditReport): AuditFixture {
        val byField = report.observations
            .groupBy { it.category to it.field }
            .mapValues { (_, values) -> values.last() }

        return fixture.copy(
            cases = fixture.cases.map { case ->
                val evidence: BoundaryEvidence? = byField[case.category to case.field]
                case.copy(
                    real = evidence?.real ?: case.real,
                    actual = evidence?.returned ?: case.actual,
                )
            },
        )
    }

    /** Returns one failure per case in [golden] that [captured] does not match. */
    fun diff(golden: AuditFixture, captured: AuditFixture): List<FixtureFailure> {
        val actualById = captured.cases.associateBy(FixtureCase::id)

        return golden.cases.mapNotNull { expected ->
            val actual = actualById[expected.id]
            val expectedValue = expected.expected ?: expected.actual

            if (actual == null) {
                FixtureFailure(
                    id = expected.id,
                    category = expected.category,
                    field = expected.field,
                    expected = expectedValue,
                    actual = null,
                    reason = "case is missing from captured fixture",
                )
            } else {
                val actualValue = actual.actual
                when {
                    actualValue == null && !expected.required -> null
                    actualValue == null -> FixtureFailure(
                        expected.id, expected.category, expected.field,
                        expectedValue, null, "missing actual value",
                    )
                    expectedValue == null || expectedValue == actualValue -> null
                    else -> FixtureFailure(
                        expected.id, expected.category, expected.field,
                        expectedValue, actualValue,
                        "expected '$expectedValue' but observed '$actualValue'",
                    )
                }
            }
        }
    }

    fun encode(fixture: AuditFixture): String = json.encodeToString(AuditFixture.serializer(), fixture)

    fun decode(text: String): AuditFixture = json.decodeFromString(AuditFixture.serializer(), text)

    fun encode(report: FixtureReport): String = json.encodeToString(FixtureReport.serializer(), report)

    fun decodeReport(text: String): FixtureReport = json.decodeFromString(FixtureReport.serializer(), text)

    private fun failure(case: FixtureCase, reason: String) = FixtureFailure(
        id = case.id,
        category = case.category,
        field = case.field,
        expected = case.expected,
        actual = case.actual,
        reason = reason,
    )
}
