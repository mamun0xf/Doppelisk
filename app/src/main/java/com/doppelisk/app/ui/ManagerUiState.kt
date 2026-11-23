package com.doppelisk.app.ui

import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.fields.DeviceSnapshot
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.DoppeliskIndex

/**
 * One profile as the app list shows it.
 *
 * [stored] distinguishes a profile that exists on disk from the implicit
 * `Default` row, which is always shown and is only materialised when the user
 * edits it. [device] is the model the profile pretends to be (`Google Pixel 8a`),
 * read from the artifact's build identity because the index only carries the
 * user's label.
 */
data class ProfileRow(
    val id: String,
    val label: String,
    val active: Boolean,
    val isDefault: Boolean,
    val stored: Boolean,
    val device: String? = null,
)

/**
 * One app plus its profiles.
 *
 * [inScope] is the framework's answer, not the app's own: a profile can be
 * assigned to a package that is not hooked, and the UI must be able to say so.
 */
data class TargetRow(
    val option: ManagerRepository.TargetOption,
    val inScope: Boolean,
    val assignedId: String?,
    val profiles: List<ProfileRow>,
    val defaultProfile: ProfileRow,
) {
    val hasDoppelisk: Boolean get() = profiles.isNotEmpty() || defaultProfile.stored

    val defaultActive: Boolean get() = defaultProfile.active

    val activeProfile: ProfileRow?
        get() = profiles.firstOrNull { it.active } ?: defaultProfile.takeIf { it.active }

    /** The `Default` row first, then every stored profile. */
    val displayProfiles: List<ProfileRow> get() = listOf(defaultProfile) + profiles

    /** Stored profiles for this app, counting a materialised `Default`. */
    val profileCount: Int get() = profiles.size + if (defaultProfile.stored) 1 else 0
}

/**
 * Everything the UI renders.
 *
 * It is one immutable object rather than a bag of flows so that a refresh is a
 * single atomic swap: the list never shows a mix of old rows and a new status.
 */
data class ManagerUiState(
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<TargetRow> = emptyList(),
    val chassis: List<Chassis> = emptyList(),
    val index: DoppeliskIndex? = null,
    val device: DeviceSnapshot? = null,
    val selectedPackage: String? = null,
    val audit: AuditReport? = null,
    val auditLoading: Boolean = false,
    val root: Boolean = false,
    val daemon: Boolean = false,
    val lsposed: Boolean = false,
    /**
     * False until the first status call returns. Until then the three flags above
     * are placeholders, so the UI must say "Checking…" instead of "Offline" —
     * otherwise every cold launch flashes three false failures.
     */
    val statusChecked: Boolean = false,
    /**
     * True for a short window after launch while root, the daemon and LSPosed
     * may still be coming up.
     *
     * A cold start used to finish the first status call before the root probe or
     * the Xposed binder had settled, so the screen said "Offline" for a couple of
     * seconds. Inside this window a not-yet-online check reads as "Checking…"
     * instead; the window ends as soon as everything is online or after a fixed
     * deadline, whichever comes first.
     */
    val startupGrace: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val busyAction: String? = null,
    val saveInProgress: Boolean = false,
    val editorError: String? = null,
    val lastRefreshMs: Long = 0L,
) {
    /** The selected app, falling back to the first app that has a profile. */
    val selectedRow: TargetRow?
        get() = rows.firstOrNull { it.option.packageName == selectedPackage }
            ?: rows.firstOrNull { it.hasDoppelisk }
            ?: rows.firstOrNull()

    /** Apps with at least one profile, for the Profiles tab. */
    val doppeliskRows: List<TargetRow> get() = rows.filter { it.hasDoppelisk }

    /** Total number of profiles, including materialised `Default`s. */
    val doppeliskCount: Int get() = rows.sumOf { it.profileCount }

    /** True when root, the daemon and the framework are all available. */
    val setupReady: Boolean get() = statusChecked && root && daemon && lsposed
}
