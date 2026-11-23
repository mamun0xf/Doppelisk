package com.doppelisk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ui.components.ConfirmDialog
import com.doppelisk.app.ui.fields.CarrierPresets
import com.doppelisk.app.ui.fields.PhoneNumbers
import com.doppelisk.app.ui.fields.ProfileDefaults
import com.doppelisk.app.ui.fields.ProfileDrafts
import com.doppelisk.app.ui.fields.ProfileFields
import com.doppelisk.app.ui.fields.RegionPresets
import com.doppelisk.app.ui.screens.AboutScreen
import com.doppelisk.app.ui.screens.AppBrowserScreen
import com.doppelisk.app.ui.screens.AppDetailScreen
import com.doppelisk.app.ui.screens.CustomDeviceScreen
import com.doppelisk.app.ui.screens.DevicePickerScreen
import com.doppelisk.app.ui.screens.ProfileCategoryScreen
import com.doppelisk.app.ui.screens.ProfileEditorScreen
import com.doppelisk.app.ui.screens.ProfilesHomeScreen
import com.doppelisk.app.ui.screens.ScopeListScreen
import com.doppelisk.app.ui.screens.SettingsScreen
import com.doppelisk.app.ui.screens.VerifyScreen
import com.doppelisk.app.ui.theme.Palette
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.JsonFormats

/**
 * The whole manager: four tabs, one app detail, and the editor with its
 * category and device-picker destinations.
 *
 * Navigation is explicit state rather than a navigation graph because the stack
 * is shallow and the interesting part is not the routes but the draft: a profile
 * being edited has to survive rotation, a trip to the device picker, and a back
 * gesture that is supposed to ask "discard this?" instead of closing a popup.
 */
