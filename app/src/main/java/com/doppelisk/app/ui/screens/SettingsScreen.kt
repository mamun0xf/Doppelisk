package com.doppelisk.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.ManagerUiState
import com.doppelisk.app.ui.TargetRow
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.EmptyState
import com.doppelisk.app.ui.components.InfoNote
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusBanner
import com.doppelisk.app.ui.components.StatusLabel
import com.doppelisk.app.ui.components.TextAction
import com.doppelisk.app.ui.components.Tone
import com.doppelisk.app.ui.theme.Palette

/**
 * The Settings tab: the three things that must be online for a profile to take
 * effect, plus a few facts about the installation.
 *
 * Before the first status call returns the flags are placeholders, so rows say
 * "Checking…" rather than reporting three false failures on every launch.
 */
@Composable
fun SettingsScreen(
    state: ManagerUiState,
    onOpenScope: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    val inScope = state.rows.count { it.inScope }
    val waiting = state.rows.count { it.hasDoppelisk && !it.inScope }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item { ScreenHeader(title = "Settings") }

        state.error?.let { error ->
            item {
                StatusBanner(
                    error,
                    Tone.Error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        state.message?.let { message ->
            item {
                StatusBanner(
                    message,
                    Tone.Success,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        item { SectionHeader("System") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                StatusRow(Icons.Outlined.Build, "Root", state.root, state.statusChecked, state.startupGrace)
                ListDivider(startIndent = 16.dp)
                StatusRow(Icons.Outlined.Lock, "Daemon", state.daemon, state.statusChecked, state.startupGrace)
                ListDivider(startIndent = 16.dp)
                StatusRow(Icons.Outlined.Share, "LSPosed", state.lsposed, state.statusChecked, state.startupGrace)
            }
        }

        item { SectionHeader("Module") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                LinkRow("Apps in scope", inScope.toString()) { onOpenScope(false) }
                ListDivider(startIndent = 16.dp)
                LinkRow("Waiting for scope", waiting.toString()) { onOpenScope(true) }
            }
        }

        item { SectionHeader("About") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                LinkRow("About Doppelisk", version, onOpenAbout)
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    online: Boolean,
    checked: Boolean,
    startupGrace: Boolean,
) {
    // During the startup window a "not yet" answer is not a failure: `su` can
    // take a few seconds and the Xposed binder is delivered after the first
    // status call. Say "Checking…" until the window closes or the service
    // actually comes online.
    val checking = !checked || (startupGrace && !online)
    val color = when {
        checking -> Palette.TextTertiary
        online -> Palette.Mint
        else -> Palette.Rose
    }
    val value = when {
        checking -> "Checking…"
        online -> "Online"
        else -> "Offline"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        StatusLabel(value, color, dot = false)
    }
}

/** A settings row that opens a detail screen: label, current value, chevron. */
@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The apps behind the two scope counters.
 *
 * Scope is what decides whether a profile has any effect at all, so the number
 * on the settings row is only useful if it can be opened: one list shows what is
 * already hooked, the other what is waiting. A waiting app gets a request action
 * instead of a dead end — that is the one place a user can fix the situation
 * without leaving the manager.
 */
@Composable
fun ScopeListScreen(
    state: ManagerUiState,
    waiting: Boolean,
    onBack: () -> Unit,
    onRequestScope: (ManagerRepository.TargetOption) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val rows = state.rows.filter { row ->
        if (waiting) row.hasDoppelisk && !row.inScope else row.inScope
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = if (waiting) "Waiting for scope" else "Apps in scope",
                subtitle = if (waiting) {
                    "Profiles exist, but the module is not enabled for these apps yet."
                } else {
                    "These apps run with their profiles applied."
                },
                onBack = onBack,
            )
        }

        if (rows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = if (waiting) "Nothing waiting" else "No apps in scope",
                    body = if (waiting) {
                        "Every app with a profile is already enabled in LSPosed."
                    } else {
                        "Add an app and request scope to see it here."
                    },
                )
            }
        } else {
            item {
                ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    rows.forEachIndexed { index, row ->
                        ScopeRow(
                            row = row,
                            waiting = waiting,
                            onRequestScope = onRequestScope,
                        )
                        if (index != rows.lastIndex) ListDivider(startIndent = 16.dp)
                    }
                }
            }
        }

        item {
            InfoNote(
                if (waiting) {
                    "Request adds the app to the module's scope. If the request is refused, " +
                        "enable the app manually in LSPosed and relaunch it — the profile only " +
                        "takes effect inside the hooked process."
                } else {
                    "Scope is granted by LSPosed, not by Doppelisk. Removing an app there " +
                        "moves it back to the waiting list."
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ScopeRow(
    row: TargetRow,
    waiting: Boolean,
    onRequestScope: (ManagerRepository.TargetOption) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.option.packageName, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.option.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.option.packageName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (waiting) {
            TextAction("Request", onClick = { onRequestScope(row.option) })
        } else {
            val count = row.profileCount
            StatusLabel(
                if (count == 1) "1 profile" else "$count profiles",
                Palette.Mint,
                dot = false,
            )
        }
    }
}
