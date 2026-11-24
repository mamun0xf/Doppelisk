package com.doppelisk.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.doppelisk.app.ui.components.DoppeliskAvatar
import com.doppelisk.app.ui.components.InfoNote
import com.doppelisk.app.ui.components.ListDivider
import com.doppelisk.app.ui.components.ListGroup
import com.doppelisk.app.ui.components.ScreenHeader
import com.doppelisk.app.ui.components.SectionHeader
import com.doppelisk.app.ui.components.StatusLabel
import com.doppelisk.app.ui.theme.Palette

/**
 * Project home page. The row is hidden while this is blank.
 */
private const val SOURCE_URL = "https://github.com/mamun0xf/Doppelisk"

/**
 * What the app is, which build this is, and what it does not do.
 *
 * Settings answers "is the module working"; this screen answers the questions a
 * user has before trusting a root module with their device — where the code
 * comes from, what it is licensed under, what it bundles, and where its
 * behaviour stops.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val build = remember(context) { readBuild(context) }
    var noticesOpen by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item { ScreenHeader(title = "About", onBack = onBack) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DoppeliskAvatar(size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Doppelisk", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Per-app device profiles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { SectionHeader("Build") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                AboutRow("Version", build.versionName)
                ListDivider(startIndent = 16.dp)
                AboutRow("Build", build.versionCode)
                ListDivider(startIndent = 16.dp)
                AboutRow("Channel", build.channel)
                ListDivider(startIndent = 16.dp)
                AboutRow("Package", build.packageName, mono = true)
            }
        }

        item { SectionHeader("Developer") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                AboutRow("Name", "Abdullah Al Mamun")
                ListDivider(startIndent = 16.dp)
                LinkRow(
                    label = "Email",
                    value = "mamun0xf@proton.me",
                    onClick = { openUrl(context, "mailto:mamun0xf@proton.me") },
                )
            }
        }

        item { SectionHeader("Project") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                if (SOURCE_URL.isNotBlank()) {
                    LinkRow(
                        label = "Source code",
                        value = "GitHub",
                        onClick = { openUrl(context, SOURCE_URL) },
                    )
                    ListDivider(startIndent = 16.dp)
                }
                LinkRow(
                    label = "License",
                    value = "Apache-2.0",
                    onClick = { noticesOpen = true },
                )
                ListDivider(startIndent = 16.dp)
                AboutRow("Copyright", "2026 Abdullah Al Mamun")
            }
        }

        item { SectionHeader("Bundled") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                AboutRow("libphonenumber", "Apache-2.0")
                ListDivider(startIndent = 16.dp)
                AboutRow("libxposed", "Apache-2.0")
                ListDivider(startIndent = 16.dp)
                AboutRow("AndroidX, Compose, Kotlin", "Apache-2.0")
                ListDivider(startIndent = 16.dp)
                AboutRow("mcc-mnc-list", "MIT")
            }
        }

        item { SectionHeader("Privacy") }
        item {
            InfoNote(
                "The manager holds no network permission. Profiles, audit records and " +
                    "containers stay on this device under /data/adb/doppelisk.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        item { SectionHeader("Limits") }
        item {
            ListGroup(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                LimitRow("Attestation", "Hardware-backed verdicts are not forged.")
                ListDivider(startIndent = 16.dp)
                LimitRow("System properties", "Read-only; the property store is not rewritten.")
            }
        }

        item {
            InfoNote(
                "Doppelisk changes what an app observes about the device it runs on. Use it " +
                    "only on hardware you own and only where that is lawful. It comes with no " +
                    "warranty; a misconfigured profile can stop an app from working.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (noticesOpen) {
        NoticesDialog(onDismiss = { noticesOpen = false })
    }
}

private data class BuildInfo(
    val versionName: String,
    val versionCode: String,
    val channel: String,
    val packageName: String,
)

private fun readBuild(context: Context): BuildInfo {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val debuggable = info?.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return BuildInfo(
        versionName = info?.versionName ?: "unknown",
        versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "unknown",
        channel = if (debuggable) "Debug" else "Release",
        packageName = context.packageName,
    )
}

private fun openUrl(context: Context, url: String) {
    // No browser or mail client installed: the value is still readable on screen.
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun AboutRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LimitRow(label: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            StatusLabel("Not spoofed", Palette.TextTertiary, dot = false)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

@Composable
private fun NoticesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("License and notices") },
        text = {
            Column {
                Text(
                    "Doppelisk is licensed under the Apache License, Version 2.0. " +
                        "The full text ships in the repository as LICENSE.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Bundled work: libphonenumber (Apache-2.0), libxposed API and " +
                        "service (Apache-2.0), AndroidX, Jetpack Compose, Kotlin and " +
                        "kotlinx.serialization (Apache-2.0), and operator data derived " +
                        "from mcc-mnc-list (MIT). The chassis catalog is compiled from " +
                        "vendor OTA metadata and published device specifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
