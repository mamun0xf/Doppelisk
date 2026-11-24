package com.doppelisk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.components.AppIcon
import com.doppelisk.app.ui.components.InfoNote
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.PrimaryAction
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SecondaryAction
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusBanner
import com.doppelisk.app.ui.components.SurfaceCard
import com.doppelisk.app.ui.components.TextAction
import com.doppelisk.app.ui.components.Tone
import com.doppelisk.app.ui.deviceLabel
import com.doppelisk.app.ui.fields.FieldCategory
import com.doppelisk.app.ui.fields.FieldKind
import com.doppelisk.app.ui.fields.FieldSpec
import com.doppelisk.app.ui.fields.CarrierPresets
import com.doppelisk.app.ui.fields.PhoneNumbers
import com.doppelisk.app.ui.fields.ProfileFields
import com.doppelisk.app.ui.fields.RegionPreset
import com.doppelisk.app.ui.fields.RegionPresets
import com.doppelisk.core.identity.CarrierCatalog
import com.doppelisk.core.model.Carrier
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import java.util.Locale

/**
 * The editor's landing screen.
 *
 * It is a table of contents, not a form: a profile has around fifty values and
 * showing them as one wall of inputs makes the three or four that matter
 * impossible to find. The header states what the profile is *for* — the app,
 * the preset it starts from, and its name — while the categories below open the
 * one group of fields the user is actually looking for.
 */
