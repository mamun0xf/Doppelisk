package com.doppelisk.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doppelisk.app.ui.components.ChoiceChip
import com.doppelisk.app.ui.components.InfoNote
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.PrimaryAction
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.SurfaceCard
import com.doppelisk.app.ui.fields.CustomDeviceSpec
import com.doppelisk.app.ui.fields.ProfileDrafts
import com.doppelisk.core.model.Chassis

/**
 * Picks the device a profile should describe.
 *
 * The catalog is the default source: it carries a coherent build, display and
 * hardware surface per entry. The custom option exists for a device the catalog
 * has never heard of, and it borrows hardware from the preset so that the parts
 * nobody types (kernel, baseband, sensors) still agree with each other.
 */
@Composable
fun DevicePickerScreen(
    chassis: List<Chassis>,
    onBack: () -> Unit,
    onPick: (Chassis) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var vendor by rememberSaveable { mutableStateOf("All") }

    val vendors = remember(chassis) {
        chassis.map { it.build.manufacturer }.distinct()
    }
    val visible = if (vendor == "All") chassis else chassis.filter { it.build.manufacturer == vendor }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = "Choose a device",
                subtitle = "Start from a real device. Every field stays editable.",
                onBack = onBack,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip("All", selected = vendor == "All", onClick = { vendor = "All" })
                for (entry in vendors) {
                    ChoiceChip(entry, selected = vendor == entry, onClick = { vendor = entry })
                }
            }
        }

        item {
            SurfaceCard(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                onClick = onCustom,
                padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Custom device",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Start from blank fields.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionHeader("Presets")
        }

        item {
            Text(
                "${visible.size} devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                visible.forEachIndexed { index, entry ->
                    PresetRow(entry, onClick = { onPick(entry) })
                    if (index != visible.lastIndex) ListDivider(startIndent = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun PresetRow(chassis: Chassis, onClick: () -> Unit) {
    val build = chassis.build
    val display = chassis.display
    val refresh = display.refreshRates.maxOrNull() ?: 60

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chassis.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${build.manufacturer} · Android ${build.release} · " +
                    "${display.width}×${display.height} · $refresh Hz",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chassis.hardware.soc,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Builds a chassis that is not in the catalog.
 *
 * Only the fields a person can meaningfully type are asked for. Everything else
 * is inherited from [base], so the result is coherent by construction instead
 * of being a device whose build strings and kernel disagree.
 */
@Composable
fun CustomDeviceScreen(
    base: Chassis,
    onBack: () -> Unit,
    onCreate: (Chassis) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var spec by remember(base) {
        mutableStateOf(
            CustomDeviceSpec(
                soc = base.hardware.soc,
                gpuRenderer = base.hardware.gpuRenderer,
                screenInches = base.display.screenInches,
            ),
        )
    }
    var width by remember(base) { mutableStateOf(spec.width.toString()) }
    var height by remember(base) { mutableStateOf(spec.height.toString()) }
    var density by remember(base) { mutableStateOf(spec.density.toString()) }
    var refresh by remember(base) { mutableStateOf(spec.refresh.toString()) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = "Custom device",
                subtitle = "Start from blank fields, then edit every property.",
                onBack = onBack,
            )
        }

        item {
            InfoNote(
                "Unset fields start from ${base.name}. You can edit them later.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item { GroupLabel("Name") }
        item {
            SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                SpecField("Manufacturer", spec.manufacturer) { spec = spec.copy(manufacturer = it) }
                SpecField("Brand", spec.brand) { spec = spec.copy(brand = it) }
                SpecField("Model", spec.model) { spec = spec.copy(model = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpecField("Codename", spec.device, Modifier.weight(1f)) { spec = spec.copy(device = it) }
                    SpecField("Product", spec.product, Modifier.weight(1f)) { spec = spec.copy(product = it) }
                }
            }
        }

        item { GroupLabel("Build") }
        item {
            SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpecField("Android release", spec.release, Modifier.weight(1f)) {
                        spec = spec.copy(release = it)
                    }
                    SpecField("Security patch", spec.securityPatch, Modifier.weight(1f)) {
                        spec = spec.copy(securityPatch = it)
                    }
                }
                SpecField("Build ID", spec.buildId) { spec = spec.copy(buildId = it) }
            }
        }

        item { GroupLabel("Hardware") }
        item {
            SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                SpecField("SoC identifier", spec.soc) { spec = spec.copy(soc = it) }
                SpecField("GPU renderer", spec.gpuRenderer) { spec = spec.copy(gpuRenderer = it) }
            }
        }

        item { GroupLabel("Display") }
        item {
            SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpecField("Width", width, Modifier.weight(1f)) { width = it }
                    SpecField("Height", height, Modifier.weight(1f)) { height = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpecField("Density", density, Modifier.weight(1f)) { density = it }
                    SpecField("Refresh (Hz)", refresh, Modifier.weight(1f)) { refresh = it }
                }
                SpecField("Screen size (inches)", spec.screenInches) {
                    spec = spec.copy(screenInches = it)
                }
            }
        }

        item {
            PrimaryAction(
                "Create custom device",
                onClick = {
                    val resolved = spec.copy(
                        width = width.toIntOrNull() ?: spec.width,
                        height = height.toIntOrNull() ?: spec.height,
                        density = density.toIntOrNull() ?: spec.density,
                        refresh = refresh.toIntOrNull() ?: spec.refresh,
                    )
                    onCreate(ProfileDrafts.customChassis(resolved, base))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SpecField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(vertical = 5.dp),
    )
}
