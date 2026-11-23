package com.doppelisk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.ManagerUiState
import com.doppelisk.app.ui.ProfileRow
import com.doppelisk.app.ui.TargetRow
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.ConfirmDialog
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.PrimaryAction
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SecondaryAction
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusBanner
import com.doppelisk.app.ui.components.StatusLabel
import com.doppelisk.app.ui.components.TextAction
import com.doppelisk.app.ui.components.Tone
import com.doppelisk.app.ui.deviceLabel
import com.doppelisk.app.ui.fields.ProfileDefaults
import com.doppelisk.app.ui.theme.Palette
import com.doppelisk.core.model.DoppeliskArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One app: a short summary, a Launch button, and the identities stored for it.
 *
 * The list always contains the implicit `Default` row, because "do nothing to
 * this app" is a valid configuration and is the state every app starts in. A
 * `Default` row that has never been edited is not on disk; it materialises the
 * first time the user pins a field or activates it explicitly.
 *
 * Tapping any row opens its editor directly. Activation and destructive actions
 * stay behind explicit controls so a tap on a profile never changes what runs.
 */
@Composable
fun AppDetailScreen(
    state: ManagerUiState,
    row: TargetRow,
    onBack: () -> Unit,
    onEdit: (DoppeliskArtifact, Boolean) -> Unit,
    onNewProfile: () -> Unit,
    onActivate: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClearData: (String) -> Unit,
    onRequestScope: () -> Unit,
    onRelaunch: () -> Unit,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val scope = rememberCoroutineScope()
    val artifacts = remember(row.option.packageName) { mutableStateMapOf<String, DoppeliskArtifact>() }
    val realArtifact = remember(state.device, row.option.packageName) {
        state.device?.let { ProfileDefaults.realArtifact(row.option, it) }
    }

    val storedIds = remember(row.profiles, row.defaultProfile) {
        (listOf(row.defaultProfile) + row.profiles).filter { it.stored }.map { it.id }
    }

    LaunchedEffect(storedIds) {
        val loaded = withContext(Dispatchers.IO) {
            storedIds.mapNotNull { id ->
                ManagerRepository.loadProfile(id)?.let { id to it }
            }
        }
        loaded.forEach { (id, artifact) -> artifacts[id] = artifact }
    }

    var pendingDelete by remember { mutableStateOf<ProfileRow?>(null) }
    var pendingClear by remember { mutableStateOf<ProfileRow?>(null) }

    val active = row.activeProfile
    val activeArtifact = when {
        active == null -> null
        active.stored -> artifacts[active.id]
        else -> realArtifact
    }

    fun edit(profile: ProfileRow) {
        scope.launch {
            val stored = artifacts[profile.id] ?: withContext(Dispatchers.IO) {
                if (profile.stored) ManagerRepository.loadProfile(profile.id) else null
            }
            val artifact = stored ?: if (profile.isDefault) realArtifact else null
            if (artifact != null) onEdit(artifact, !profile.stored)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = row.option.label,
                subtitle = row.option.packageName,
                onBack = onBack,
            )
        }

        state.error?.let { error ->
            item { StatusBanner(error, Tone.Error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        state.message?.let { message ->
            item { StatusBanner(message, Tone.Success, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }

        item { SummaryCard(row, activeArtifact) }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                SecondaryAction("Launch", onRelaunch, icon = Icons.Outlined.PlayArrow)
            }
        }

        item {
            SectionHeader("Profiles", actionLabel = "Add", onAction = onNewProfile)
        }

        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                row.displayProfiles.forEachIndexed { index, profile ->
                    ProfileListRow(
                        row = row,
                        profile = profile,
                        artifact = if (profile.stored) artifacts[profile.id] else realArtifact,
                        onEdit = { edit(profile) },
                        onUse = { onActivate(profile.id, false) },
                        onClearData = { pendingClear = profile },
                        onDelete = { pendingDelete = profile },
                    )
                    if (index != row.displayProfiles.lastIndex) {
                        ListDivider(startIndent = 58.dp)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                SecondaryAction(
                    "Open consistency check",
                    onClick = onOpenAudit,
                    icon = Icons.Outlined.CheckCircle,
                )
            }
        }

        if (!row.inScope) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                    PrimaryAction("Add to LSPosed scope", onRequestScope)
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = if (profile.isDefault) "Reset default profile?" else "Delete profile?",
            text = if (profile.isDefault) {
                "The stored default identity is removed and the app goes back to real values."
            } else {
                "“${profile.label}” is removed from this app. The app's own data is not touched."
            },
            confirmLabel = if (profile.isDefault) "Reset" else "Delete",
            onConfirm = { onDelete(profile.id) },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingClear?.let { profile ->
        ConfirmDialog(
            title = "Clear data?",
            text = "The private data directory and keystore namespace of “${profile.label}” are deleted. The identity itself stays.",
            confirmLabel = "Clear",
            onConfirm = { onClearData(profile.id) },
            onDismiss = { pendingClear = null },
        )
    }
}

@Composable
private fun SummaryCard(row: TargetRow, artifact: DoppeliskArtifact?) {
    val profiles = row.profileCount
    val device = artifact?.let { deviceLabel(it.identity.build.manufacturer, it.identity.build.model) }
    val headline = device ?: row.activeProfile?.label ?: "This device"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.option.packageName, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (profiles == 1) "1 profile" else "$profiles profiles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileListRow(
    row: TargetRow,
    profile: ProfileRow,
    artifact: DoppeliskArtifact?,
    onEdit: () -> Unit,
    onUse: () -> Unit,
    onClearData: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileDot(profile)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                when {
                    profile.isDefault && profile.stored ->
                        artifact?.let {
                            deviceLabel(it.identity.build.manufacturer, it.identity.build.model)
                        } ?: "This device"
                    profile.isDefault -> "This device"
                    else -> artifact?.let {
                        deviceLabel(it.identity.build.manufacturer, it.identity.build.model)
                    } ?: profile.device ?: profile.label
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    // A materialised Default is no longer "real values": it has
                    // stored overrides, so say so instead of claiming pass-through.
                    profile.isDefault && profile.stored -> "Default · modified"
                    profile.isDefault -> "Default · real values"
                    else -> profile.label
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (profile.active) {
            StatusLabel("Active", Palette.Mint)
        } else {
            TextAction("Use", onUse)
        }

        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Profile actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Clear data") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        onClearData()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (profile.isDefault) "Reset default profile" else "Delete profile",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileDot(profile: ProfileRow) {
    if (profile.active) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Palette.MintDim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = Palette.Mint,
                modifier = Modifier.size(18.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