@Composable
fun ProfileEditorScreen(
    target: ManagerRepository.TargetOption,
    draft: DoppeliskArtifact,
    categories: List<FieldCategory>,
    isNew: Boolean,
    error: String?,
    saving: Boolean,
    onDraftChange: (DoppeliskArtifact) -> Unit,
    onChangeDevice: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val device = deviceLabel(draft.identity.build.manufacturer, draft.identity.build.model)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = if (isNew) "New profile" else "Edit profile",
                subtitle = target.label,
                onBack = onBack,
            ) {
                TextAction(
                    if (isNew) "Save" else "Save",
                    onClick = onSave,
                    enabled = !saving,
                )
            }
        }

        error?.let { message ->
            item {
                StatusBanner(
                    message,
                    Tone.Error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        item {
            IdentityCard(
                target = target,
                draft = draft,
                isNew = isNew,
                saving = saving,
                onDraftChange = onDraftChange,
            )
        }

        item {
            DevicePresetCard(
                device = device,
                draft = draft,
                onChangeDevice = onChangeDevice,
                enabled = !saving,
            )
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    "Properties",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Fields at Default match the device preset.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp)) {
                categories.forEachIndexed { index, category ->
                    CategoryRow(category, onClick = { onOpenCategory(category.id) })
                    if (index != categories.lastIndex) ListDivider(startIndent = 20.dp)
                }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                PrimaryAction(
                    text = if (isNew) "Create profile" else "Save changes",
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    loading = saving,
                )
            }
        }

        item {
            Text(
                "Changes take effect the next time the app starts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun IdentityCard(
    target: ManagerRepository.TargetOption,
    draft: DoppeliskArtifact,
    isNew: Boolean,
    saving: Boolean,
    onDraftChange: (DoppeliskArtifact) -> Unit,
) {
    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        padding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(target.packageName, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    target.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isNew) "Not saved yet" else "Saved identity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Preset",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ListDivider()

        Box(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = draft.label,
                onValueChange = { onDraftChange(draft.copy(label = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !saving,
                label = { Text("Profile name") },
            )
        }
    }
}

@Composable
private fun DevicePresetCard(
    device: String,
    draft: DoppeliskArtifact,
    onChangeDevice: () -> Unit,
    enabled: Boolean,
) {
    val build = draft.identity.build
    val display = draft.identity.display
    val hardware = draft.identity.hardware

    SurfaceCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "Device preset",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            device,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${build.device}  ·  Android ${build.release}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${hardware.soc}  ·  ${display.width}×${display.height}  ·  ${display.refresh} Hz",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))
        SecondaryAction(
            "Switch preset",
            onClick = onChangeDevice,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            icon = Icons.Outlined.Refresh,
        )
    }
}

@Composable
private fun CategoryRow(category: FieldCategory, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                category.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                category.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            category.fields.size.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * One category: every field as a labelled value with a pencil.
 *
 * Tapping a row opens the one editor that fits its kind. That is also where
 * "use the real device's value" lives, because a row is the smallest thing that
 * can meaningfully be reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileCategoryScreen(
    category: FieldCategory,
    draft: DoppeliskArtifact,
    onBack: () -> Unit,
    onCommitField: (String, String) -> Unit,
    onResetField: (String) -> Unit,
    onPickRegion: (RegionPreset) -> Unit,
    onPickCarrier: (Carrier) -> Unit,
    realNumbers: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var editing by remember { mutableStateOf<FieldSpec?>(null) }
    var regionOpen by remember { mutableStateOf(false) }
    var carrierOpen by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = category.title,
                subtitle = category.subtitle,
                onBack = onBack,
            )
        }

        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp)) {
                category.fields.forEachIndexed { index, field ->
                    FieldRow(
                        field = field,
                        onClick = {
                            when {
                                field.key == "region.country" -> regionOpen = true
                                field.key == IdentityFields.TELEPHONY_CARRIER ->
                                    carrierOpen = true
                                field.kind != FieldKind.READONLY -> editing = field
                            }
                        },
                    )
                    if (index != category.fields.lastIndex) ListDivider(startIndent = 20.dp)
                }
            }
        }

        if (category.id == "region_carrier") {
            item {
                InfoNote(
                    "Country, carrier, MCC/MNC, phone numbers, timezone, and location all describe one region. " +
                        "Tap Country to fill them together, or Carrier to pick a different operator from the " +
                        "same country. Nothing is saved until you tap Save.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    editing?.let { field ->
        FieldEditorSheet(
            field = field,
            canReset = field.isOverridden,
            countryIso = draft.identity.telephony.countryIso,
            carrier = draft.identity.telephony.carrier,
            realNumbers = realNumbers,
            onCommit = { value ->
                onCommitField(field.key, value)
                editing = null
            },
            onReset = {
                onResetField(field.key)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (regionOpen) {
        RegionPickerSheet(
            current = draft,
            onPick = { preset ->
                onPickRegion(preset)
                regionOpen = false
            },
            onDismiss = { regionOpen = false },
        )
    }

    if (carrierOpen) {
        CarrierPickerSheet(
            current = draft,
            onPick = { carrier ->
                onPickCarrier(carrier)
                carrierOpen = false
            },
            onCustom = {
                carrierOpen = false
                editing = category.fields.firstOrNull {
                    it.key == IdentityFields.TELEPHONY_CARRIER
                }
            },
            onDismiss = { carrierOpen = false },
        )
    }
}

@Composable
private fun FieldRow(field: FieldSpec, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = field.kind != FieldKind.READONLY, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                field.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                field.value.ifBlank { field.placeholder ?: "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (field.mono) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (field.multiline) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (field.kind != FieldKind.READONLY) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Edit ${field.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The editor for one [FieldSpec], chosen by its [FieldKind]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditorSheet(
    field: FieldSpec,
    canReset: Boolean,
    countryIso: String,
    carrier: String,
    realNumbers: List<String>,
    onCommit: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(field.key) { mutableStateOf(field.value) }
    var error by remember(field.key) { mutableStateOf<String?>(null) }

    fun commit(value: String = text) {
        val problem = ProfileFields.validateField(field.key, value, countryIso)
        if (problem != null) {
            error = problem
        } else {
            onCommit(value)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.size(12.dp))

            when (field.kind) {
                FieldKind.SELECT -> {
                    Column(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (option in field.options) {
                            ChoiceRow(
                                label = ProfileFields.formatOption(option),
                                selected = option.equals(field.value, ignoreCase = true),
                                onClick = { onCommit(option) },
                            )
                        }
                    }
                }

                FieldKind.BOOLEAN -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (text.toBoolean()) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = text.toBoolean(), onCheckedChange = { commit(it.toString()) })
                    }
                }

                FieldKind.READONLY -> Text(
                    field.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )

                FieldKind.INTEGER, FieldKind.TEXT -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1,
                        isError = error != null,
                        label = { Text(field.label) },
                        placeholder = field.placeholder?.let { { Text(it) } },
                        textStyle = if (field.mono) {
                            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.kind == FieldKind.INTEGER) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Text
                            },
                        ),
                    )

                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    if (field.key in IdentityFields.TELEPHONY_PHONES) {
                        val slot = IdentityFields.TELEPHONY_PHONES.indexOf(field.key)
                        val real = realNumbers.getOrNull(slot).orEmpty()

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val generated = PhoneNumbers.randomize(countryIso, carrier, slot)
                                    if (generated.isBlank()) {
                                        error = "No mobile numbering plan for this country. " +
                                            "Enter the number manually."
                                    } else {
                                        text = generated
                                        error = null
                                    }
                                },
                            ) {
                                Text(
                                    "Randomise for " +
                                        countryIso.ifBlank { "the selected country" },
                                )
                            }

                            if (real.isNotBlank()) {
                                TextButton(onClick = { text = real; error = null }) {
                                    Text("Use SIM ${slot + 1} number")
                                }
                            }
                        }

                        if (real.isNotBlank()) {
                            Text(
                                "This phone's SIM ${slot + 1} reports $real.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.size(16.dp))

            if (field.kind != FieldKind.SELECT && field.kind != FieldKind.READONLY) {
                PrimaryAction(
                    "Apply",
                    onClick = { commit() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Check,
                )
            }

            if (canReset) {
                Spacer(Modifier.size(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onReset) { Text("Use Default (real value)") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                )
                .then(
                    if (selected) {
                        Modifier
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Picks a country.
 *
 * A preset is not just a label: it writes the carrier, MCC/MNC, timezone and
 * coordinates together, because a profile that claims to be in one country while
 * its timezone says another is more suspicious than one that claims nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionPickerSheet(
    current: DoppeliskArtifact,
    onPick: (RegionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val presets = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            RegionPresets.all()
        } else {
            RegionPresets.all().filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.countryIso.contains(needle, ignoreCase = true) ||
                    it.carrier.contains(needle, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Country / region",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Sets carrier, MCC/MNC, timezone, and a representative location.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.size(14.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Search countries") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(presets, key = { it.id + it.mcc + it.mnc }) { preset ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(preset) }
                            .padding(vertical = 11.dp),
                    ) {
                        Text(
                            preset.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${preset.carrier}  ·  ${preset.countryIso}  ·  ${preset.mcc}-${preset.mnc}  ·  ${preset.timezone}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            InfoNote(
                "This changes the draft only. Nothing is saved until you tap Save.",
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * Picks an operator from the country's real MCC/MNC list.
 *
 * The country preset chooses a representative carrier, but a country has
 * several. This sheet shows all of them and writes the name, MCC and MNC
 * together, so a profile can claim `Grameenphone` and `470-01` instead of a
 * name that does not match its numeric. A custom entry stays available for an
 * operator the catalog does not know.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarrierPickerSheet(
    current: DoppeliskArtifact,
    onPick: (Carrier) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val countryIso = current.identity.telephony.countryIso.trim().uppercase(Locale.ROOT)
    val countryName = RegionPresets.forCountry(countryIso).firstOrNull()?.label ?: countryIso
    val selected = current.identity.telephony

    val entries = remember(query, countryIso) {
        val needle = query.trim()
        when {
            countryIso.isBlank() && needle.isEmpty() -> emptyList()
            countryIso.isBlank() -> CarrierCatalog.search(needle)
            needle.isEmpty() -> CarrierPresets.forCountry(countryIso).map { countryIso to it }
            else -> CarrierPresets.forCountry(countryIso)
                .filter {
                    it.name.contains(needle, ignoreCase = true) ||
                        it.numeric.contains(needle)
                }
                .map { countryIso to it }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Carrier",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (countryIso.isBlank()) {
                            "Search every operator, or set a country first."
                        } else {
                            "Operators in $countryName. MCC and MNC are filled in with the name."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.size(14.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = {
                    Text(if (countryIso.isBlank()) "Search all carriers" else "Search carriers")
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            if (entries.isEmpty()) {
                InfoNote(
                    if (countryIso.isBlank() && query.isBlank()) {
                        "Choose a country first, or type a carrier name to search."
                    } else {
                        "No carrier matched. You can still enter a custom name."
                    },
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(entries, key = { it.first + it.second.numeric + it.second.name }) { (iso, carrier) ->
                        val isSelected = carrier.mcc == selected.mcc.trim() &&
                            carrier.mnc == selected.mnc.trim() &&
                            carrier.name.equals(selected.carrier, ignoreCase = true)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(carrier) }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                carrier.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                "${carrier.mcc}-${carrier.mnc}  ·  $iso",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onCustom) { Text("Enter a custom carrier") }
            }

            InfoNote(
                "This changes the draft only. Nothing is saved until you tap Save.",
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}
