package com.doppelisk.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ui.ManagerUiState
import com.doppelisk.app.ui.TargetRow
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.EmptyState
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.LoadingState
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusBanner
import com.doppelisk.app.ui.components.SurfaceCard
import com.doppelisk.app.ui.components.Tone
import com.doppelisk.app.ui.theme.Palette
import com.doppelisk.app.ui.components.ChoiceChip
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.BoundaryEvidence

/**
 * The Audit tab: what the hooks actually returned inside the selected app.
 *
 * This is the only screen that reports on the hook side rather than on the
 * configuration, so it is written as evidence rather than as settings: the
 * summary counts what changed, what agreed, and what could not be checked, and
 * the gaps are spelled out instead of being silently dropped.
 */
@Composable
fun VerifyScreen(
    state: ManagerUiState,
    onOpenApps: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onRelaunch: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val targets = state.doppeliskRows
    val row = state.selectedRow?.takeIf { it.hasDoppelisk } ?: targets.firstOrNull()
    val report = state.audit

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = "Consistency Check",
                subtitle = "What the target app actually observed.",
            ) {
                if (state.auditLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        state.error?.let { error ->
            item {
                StatusBanner(
                    error,
                    Tone.Error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        if (state.initialLoading && targets.isEmpty()) {
            item { LoadingState("Loading observations…") }
            return@LazyColumn
        }

        if (targets.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Nothing to check yet",
                    body = "Create a profile for an app, launch it once, then come back.",
                    actionLabel = "Go to Apps",
                    onAction = onOpenApps,
                )
            }
            return@LazyColumn
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (target in targets) {
                    ChoiceChip(
                        text = target.option.label,
                        selected = target.option.packageName == row?.option?.packageName,
                        onClick = { onSelectTarget(target.option.packageName) },
                    )
                }
            }
        }

        if (row != null) {
            item {
                SelectedTargetCard(row = row, onRelaunch = onRelaunch)
            }

            if (report == null) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Info,
                        title = "No observations yet",
                        body = "Launch ${row.option.label} once with its profile active.",
                        actionLabel = "Relaunch app",
                        onAction = onRelaunch,
                    )
                }
            } else {
                // A report with no observations is still a report: it records why
                // nothing was observed (daemon down, hooks skipped, ...). Showing
                // "no observations" and hiding the gaps made a real failure look
                // like an empty screen.
                item {
                    ObservedValuesCard(report)
                }

                if (report.gaps.isNotEmpty()) {
                    item { GapsCard(report.gaps) }
                }

                if (report.observations.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Info,
                            title = "No values recorded",
                            body = if (report.gaps.isEmpty()) {
                                "Launch ${row.option.label} once with its profile active."
                            } else {
                                "The hooks could not read anything this time; see the gaps above."
                            },
                            actionLabel = "Relaunch app",
                            onAction = onRelaunch,
                        )
                    }
                } else {
                    val grouped = report.observations.groupBy { it.category }.toSortedMap()
                    for ((category, observations) in grouped) {
                        item(key = "header-$category") {
                            SectionHeader(category.replaceFirstChar { it.uppercase() })
                        }
                        item(key = "rows-$category") {
                            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                                observations.forEachIndexed { index, evidence ->
                                    EvidenceRow(evidence)
                                    if (index != observations.lastIndex) {
                                        ListDivider(startIndent = 16.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedTargetCard(row: TargetRow, onRelaunch: () -> Unit) {
    val active = row.activeProfile

    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.option.packageName, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.option.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (active == null) {
                        "No active identity"
                    } else {
                        "${row.option.label} — ${if (active.isDefault) "This device" else active.label}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRelaunch) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Relaunch app",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ObservedValuesCard(report: AuditReport) {
    val total = report.observations.size
    val changed = report.mismatches.size
    val gaps = report.gaps.size
    val unchanged = (total - changed).coerceAtLeast(0)

    SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Observed values",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$total total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Metric(changed.toString(), "changed")
            Metric(unchanged.toString(), "unchanged")
            Metric(gaps.toString(), "gaps")
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GapsCard(gaps: List<String>) {
    SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = Palette.Amber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Gaps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(8.dp))
        for (gap in gaps) {
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("•", color = Palette.Periwinkle)
                Spacer(Modifier.width(8.dp))
                Text(
                    gap,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun EvidenceRow(evidence: BoundaryEvidence) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            evidence.field,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${evidence.real ?: "(unknown)"}  →  ${evidence.returned ?: "(null)"}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
