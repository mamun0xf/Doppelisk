package com.doppelisk.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doppelisk.app.DoppeliskApp
import com.doppelisk.app.ManagerRepository
import com.doppelisk.app.ManagerRepository.TargetOption
import com.doppelisk.app.ui.fields.DeviceSnapshot
import com.doppelisk.app.ui.fields.ProfileDefaults
import com.doppelisk.core.model.AuditReport
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.DoppeliskIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The single source of truth for the UI.
 *
 * Every daemon call happens on [Dispatchers.IO] and every result is folded back
 * into one immutable [ManagerUiState]. The view model never blocks the main
 * thread on a socket, and it never leaves the UI half-updated after a failure:
 * an error becomes a field in the state, which the screen renders as a banner.
 */
class ManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ManagerUiState())
    val state: StateFlow<ManagerUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var auditJob: Job? = null
    private var messageJob: Job? = null
    private var errorJob: Job? = null

    /**
     * Artifact id to a human device label.
     *
     * The index deliberately stores only the user's label, so the home row's
     * "what is this pretending to be" line needs the artifact itself. The map
     * keeps that to one mailbox round trip per artifact rather than one per
     * refresh.
     */
    private val deviceLabels = mutableMapOf<String, String>()

    private val serviceListener: () -> Unit = { refreshScopes() }

    init {
        DoppeliskApp.addServiceListener(serviceListener)
        refresh()

        // The first probe can finish before `su` answers or the Xposed binder is
        // delivered, so the screen would briefly report failures that fix
        // themselves a second later. Keep saying "Checking…" for a short window
        // and re-check once at the end of it.
        viewModelScope.launch {
            delay(STARTUP_GRACE_MS)
            _state.update { it.copy(startupGrace = false) }
            if (!_state.value.setupReady) refresh(showSpinner = false)
        }
    }

    override fun onCleared() {
        DoppeliskApp.removeServiceListener(serviceListener)
        super.onCleared()
    }

    // ------------------------------------------------------------- refresh

    /** Reloads apps, index, chassis and device state. */
    fun refresh(showSpinner: Boolean = true) {
        if (refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            if (showSpinner) {
                _state.update { it.copy(refreshing = true, initialLoading = it.rows.isEmpty()) }
            }

            // A failure here must not leave the screen on "Loading identities…"
            // forever: an exception used to escape the coroutine, so the spinner
            // never cleared and the whole refresh vanished without a message.
            val result = withContext(Dispatchers.IO) { runCatching { loadSnapshot() } }
            val snapshot = result.getOrNull()

            _state.update { current ->
                if (snapshot == null) {
                    current.copy(
                        initialLoading = false,
                        refreshing = false,
                        statusChecked = true,
                        error = result.exceptionOrNull()?.message ?: "Could not load profiles",
                        lastRefreshMs = SystemClock.elapsedRealtime(),
                    )
                } else {
                    val lsposed = DoppeliskApp.isConnected()
                    current.copy(
                        initialLoading = false,
                        refreshing = false,
                        statusChecked = true,
                        rows = buildRows(snapshot.targets, snapshot.status.index),
                        chassis = snapshot.chassis,
                        index = snapshot.status.index,
                        device = snapshot.device,
                        root = snapshot.status.root,
                        daemon = snapshot.status.daemon,
                        lsposed = lsposed,
                        // Everything answered: there is nothing left to wait for.
                        startupGrace = current.startupGrace &&
                            !(snapshot.status.root && snapshot.status.daemon && lsposed),
                        error = snapshot.status.error,
                        lastRefreshMs = SystemClock.elapsedRealtime(),
                    )
                }
            }

            _state.value.selectedPackage?.let(::loadAudit)
        }
    }

    /** Re-reads only the framework scope, which changes without a daemon call. */
    fun refreshScopes() {
        val connected = DoppeliskApp.isConnected()
        _state.update { current ->
            current.copy(
                lsposed = connected,
                startupGrace = current.startupGrace &&
                    !(connected && current.root && current.daemon),
                rows = current.rows.map { row ->
                    row.copy(inScope = DoppeliskApp.isInScope(row.option.packageName))
                },
            )
        }
    }

    /** Called when the activity returns to the foreground. */
    fun onResume() {
        refreshScopes()

        val age = SystemClock.elapsedRealtime() - _state.value.lastRefreshMs
        if (_state.value.rows.isEmpty() || age > 5_000L) {
            refresh(showSpinner = false)
        } else {
            // Coming back from the target app is exactly when a fresh audit
            // exists, so re-read it even when the profile list is still fresh.
            _state.value.selectedPackage?.let(::loadAudit)
        }
    }

    fun selectTarget(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName, audit = null) }
        loadAudit(packageName)
    }

    // --------------------------------------------------------------- audit

    fun loadAudit(packageName: String) {
        auditJob?.cancel()

        auditJob = viewModelScope.launch {
            _state.update { it.copy(auditLoading = true) }

            val audit = withContext(Dispatchers.IO) { ManagerRepository.loadAudit(packageName) }

            _state.update { current ->
                if (current.selectedPackage == null || current.selectedPackage == packageName) {
                    current.copy(audit = audit, auditLoading = false)
                } else {
                    current.copy(auditLoading = false)
                }
            }
        }
    }

    // ------------------------------------------------------------- writing

    /** Validates and stores [artifact]. */
    fun saveProfile(
        artifact: DoppeliskArtifact,
        isNew: Boolean,
        onSuccess: () -> Unit,
    ) {
        if (_state.value.saveInProgress) return

        viewModelScope.launch {
            _state.update {
                it.copy(saveInProgress = true, editorError = null, busyAction = "Saving profile")
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ManagerRepository.validateProfile(getApplication(), artifact)
                    ManagerRepository.saveArtifact(getApplication(), artifact)
                }
            }

            _state.update {
                it.copy(
                    saveInProgress = false,
                    busyAction = null,
                    editorError = result.exceptionOrNull()?.message,
                )
            }
            postMessage(
                if (result.isSuccess) {
                    if (isNew) "Profile created" else "Profile saved"
                } else {
                    null
                }
            )

            if (result.isSuccess) {
                onSuccess()
                refresh(showSpinner = false)
            }
        }
    }

    fun clearEditorError() {
        _state.update { it.copy(editorError = null) }
    }

    // ---------------------------------------------------------- activating

    fun activateProfile(target: TargetOption, profileId: String, relaunchAfter: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = "Activating profile") }

            val ok = withContext(Dispatchers.IO) {
                ManagerRepository.activate(target.packageName, profileId)
            }

            _state.update { it.copy(busyAction = null) }
            if (ok) {
                postMessage("Profile activated")
            } else {
                postError("The daemon rejected the activation.")
            }

            if (ok && relaunchAfter) relaunchPackage(target.packageName)
            if (ok) refresh(showSpinner = false)
        }
    }

    fun deleteProfile(target: TargetOption, profileId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = "Deleting profile") }

            val result = withContext(Dispatchers.IO) {
                runCatching { ManagerRepository.deleteProfile(target.packageName, profileId) }
            }

            _state.update { it.copy(busyAction = null) }
            if (result.isSuccess) {
                postMessage("Profile deleted")
                onSuccess()
                refresh(showSpinner = false)
            } else {
                postError(result.exceptionOrNull()?.message ?: "Could not delete the profile.")
            }
        }
    }

    fun clearProfileData(target: TargetOption, profileId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = "Clearing profile data") }

            val result = withContext(Dispatchers.IO) {
                runCatching { ManagerRepository.clearProfileData(target.packageName, profileId) }
            }

            _state.update { it.copy(busyAction = null) }
            if (result.isSuccess) {
                postMessage("Profile data cleared")
                onSuccess()
            } else {
                postError(result.exceptionOrNull()?.message ?: "Could not clear the profile data.")
            }
        }
    }

    // ------------------------------------------------------------ transfer

    /**
     * Writes [id] to the document the user picked.
     *
     * The repository streams from the daemon's staging file straight into the
     * SAF document, so nothing here touches the profile payload itself.
     */
    fun exportProfile(id: String, destination: Uri, includeData: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = "Exporting profile") }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ManagerRepository.exportProfile(getApplication(), id, destination, includeData)
                }
            }

            _state.update { it.copy(busyAction = null) }
            if (result.isSuccess) {
                postMessage(if (includeData) "Profile and data exported" else "Profile exported")
            } else {
                postError(result.exceptionOrNull()?.message ?: "Could not export the profile.")
            }
        }
    }

    /**
     * Imports the document the user picked and reloads the list.
     *
     * The imported profile belongs to whatever app the file names, so it may
     * not be the app the user was looking at: a refresh is the only way the new
     * row appears.
     */
    fun importProfile(source: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = "Importing profile") }

            val result = withContext(Dispatchers.IO) {
                runCatching { ManagerRepository.importProfile(getApplication(), source) }
            }

            _state.update { it.copy(busyAction = null) }
            if (result.isSuccess) {
                postMessage("Profile imported")
                refresh(showSpinner = false)
            } else {
                postError(result.exceptionOrNull()?.message ?: "Could not import the profile.")
            }
        }
    }

    // -------------------------------------------------------------- scopes

    fun requestScope(target: TargetOption) {
        DoppeliskApp.requestScope(target.packageName) { approved, message ->
            if (approved) {
                postMessage("${target.label} added to scope")
            } else {
                postError(message ?: "The scope request failed.")
            }
            refreshScopes()
        }
    }

    /** Force-stops and restarts the selected app so new hooks take effect. */
    fun relaunch() {
        val packageName = _state.value.selectedRow?.option?.packageName ?: return
        viewModelScope.launch { relaunchPackage(packageName) }
    }

    private suspend fun relaunchPackage(packageName: String) {
        _state.update { it.copy(busyAction = "Relaunching app") }

        val ok = withContext(Dispatchers.IO) { ManagerRepository.relaunch(packageName) }

        _state.update { it.copy(busyAction = null) }
        if (ok) postMessage("App started") else postError("Could not restart the app.")
    }

    // -------------------------------------------------------------- banners

    /**
     * Shows a result message briefly, then clears it.
     *
     * A message used to live in the state until the next action replaced it, so
     * "Profile activated" followed the user to every screen indefinitely. Each
     * call cancels the previous timer, so the newest result always wins.
     */
    private fun postMessage(text: String?) {
        messageJob?.cancel()
        if (text == null) {
            _state.update { it.copy(message = null) }
            return
        }
        _state.update { it.copy(message = text) }
        messageJob = viewModelScope.launch {
            delay(2_200L)
            _state.update { current ->
                if (current.message == text) current.copy(message = null) else current
            }
        }
    }

    /**
     * Shows a failure briefly, then clears it.
     *
     * Errors used to be a field that only a later action replaced, so "Could not
     * restart the app" followed the user to every tab. The message now expires
     * on its own and is also dropped when the screen changes.
     */
    private fun postError(text: String?) {
        errorJob?.cancel()
        if (text == null) {
            _state.update { it.copy(error = null) }
            return
        }
        _state.update { it.copy(error = text) }
        errorJob = viewModelScope.launch {
            delay(ERROR_VISIBLE_MS)
            _state.update { current ->
                if (current.error == text) current.copy(error = null) else current
            }
        }
    }

    fun consumeMessage() {
        messageJob?.cancel()
        _state.update { it.copy(message = null) }
    }

    fun consumeError() {
        errorJob?.cancel()
        _state.update { it.copy(error = null) }
    }

    /** Drops any transient success/error banner; called on navigation. */
    fun clearTransient() {
        consumeMessage()
        consumeError()
    }

    // ------------------------------------------------------------ internals

    private data class Snapshot(
        val targets: List<TargetOption>,
        val status: ManagerRepository.Status,
        val chassis: List<Chassis>,
        val device: DeviceSnapshot,
    )

    private fun loadSnapshot(): Snapshot {
        val context = getApplication<Application>()
        val status = ManagerRepository.status(context)
        val chassis = ManagerRepository.chassis()

        status.index?.doppelisks?.values?.forEach { entry ->
            if (deviceLabels[entry.id] == null) {
                val build = ManagerRepository.loadProfile(entry.id)?.identity?.build
                if (build != null) {
                    deviceLabels[entry.id] = deviceLabel(build.manufacturer, build.model)
                }
            }
        }

        return Snapshot(
            targets = ManagerRepository.installedTargets(context),
            status = status,
            chassis = chassis,
            device = ProfileDefaults.snapshot(context, ManagerRepository.deviceReadings(), chassis),
        )
    }

    /**
     * Folds the index into one row per app.
     *
     * Labels come from [deviceLabels], which is filled once per artifact; the
     * index itself never has to be re-read.
     */
    private fun buildRows(
        targets: List<TargetOption>,
        index: DoppeliskIndex?,
    ): List<TargetRow> {
        val assignments = index?.assignments.orEmpty()
        val entries = index?.doppelisks.orEmpty()

        return targets.map { option ->
            val defaultId = ProfileDefaults.defaultId(option.packageName)

            val profiles = entries.values
                .filter { it.packageName == option.packageName && it.id != defaultId }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
                .map { entry ->
                    ProfileRow(
                        id = entry.id,
                        label = entry.label,
                        active = assignments[option.packageName] == entry.id,
                        isDefault = false,
                        stored = true,
                        device = deviceLabels[entry.id],
                    )
                }

            TargetRow(
                option = option,
                inScope = DoppeliskApp.isInScope(option.packageName),
                assignedId = assignments[option.packageName],
                profiles = profiles,
                defaultProfile = ProfileRow(
                    id = defaultId,
                    label = "Default",
                    active = assignments[option.packageName] == defaultId,
                    isDefault = true,
                    stored = entries.containsKey(defaultId),
                ),
            )
        }
    }

    private companion object {
        /** How long a cold start may keep saying "Checking…" while probes settle. */
        const val STARTUP_GRACE_MS = 6_000L

        /** How long a failure banner stays before it fades out on its own. */
        const val ERROR_VISIBLE_MS = 5_000L
    }
}


