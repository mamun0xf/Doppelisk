package com.doppelisk.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ui.ManagerUiState
import com.doppelisk.app.ui.TargetRow
import com.doppelisk.app.ui.components.AppHeader
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.EmptyState
import com.doppelisk.app.ui.components.LoadingState
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusBanner
import com.doppelisk.app.ui.components.StatusLabel
import com.doppelisk.app.ui.components.SurfaceCard
import com.doppelisk.app.ui.components.Tone
import com.doppelisk.app.ui.theme.Palette

/**
 * The Profiles tab: one row per app that has identities, split into apps with
 * an active profile and apps whose profiles are only stored.
 *
 * This screen is deliberately shallow: it answers "which apps have identities"
 * and nothing else. Device names live on the detail screen, where they belong;
 * here the only useful count is how many profiles an app has.
 */
@Composable
fun ProfilesHomeScreen(
    state: ManagerUiState,
    onAdd: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val rows = state.doppeliskRows
    val activeRows = rows.filter { it.activeProfile != null }
    val storedRows = rows.filter { it.activeProfile == null }
    val profileCount = state.doppeliskCount

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            AppHeader(
                subtitle = when {
                    state.initialLoading && rows.isEmpty() -> "Loading…"
                    profileCount == 1 -> "1 profile"
                    else -> "$profileCount profiles"
                },
                onAdd = onAdd,
            )
        }

        state.error?.let { error ->
            item { StatusBanner(error, Tone.Error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        state.message?.let { message ->
            item { StatusBanner(message, Tone.Success, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }

        // The banner appears only once the startup probes have settled; before
        // that a not-yet-online service is still "Checking…", not a failure.
        if (state.statusChecked && !state.startupGrace && !state.setupReady) {
            item { SetupBanner(state, onOpenSettings) }
        }

        if (state.initialLoading && rows.isEmpty()) {
            item { LoadingState("Loading profiles…") }
        } else if (rows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Person,
                    title = "No profiles yet",
                    body = "Choose an app to create its first identity.",
                    actionLabel = "Add app",
                    onAction = onAdd,
                )
            }
        } else {
            if (activeRows.isNotEmpty()) {
                item { SectionHeader("Active") }
                items(activeRows, key = { it.option.packageName }) { row ->
                    AppRow(row = row, active = true, onOpenApp = onOpenApp)
                }
            }

            if (storedRows.isNotEmpty()) {
                item { SectionHeader("Stored") }
                items(storedRows, key = { it.option.packageName }) { row ->
                    AppRow(row = row, active = false, onOpenApp = onOpenApp)
                }
            }
        }
    }
}

@Composable
private fun AppRow(row: TargetRow, active: Boolean, onOpenApp: (String) -> Unit) {
    val profiles = row.profileCount

    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        onClick = { onOpenApp(row.option.packageName) },
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.option.packageName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.option.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    if (profiles == 1) "1 profile" else "$profiles profiles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                StatusLabel("Active", Palette.Mint)
            } else {
                Text(
                    "Stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SetupBanner(state: ManagerUiState, onOpenSettings: () -> Unit) {
    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        onClick = onOpenSettings,
    ) {
        Text(
            "Setup incomplete",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            "A profile can only take effect when root, the daemon, and LSPosed are all online.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
            Requirement("Root", state.root)
            Requirement("Daemon", state.daemon)
            Requirement("LSPosed", state.lsposed)
        }
    }
}

@Composable
private fun Requirement(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusLabel(
            if (ok) "Online" else "Offline",
            if (ok) Palette.Mint else Palette.Rose,
        )
    }
}
