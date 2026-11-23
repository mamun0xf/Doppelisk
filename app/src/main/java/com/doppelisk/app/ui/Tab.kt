package com.doppelisk.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The four top-level destinations. */
enum class Tab(val label: String, val icon: ImageVector) {
    Profiles("Profiles", Icons.Outlined.Person),
    Apps("Apps", Icons.Outlined.List),
    Audit("Audit", Icons.Outlined.CheckCircle),
    Settings("Settings", Icons.Outlined.Settings),
}
