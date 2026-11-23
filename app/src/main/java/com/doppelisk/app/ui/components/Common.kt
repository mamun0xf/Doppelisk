package com.doppelisk.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.doppelisk.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How loud a banner is. */
enum class Tone { Info, Success, Error }

// ------------------------------------------------------------------ identity

/**
 * The product mark: two interlocking rings, one periwinkle and one mint.
 *
 * It is drawn rather than shipped as a bitmap so the same mark can be the app
 * icon, the header logo, and the fallback tile for a package whose icon cannot
 * be read, at any size and without assets.
 */
@Composable
fun DoppeliskAvatar(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val left = MaterialTheme.colorScheme.primary
        val right = MaterialTheme.colorScheme.secondary
        Canvas(Modifier.size(size)) {
            val radius = this.size.minDimension * 0.22f
            val gap = radius * 0.62f
            val stroke = this.size.minDimension * 0.072f
            drawCircle(
                color = left,
                radius = radius,
                center = Offset(center.x - gap, center.y),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawCircle(
                color = right,
                radius = radius,
                center = Offset(center.x + gap, center.y),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * The launcher icon of [packageName], or a monogram when it cannot be read.
 *
 * Loading happens off the main thread: the app list can be several hundred rows
 * and `getApplicationIcon` touches the package manager for every one.
 */
@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    if (packageName == "com.doppelisk.app") {
        DoppeliskAvatar(size = size, modifier = modifier)
        return
    }

    val context = LocalContext.current
    var bitmap by remember(packageName, size) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName, size) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val pixels = (size.value * context.resources.displayMetrics.density).toInt()
                    .coerceAtLeast(24)
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(pixels, pixels)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = packageName.substringAfterLast('.').take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ------------------------------------------------------------------ headers

/**
 * The header used by destinations: a back arrow, a title with an optional
 * subtitle, and trailing actions.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 8.dp else 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        actions()
    }
}

/** The home header: product mark, product name, a count, and one Add action. */
@Composable
fun AppHeader(
    subtitle: String,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DoppeliskAvatar(size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Doppelisk",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextAction("Add", onAdd)
    }
}

/** A bold section title with an optional text action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextAction(actionLabel, onAction)
        }
    }
}

// -------------------------------------------------------------------- cards

/**
 * A rounded surface. This is the only container that holds rows or content:
 * a flat section of the page is either the background or one of these.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** A card whose rows supply their own horizontal padding and dividers. */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard(
        modifier = modifier,
        padding = PaddingValues(vertical = verticalPadding),
        content = content,
    )
}

/** The hairline between rows inside a [ListGroup]. */
@Composable
fun ListDivider(startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** `Label            value`, the shape of every read-only detail row. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A muted explanatory note with an info glyph. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ actions

/** A full-width filled button; the one primary action on a screen. */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** An outlined button for the second action next to a [PrimaryAction]. */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A link-shaped action, used in headers and list rows. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A chip used for filters and selected targets.
 *
 * The selected state is a tinted fill with a check glyph; unselected chips are
 * just an outline, so a row of them stays quiet.
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingCheck: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Palette.PeriwinkleDim else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Palette.PeriwinkleStrong else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected && leadingCheck) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** `● Active` or `● Not hooked` in a semantic colour, without a background. */
@Composable
fun StatusLabel(text: String, color: Color, dot: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** A small coloured pill, for a count or a pass/fail verdict. */
@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// -------------------------------------------------------------------- states

/** A centred placeholder with a circled glyph, copy, and an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryAction(actionLabel, onAction)
        }
    }
}

/**
 * Shown while the first load is in flight.
 *
 * The state starts with no rows, so without this a cold start renders the
 * "No profiles yet" empty state for two or three seconds and then swaps in the
 * real list — which reads as data loss rather than loading.
 */
@Composable
fun LoadingState(text: String = "Loading…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A one-line banner for the result of the last action. */
@Composable
fun StatusBanner(
    message: String,
    tone: Tone = Tone.Info,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val container = when (tone) {
        Tone.Info -> MaterialTheme.colorScheme.primaryContainer
        Tone.Success -> MaterialTheme.colorScheme.secondaryContainer
        Tone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        Tone.Info -> Palette.Periwinkle
        Tone.Success -> Palette.Mint
        Tone.Error -> Palette.Rose
    }

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** A small inline spinner for a card that is reloading. */
@Composable
fun BusyOverlay(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A destructive-action confirmation. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** A label that truncates rather than pushing the layout. */
@Composable
fun EllipsizedText(text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A monospace value used for identifiers and fingerprints. */
@Composable
fun MonoText(text: String, size: Int = 13, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