@Composable
fun DoppeliskRoot(viewModel: ManagerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Profiles) }
    var detailPackage by rememberSaveable { mutableStateOf<String?>(null) }

    // A result banner describes the action that just happened on this screen;
    // carrying it to the next tab makes it read like a global state. Clear it
    // whenever the destination changes.
    LaunchedEffect(tab) {
        viewModel.clearTransient()
    }
    LaunchedEffect(detailPackage) {
        viewModel.clearTransient()
    }

    // The Audit tab shows one app's report, so choosing it must also load that
    // report. Previously only the App tab's "Open consistency check" set the
    // target; arriving from the bottom bar left the screen on "No observations"
    // even when a fresh audit existed.
    val auditTarget = state.selectedRow?.takeIf { it.hasDoppelisk }?.option?.packageName
        ?: state.doppeliskRows.firstOrNull()?.option?.packageName
    LaunchedEffect(tab) {
        if (tab == Tab.Audit) auditTarget?.let(viewModel::selectTarget)
    }
    LaunchedEffect(tab, auditTarget) {
        if (tab == Tab.Audit && auditTarget != null && state.selectedPackage != auditTarget) {
            viewModel.selectTarget(auditTarget)
        }
    }

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var editorIsNew by rememberSaveable { mutableStateOf(false) }
    var editorDraft by rememberSaveable(stateSaver = artifactSaver) {
        mutableStateOf<DoppeliskArtifact?>(null)
    }
    var editorBaseline by rememberSaveable(stateSaver = artifactSaver) {
        mutableStateOf<DoppeliskArtifact?>(null)
    }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }

    // null when closed; true shows the apps waiting for scope, false the ones
    // already hooked. Kept next to the tab state because it is a detail screen
    // of Settings, not a tab of its own.
    var scopeWaiting by rememberSaveable { mutableStateOf<Boolean?>(null) }

    // The About screen is a detail of Settings, like the scope lists.
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var pickerForPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var discardDialog by rememberSaveable { mutableStateOf(false) }

    val detailRow = state.rows.firstOrNull { it.option.packageName == detailPackage }
    val editorTarget = state.rows.firstOrNull { it.option.packageName == editorPackage }?.option

    val draft = editorDraft
    val baseline = editorBaseline
    val dirty = draft != null && draft != baseline

    fun closeEditor() {
        editorOpen = false
        editorPackage = null
        editorDraft = null
        editorBaseline = null
        editorIsNew = false
        categoryId = null
        discardDialog = false
        viewModel.clearEditorError()
    }

    fun requestCloseEditor() {
        if (dirty) discardDialog = true else closeEditor()
    }

    fun openEditor(target: ManagerRepository.TargetOption, artifact: DoppeliskArtifact, isNew: Boolean) {
        editorPackage = target.packageName
        editorDraft = artifact
        editorBaseline = if (isNew) null else artifact
        editorIsNew = isNew
        editorOpen = true
        categoryId = null
        pickerOpen = false
        pickerForPackage = null
        customOpen = false
    }

    fun startNewProfile(target: ManagerRepository.TargetOption) {
        pickerForPackage = target.packageName
        pickerOpen = true
    }

    fun applyChassis(chassis: Chassis) {
        val forPackage = pickerForPackage
        val current = editorDraft
        when {
            forPackage != null -> {
                val option = state.rows
                    .firstOrNull { it.option.packageName == forPackage }
                    ?.option
                    ?: return
                // A new profile starts with the numbers this phone's SIMs really
                // report. That is what a SIM-bound app compares against; when the
                // device exposes nothing, the numbers stay blank until a country
                // preset fills them.
                val draft = ProfileDrafts.newDraft(option, chassis)
                val real = state.device?.telephony?.phoneNumbers.orEmpty()
                openEditor(option, PhoneNumbers.seedFromDevice(draft, real), isNew = true)
            }

            current != null -> {
                editorDraft = ProfileDrafts.regenerate(current, chassis)
                pickerOpen = false
                customOpen = false
            }
        }
    }

    val backAction: (() -> Unit)? = when {
        customOpen -> ({ customOpen = false })
        pickerOpen -> ({
            pickerOpen = false
            pickerForPackage = null
        })

        editorOpen && categoryId != null -> ({ categoryId = null })
        editorOpen -> ({ requestCloseEditor() })
        scopeWaiting != null -> ({ scopeWaiting = null })
        aboutOpen -> ({ aboutOpen = false })
        detailRow != null -> ({ detailPackage = null })
        tab == Tab.Apps -> ({ tab = Tab.Profiles })
        else -> null
    }

    BackHandler(enabled = backAction != null) { backAction?.invoke() }

    val showChrome = !editorOpen && detailRow == null && scopeWaiting == null && !aboutOpen &&
        tab != Tab.Apps && !pickerOpen && !customOpen

    // Scroll positions live here, not in each screen, because every screen is a
    // branch of one `when`: switching branches destroyed the LazyColumn and the
    // reader came back to the top of a list they had already scrolled. One state
    // per screen — and per field category, so each opens where it was left —
    // keeps the position across navigation without changing what is shown.
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showChrome) {
                BottomTabs(tab) { tab = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                customOpen -> {
                    state.chassis.firstOrNull()?.let { base ->
                        CustomDeviceScreen(
                            base = base,
                            onBack = { customOpen = false },
                            onCreate = { chassis ->
                                applyChassis(chassis)
                                customOpen = false
                            },
                            listState = listState("custom-device"),
                        )
                    }
                }

                pickerOpen -> {
                    DevicePickerScreen(
                        chassis = state.chassis,
                        onBack = {
                            pickerOpen = false
                            pickerForPackage = null
                        },
                        onPick = { chassis -> applyChassis(chassis) },
                        onCustom = { customOpen = true },
                        listState = listState("device-picker"),
                    )
                }

                editorOpen && draft != null && editorTarget != null -> {
                    val realArtifact = state.device?.let {
                        ProfileDefaults.realArtifact(editorTarget, it)
                    }
                    val existingCount = state.rows
                        .firstOrNull { it.option.packageName == editorTarget.packageName }
                        ?.profiles
                        ?.count { !it.isDefault }
                        ?: 0
                    val screenInches = realArtifact?.identity?.display?.screenInches.orEmpty()

                    val categories = remember(draft, realArtifact, existingCount) {
                        ProfileFields.categoriesFor(
                            artifact = draft,
                            existingProfileCount = existingCount,
                            screenInches = screenInches,
                            realArtifact = realArtifact,
                        )
                    }

                    val category = categories.firstOrNull { it.id == categoryId }

                    if (category != null && categoryId != null) {
                        ProfileCategoryScreen(
                            category = category,
                            draft = draft,
                            onBack = { categoryId = null },
                            onCommitField = { key, value ->
                                editorDraft = ProfileFields.applyField(draft, key, value)
                            },
                            onResetField = { key ->
                                val real = realArtifact
                                if (real != null) {
                                    editorDraft = ProfileFields.resetFieldToDefault(
                                        artifact = draft,
                                        realArtifact = real,
                                        key = key,
                                        existingProfileCount = existingCount,
                                        screenInches = screenInches,
                                    )
                                }
                            },
                            onPickRegion = { preset ->
                                editorDraft = RegionPresets.apply(draft, preset)
                            },
                            onPickCarrier = { carrier ->
                                editorDraft = CarrierPresets.apply(draft, carrier)
                            },
                            realNumbers = state.device?.telephony?.phoneNumbers.orEmpty(),
                            listState = listState("category:" + draft.id + ":" + category.id),
                        )
                    } else {
                        ProfileEditorScreen(
                            target = editorTarget,
                            draft = draft,
                            categories = categories,
                            isNew = editorIsNew,
                            error = state.editorError,
                            saving = state.saveInProgress,
                            onDraftChange = { editorDraft = it },
                            onChangeDevice = {
                                pickerForPackage = null
                                pickerOpen = true
                            },
                            onSave = {
                                viewModel.saveProfile(draft, editorIsNew) { closeEditor() }
                            },
                            onBack = { requestCloseEditor() },
                            onOpenCategory = { categoryId = it },
                            listState = listState("editor:" + draft.id),
                        )
                    }
                }

                scopeWaiting != null -> {
                    val waiting = scopeWaiting == true
                    ScopeListScreen(
                        state = state,
                        waiting = waiting,
                        onBack = { scopeWaiting = null },
                        onRequestScope = { viewModel.requestScope(it) },
                        listState = listState(if (waiting) "scope-waiting" else "scope-active"),
                    )
                }

                aboutOpen -> AboutScreen(
                    onBack = { aboutOpen = false },
                    listState = listState("about"),
                )

                detailRow != null -> {
                    AppDetailScreen(
                        state = state,
                        row = detailRow,
                        onBack = { detailPackage = null },
                        onEdit = { artifact, isNew -> openEditor(detailRow.option, artifact, isNew) },
                        onNewProfile = { startNewProfile(detailRow.option) },
                        onActivate = { id, relaunch ->
                            viewModel.activateProfile(detailRow.option, id, relaunch)
                        },
                        onDelete = { id ->
                            viewModel.deleteProfile(detailRow.option, id) { }
                        },
                        onClearData = { id ->
                            viewModel.clearProfileData(detailRow.option, id) { }
                        },
                        onRequestScope = { viewModel.requestScope(detailRow.option) },
                        onRelaunch = {
                            viewModel.selectTarget(detailRow.option.packageName)
                            viewModel.relaunch()
                        },
                        onOpenAudit = {
                            viewModel.selectTarget(detailRow.option.packageName)
                            tab = Tab.Audit
                            detailPackage = null
                        },
                        listState = listState("detail:" + detailRow.option.packageName),
                    )
                }

                tab == Tab.Profiles -> ProfilesHomeScreen(
                    state = state,
                    onAdd = { tab = Tab.Apps },
                    onOpenApp = { detailPackage = it },
                    onOpenSettings = { tab = Tab.Settings },
                    listState = listState("profiles"),
                )

                tab == Tab.Apps -> AppBrowserScreen(
                    state = state,
                    onBack = { tab = Tab.Profiles },
                    onOpenApp = { detailPackage = it },
                    onNewProfile = { target -> startNewProfile(target) },
                    listState = listState("apps"),
                )

                tab == Tab.Audit -> VerifyScreen(
                    state = state,
                    onOpenApps = { tab = Tab.Apps },
                    onSelectTarget = { viewModel.selectTarget(it) },
                    onRelaunch = {
                        viewModel.relaunch()
                    },
                    listState = listState("audit"),
                )

                else -> SettingsScreen(
                    state = state,
                    onOpenScope = { waiting -> scopeWaiting = waiting },
                    onOpenAbout = { aboutOpen = true },
                    listState = listState("settings"),
                )
            }
        }
    }

    if (discardDialog) {
        ConfirmDialog(
            title = "Discard this draft?",
            text = if (editorIsNew) {
                "The profile has not been created yet."
            } else {
                "The edits to this profile have not been saved."
            },
            confirmLabel = "Discard",
            onConfirm = { closeEditor() },
            onDismiss = { discardDialog = false },
        )
    }
}

/**
 * Persists the in-progress draft across configuration changes.
 *
 * The profile is already a serializable document, so reusing its codec is both
 * the shortest and the most honest representation: what is restored is exactly
 * what would be written to disk.
 */
private val artifactSaver: Saver<DoppeliskArtifact?, String> = Saver(
    save = { artifact ->
        artifact?.let {
            runCatching {
                JsonFormats.profile.encodeToString(DoppeliskArtifact.serializer(), it)
            }.getOrDefault("")
        } ?: ""
    },
    restore = { raw ->
        if (raw.isEmpty()) {
            null
        } else {
            runCatching {
                JsonFormats.profile.decodeFromString(DoppeliskArtifact.serializer(), raw)
            }.getOrNull()
        }
    },
)

/**
 * The tab bar.
 *
 * It is a plain row rather than `NavigationBar` because the bar is 64dp tall
 * with the page background behind it and a single hairline on top, while the
 * Material component bakes in an 80dp height, a surface container and an
 * indicator pill.
 */
@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalDivider(color = Palette.OutlineSoft)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (entry in Tab.entries) {
                val active = entry == selected
                val tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Palette.TextTertiary
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(entry) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        color = tint,
                    )
                }
            }
        }
    }
}
