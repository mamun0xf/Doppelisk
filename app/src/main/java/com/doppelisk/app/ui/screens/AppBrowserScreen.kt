package com.doppelisk.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.ManagerUiState
import com.doppelisk.app.ui.TargetRow
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.ChoiceChip
import com.doppelisk.app.ui.components.LoadingState
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SurfaceCard

private enum class ProfileFilter(val label: String) {
    All("All"),
    WithProfile("With profile"),
    NoProfile("No profile"),
}

/**
 * The Apps tab, titled "Add app": every installed user package with the two
 * facts that matter for a new identity — does it have a profile, and is it in
 * the module's scope.
 *
 * There is no curated list. A profile describes a device, not an app, so the
 * only sensible filter is what is installed. System packages are left out
 * because there are hundreds of them and they are almost never the target.
 */
@Composable
fun AppBrowserScreen(
    state: ManagerUiState,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onNewProfile: (ManagerRepository.TargetOption) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ProfileFilter.All) }

    val rows = state.rows
        .asSequence()
        .filterNot { it.option.isSystem }
        .filter { row ->
            when (filter) {
                ProfileFilter.All -> true
                ProfileFilter.WithProfile -> row.hasDoppelisk
                ProfileFilter.NoProfile -> !row.hasDoppelisk
            }
        }
        .filter { matches(it, query) }
        .toList()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ScreenHeader(
                title = "Add app",
                subtitle = "Choose an app for the new identity.",
                onBack = onBack,
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                placeholder = {
                    Text(
                        "Search apps or packages",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (entry in ProfileFilter.entries) {
                    ChoiceChip(
                        text = entry.label,
                        selected = filter == entry,
                        onClick = { filter = entry },
                    )
                }
            }
        }

        if (state.initialLoading && state.rows.isEmpty()) {
            item { LoadingState("Loading apps…") }
        } else if (rows.isEmpty()) {
            item {
                Text(
                    "No matching apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }

        items(rows, key = { it.option.packageName }) { row ->
            BrowserAppRow(
                row = row,
                onOpen = { onOpenApp(row.option.packageName) },
                onAdd = { onNewProfile(row.option) },
            )
        }
    }
}

@Composable
private fun BrowserAppRow(
    row: TargetRow,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    val profiles = row.profiles.size + if (row.defaultProfile.stored) 1 else 0

    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        onClick = onOpen,
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.option.packageName, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    row.option.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    row.option.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append(
                            when (profiles) {
                                0 -> "No profile"
                                1 -> "1 profile"
                                else -> "$profiles profiles"
                            },
                        )
                        append(" · ")
                        append(if (row.inScope) "in scope" else "not in scope")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "New profile",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun matches(row: TargetRow, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return row.option.label.contains(needle, ignoreCase = true) ||
        row.option.packageName.contains(needle, ignoreCase = true)
}
