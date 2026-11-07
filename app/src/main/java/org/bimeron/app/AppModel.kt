package org.bimeron.app

import android.app.Application
import android.content.Context
import android.content.Intent
import java.util.UUID
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Context.preferences by preferencesDataStore("settings")
private val themeKey = stringPreferencesKey("theme")
private val selectedKey = stringPreferencesKey("selected_profile")

class AppModel(application: Application) : AndroidViewModel(application) {
    private val storeReady = MutableStateFlow(false)
    @Volatile private var initializedStore: ProfileStore? = null
    val store: ProfileStore get() = checkNotNull(initializedStore) { "Profiles are loading" }
    private val commits = EditorCommits { store }
    private suspend fun loadProfiles(): Pair<List<Profile>, Boolean> {
        val ready = withContext(Dispatchers.IO) { ProfileStore.get(getApplication()) }
        initializedStore = ready
        storeReady.value = true
        val (stored, warning) = ready.load()
        commits.loaded(stored)
        return stored.map { it.profile } to warning
    }
    private val coordinator = AppModelOperations(
        scope = viewModelScope,
        load = { loadProfiles() },
        saveProfile = { text, id -> store.save(text, id) },
        saveDraft = { text, id, draft -> store.save(text, id, (draft as? EditorDraft)?.takeIf { it.profile.id == id && it.source.text == text }?.resources) },
        deleteProfile = { store.delete(it) },
        saveCommitted = { text, id, draft, publish -> commits.save(text, id, draft as? EditorDraft, publish) },
        deleteCommitted = { id, draft, publish -> commits.delete(id, draft as? EditorDraft, publish) },
        checkDelete = { id, draft -> commits.checkDelete(id, draft as? EditorDraft) },
        rebase = { id, text, draft -> commits.rebase(id, text, draft) },
        readLatest = { id -> store.readLatest(UUID.fromString(id)) },
        saveAsNewCommitted = { text, draft, publish -> commits.saveAsNew(text, draft, publish) },
        deleteLatest = { id, expected, publish -> commits.deleteAt(id, expected, publish) },
        readSelection = { application.preferences.data.first()[selectedKey] },
        writeSelection = { id ->
            application.preferences.edit { settings ->
                if (id == null) settings.remove(selectedKey) else settings[selectedKey] = id
            }
        },
        runtime = VpnLive.state,
        stop = {
            application.startService(Intent(application, BimeronVpnService::class.java).setAction(BimeronVpnService.STOP))
        },
        log = { AppEvents.add(it) },
        journal = { message, level -> AppEvents.add(message, level) },
        strings = { id, args -> application.string(id, args) },
    )
    var editorDraft: EditorDraft?
        get() = coordinator.editorDraft as EditorDraft?
        set(value) {
            val old = editorDraft
            coordinator.editorDraft = value
            if (old != null && old !== value) viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                old.resources.dispose(store.resources)
            }
        }
    /** The only production entry point for a new draft: it pins the revision the editor shows. */
    fun openEditor(profile: Profile): EditorDraft =
        EditorDraft(profile, commits.baseRevision(profile)).also { editorDraft = it }
    val profiles = coordinator.profiles
    val selected = coordinator.selected
    val pendingSwitch = coordinator.pendingSwitch
    val pendingApply = coordinator.pendingApply
    val loading = combine(coordinator.loading, storeReady) { loading, ready -> loading || !ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val warning = coordinator.warning
    val error = coordinator.error
    val busy = coordinator.busy
    val expanded = MutableStateFlow(false)
    var pendingPermissionId: String? = null
    val logs = AppEvents.entries
    val theme = application.preferences.data.map { it[themeKey] ?: "system" }

    override fun onCleared() {
        editorDraft = null
        super.onCleared()
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) = AppEvents.add(message, level)
    fun refresh() = coordinator.refresh()
    fun select(id: String) = coordinator.select(id)
    fun requestSelect(id: String) = coordinator.requestSelect(id)
    fun confirmSwitch(start: (String) -> Unit) = coordinator.confirmSwitch(start)
    fun cancelSwitch() = coordinator.cancelSwitch()
    fun clearPendingApply() = coordinator.clearPendingApply()
    fun applyPending(start: (String) -> Unit) = coordinator.applyPending(start)
    fun save(text: String, id: String?, event: String, configurationChanged: Boolean = false, done: () -> Unit) =
        coordinator.save(text, id, event, configurationChanged, done)
    fun delete(id: String, done: () -> Unit) = coordinator.delete(id, done)
    /** Replaces the open draft's text with the latest stored profile at its revision (after the user confirmed). */
    fun reloadLatest() = coordinator.reloadLatest()
    /** Saves the open draft as a new profile with a new id and its own copies of the SSH files. */
    fun saveAsNew(event: String, done: () -> Unit) = coordinator.saveAsNew(event, done)
    /** Deletes the open draft's profile at its latest revision after the user confirmed a delete conflict. */
    fun deleteAnyway(done: () -> Unit) = coordinator.deleteAnyway(done)
    fun clearError() = coordinator.clearError()


    fun toggleExpanded() { expanded.value = !expanded.value }
    fun collapse() { expanded.value = false }
    fun setTheme(value: String) = viewModelScope.launch { getApplication<Application>().preferences.edit { it[themeKey] = value } }

    fun addKey(name: String, key: String, done: () -> Unit) {
        val app = getApplication<Application>()
        try { save(Conf.fromKey(name, key), null, app.getString(R.string.profile_added), done = done) }
        catch (e: ProfileError) { error.value = e.text(app) }
    }

}

internal class AppModelOperations(
    private val scope: CoroutineScope,
    private val load: suspend () -> Pair<List<Profile>, Boolean>,
    private val saveProfile: suspend (String, String?) -> Profile,
    private val deleteProfile: suspend (String) -> Unit,
    private val readSelection: suspend () -> String?,
    private val writeSelection: suspend (String?) -> Unit,
    private val runtime: StateFlow<VpnState>,
    private val stop: () -> Unit,
    private val log: (String) -> Unit,
    private val saveDraft: (suspend (String, String?, Any?) -> Profile)? = null,
    private val saveCommitted: (suspend (String, String?, Any?, suspend (Profile) -> Unit) -> Unit)? = null,
    private val deleteCommitted: (suspend (String, Any?, suspend () -> Unit) -> Unit)? = null,
    // Cheap delete precondition run before stopping the VPN; the commit itself stays authoritative.
    private val checkDelete: (String, Any?) -> Unit = { _, _ -> },
    private val journal: (String, LogLevel) -> Unit = { message, _ -> log(message) },
    // Resolves user-visible string resources; the default (resource id) serves plain JVM tests.
    private val strings: (Int, Array<out Any>) -> String = { id, _ -> "#$id" },
    // Edit-conflict rebase: reads the latest stored profile and merges the draft onto it (null = not offered).
    private val rebase: (suspend (String, String, EditorDraft) -> EditorConflict?)? = null,
    private val readLatest: (suspend (String) -> LatestProfile)? = null,
    private val saveAsNewCommitted: (suspend (String, EditorDraft, suspend (Profile) -> Unit) -> Unit)? = null,
    // Compare-and-delete against an explicit revision ("Delete anyway"); null = not offered.
    private val deleteLatest: (suspend (String, ProfileRevision, suspend () -> Unit) -> Unit)? = null,
) {
    private fun resource(id: Int) = strings(id, emptyArray())

    // Bumped on every replacement so a draft set again later is still a different editor session.
    private var draftSession = 0L
    var editorDraft: Any? = null
        set(value) { if (field !== value) draftSession++; field = value }
    /**
     * True while the editor state captured when an operation was requested is still shown: same draft
     * object, same session and no further edits. Only then may a result close it or set its error.
     */
    private fun stillCurrent(draft: Any?): () -> Boolean {
        val session = draftSession
        val generation = (draft as? EditorDraft)?.editGeneration
        return { draftSession == session && editorDraft === draft && (draft as? EditorDraft)?.editGeneration == generation }
    }
    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val selected = MutableStateFlow<String?>(null)
    val pendingSwitch = MutableStateFlow<String?>(null)
    val pendingApply = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val warning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private var initialSelectionLoaded = false
    private val operations = Mutex()
    private var switchGeneration = 0L
    private var applySession: VpnState? = null
    private var busyCount = 0
    private var loadingCount = 0

    private fun operation(load: Boolean = false, work: suspend () -> Unit): kotlinx.coroutines.Job {
        synchronized(operations) {
            if (load) loadingCount++ else busyCount++
            loading.value = loadingCount > 0
            busy.value = busyCount > 0
        }
        return scope.launch { operations.withLock { work() } }.also { job ->
            job.invokeOnCompletion {
                synchronized(operations) {
                    if (load) loadingCount-- else busyCount--
                    loading.value = loadingCount > 0
                    busy.value = busyCount > 0
                }
            }
        }
    }

    private fun validApply(runtime: VpnState): Boolean =
        runtime.state in setOf("connected", "reconnecting") &&
            runtime.activeProfileId == pendingApply.value &&
            applySession?.activeProfileId == runtime.activeProfileId &&
            applySession?.connectedAtElapsed == runtime.connectedAtElapsed

    init {
        refresh()
        scope.launch {
            runtime.collect { runtime ->
                if (!validApply(runtime)) clearPendingApply()
            }
        }
    }


    fun refresh() = operation(load = true) {
        try {
            val (items, hasWarning) = load()
            val savedId = if (!initialSelectionLoaded) readSelection() else selected.value
            profiles.value = items
            if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
            warning.value = hasWarning
            val resolved = savedId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
            persistSelection(resolved)
            initialSelectionLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error.value = resource(R.string.profiles_load_failed)
        }
    }

    private suspend fun persistSelection(id: String?) {
        writeSelection(id)
        selected.value = id
    }

    fun select(id: String): kotlinx.coroutines.Job {
        cancelSwitch()
        return scope.launch {
            operations.withLock {
            try {
                if (profiles.value.any { it.id == id }) persistSelection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = resource(R.string.profile_select_failed)
            }
            }
        }
    }

    fun requestSelect(id: String) {
        cancelSwitch()
        if (profiles.value.none { it.id == id }) return
        val state = runtime.value
        if (state.state in setOf("permission", "connecting", "stopping")) return
        if (state.state in setOf("connected", "reconnecting") && state.activeProfileId != null) {
            if (id == selected.value) return
            pendingSwitch.value = id
        } else select(id)
    }

    fun cancelSwitch() { switchGeneration++; pendingSwitch.value = null }

    fun confirmSwitch(start: (String) -> Unit) {
        val id = pendingSwitch.value ?: return
        val generation = ++switchGeneration
        val session = runtime.value
        scope.launch { operations.withLock {
            try {
                if (generation != switchGeneration || pendingSwitch.value != id) return@withLock
                val profile = profiles.value.firstOrNull { it.id == id }
                if (profile == null || !sameModelSession(runtime.value, session)) {
                    cancelSwitch()
                    return@withLock
                }
                val previous = selected.value
                try {
                    persistSelection(id)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { persistSelection(previous) }
                    throw e
                }
                if (generation != switchGeneration || pendingSwitch.value != id ||
                    !sameModelSession(runtime.value, session)) {
                    withContext(NonCancellable) { persistSelection(previous) }
                    if (generation == switchGeneration) cancelSwitch()
                    return@withLock
                }
                pendingSwitch.value = null
                // An unsupported target (proxy mode or core KillSwitch = true) never starts, as on every other start path.
                if (profile.startsAsSystemVpn) start(id)
                else stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = resource(R.string.profile_switch_failed)
            }
        } }
    }

    fun clearPendingApply() { pendingApply.value = null; applySession = null }

    /**
     * Offers the reconnect confirmation when [id] is the active profile of a connected or reconnecting
     * session. The user applies it through [applyPending]; nothing reconnects here. Main-thread owned.
     */
    private fun offerApply(id: String): Boolean {
        val session = runtime.value
        if (session.state !in setOf("connected", "reconnecting") || session.activeProfileId != id) return false
        applySession = session
        pendingApply.value = id
        return true
    }
    fun applyPending(start: (String) -> Unit) {
        val id = pendingApply.value ?: return
        val valid = validApply(runtime.value)
        clearPendingApply()
        val profile = profiles.value.firstOrNull { it.id == id }
        if (valid && profile?.startsAsSystemVpn == true) start(id)
    }

    fun save(text: String, id: String?, event: String, configurationChanged: Boolean = false, done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft
        val current = stillCurrent(draft)
        // Creations (imports, added keys) never depend on whatever editor draft happens to be open.
        val editorBound = id != null && draft != null
        return operation {
            // A queued editor save whose draft was replaced or edited meanwhile is dropped, not committed.
            if (editorBound && !current()) return@operation
            error.value = null
            try {
                if (id != null && profiles.value.none { it.id == id })
                    throw ProfileError(if (editorBound) R.string.profile_deleted_elsewhere else R.string.profile_no_longer_exists)
                val publish = published(id, event, configurationChanged, { !editorBound || current() }, done)
                if (saveCommitted != null) saveCommitted.invoke(text, id, draft, publish)
                else publish(saveDraft?.invoke(text, id, draft) ?: saveProfile(text, id))
            } catch (e: ProfileError) {
                if (!editorBound || current()) error.value = e.text(strings)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
                if (e.isEditConflict) {
                    reloadAfterConflict()
                    if (editorBound) offerRebase(id!!, text, draft as? EditorDraft, current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!editorBound || current()) error.value = resource(R.string.profile_save_failed)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
            }
        }
    }

    /** Publication after a committed save: [id] null means a new profile, which is selected if the VPN is idle. */
    private fun published(id: String?, event: String, configurationChanged: Boolean, closes: () -> Boolean,
                          done: () -> Unit): suspend (Profile) -> Unit = { profile -> withContext(NonCancellable) {
        // Persistence is committed. Refresh/preferences failures must not report a failed save.
        profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
        try {
            val (items, hasWarning) = load()
            profiles.value = items
            if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
            warning.value = hasWarning
            val vpnBusy = runtime.value.state in setOf("permission", "connecting", "connected", "reconnecting", "stopping")
            if (id == null && !vpnBusy) persistSelection(profile.id)
            if (configurationChanged && id != null) offerApply(id)
        } catch (_: Exception) { warning.value = true }
        runCatching { log(event) }
        if (closes()) runCatching { done() }.onFailure { warning.value = true }
    } }

    /**
     * After an editor save conflicted: merges the draft onto the latest stored profile and shows the
     * conflict panel, only while the draft that saved is still shown unchanged. Never saves.
     */
    private suspend fun offerRebase(id: String, text: String, draft: EditorDraft?, current: () -> Boolean) {
        val rebase = rebase ?: return
        if (draft == null || !current()) return
        val conflict = try { rebase(id, text, draft) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (conflict == null || !current()) return
        draft.conflict = conflict
        error.value = resource(conflictMessage(conflict))
    }

    private fun conflictMessage(conflict: EditorConflict) = when {
        conflict !is EditorConflict.Gone -> R.string.profile_conflict_review
        conflict.deleted -> R.string.profile_deleted_elsewhere
        else -> R.string.profile_conflict_unreadable
    }

    /** [text] renamed to a free localized "Name (copy)" / "Name (copy n)" among the loaded profiles. */
    private fun asCopy(text: String): String {
        val document = try { ConfigDocument.parse(text) } catch (_: ProfileError) { return text }
        val name = copyProfileName(document.value("Profile", "Name"), profiles.value.map { it.name }.toSet()) { base, number ->
            if (number == 1) strings(R.string.profile_copy_name, arrayOf(base)) else strings(R.string.profile_copy_name_numbered, arrayOf(base, number))
        }
        return document.set("Profile", "Name", name).render()
    }

    /** Replaces the open draft with the latest stored profile at its revision; offers save-as-new if it is gone. */
    fun reloadLatest(): kotlinx.coroutines.Job {
        val draft = editorDraft as? EditorDraft
        val current = stillCurrent(draft)
        return operation {
            val read = readLatest
            if (draft == null || read == null || !current()) return@operation
            try {
                val latest = read(draft.profile.id)
                if (!current()) return@operation
                if (latest is LatestProfile.Present) {
                    draft.reload(latest.stored)
                    error.value = null
                } else {
                    val gone = EditorConflict.Gone(deleted = latest == LatestProfile.Deleted)
                    draft.conflict = gone
                    error.value = resource(conflictMessage(gone))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current()) error.value = resource(R.string.profiles_load_failed)
            }
        }
    }

    /**
     * Saves the open draft's text as a new profile (new id, own copies of its SSH files) and closes the
     * editor on success. The draft and its imports are left untouched when this fails.
     */
    fun saveAsNew(event: String, done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft as? EditorDraft
        val current = stillCurrent(draft)
        val text = draft?.source?.text
        return operation {
            val commit = saveAsNewCommitted
            if (draft == null || text == null || commit == null || !current()) return@operation
            error.value = null
            try {
                commit(asCopy(text), draft, published(null, event, false, current, done))
            } catch (e: ProfileError) {
                if (current()) error.value = e.text(strings)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current()) error.value = resource(R.string.profile_save_failed)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
            }
        }
    }

    fun delete(id: String, done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft
        val current = stillCurrent(draft)
        return operation {
            if (draft != null && !current()) return@operation
            error.value = null
            runDelete(id, draft, current, done, precheck = { checkDelete(id, draft) }) { publish ->
                if (deleteCommitted != null) deleteCommitted.invoke(id, draft, publish)
                else { deleteProfile(id); publish() }
            }
        }
    }

    /**
     * "Delete anyway" after a delete conflict: re-reads the latest revision and compare-and-deletes against
     * it, stopping an active VPN first. Another change in between shows the confirmation again; a profile
     * already deleted counts as done.
     */
    fun deleteAnyway(done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft as? EditorDraft
        val current = stillCurrent(draft)
        return operation {
            val read = readLatest
            val commit = deleteLatest
            if (draft == null || read == null || commit == null || !current()) return@operation
            val id = draft.profile.id
            error.value = null
            draft.conflict = null
            runDelete(id, draft, current, done, precheck = {}) { publish ->
                when (val latest = read(id)) {
                    is LatestProfile.Present -> commit(id, latest.stored.revision, publish)
                    LatestProfile.Deleted -> throw ProfileError(R.string.profile_deleted_elsewhere)
                    LatestProfile.Unreadable -> throw ProfileError(R.string.profile_edit_conflict)
                }
            }
        }
    }

    private suspend fun runDelete(id: String, draft: Any?, current: () -> Boolean, done: () -> Unit, precheck: () -> Unit,
                                  commit: suspend (suspend () -> Unit) -> Unit) {
        val publish: suspend () -> Unit = { withContext(NonCancellable) {
            profiles.value = profiles.value.filterNot { it.id == id }
            try {
                val (items, hasWarning) = load()
                profiles.value = items
                if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
                warning.value = hasWarning
                if (selected.value == id || items.none { it.id == selected.value }) persistSelection(items.firstOrNull()?.id)
                if (pendingApply.value == id) pendingApply.value = null
            } catch (_: Exception) { warning.value = true }
            runCatching { log(resource(R.string.journal_profile_deleted)) }
            if (current()) runCatching { done() }.onFailure { warning.value = true }
        } }
        try {
            precheck()
            val vpn = runtime.value
            if (deleteStopsVpn(vpn, id)) {
                stop()
                // The pre-stop value may itself be "failed" (a failed attempt still names the profile): only a
                // failure published after it is the stop's result. No drop(1): a quick "disconnected" counts.
                val stopped = withTimeoutOrNull(15_000) {
                    runtime.first { it.state == "disconnected" || (it.state == "failed" && it != vpn) }
                }
                if (stopped?.state != "disconnected") throw ProfileError(R.string.profile_delete_vpn_stop_failed)
            }
            commit(publish)
        } catch (e: ProfileError) {
            if (draft == null || current()) error.value = e.text(strings)
            journal(resource(R.string.journal_profile_delete_error), LogLevel.ERROR)
            if (e.isEditConflict) {
                reloadAfterConflict()
                if (draft is EditorDraft) offerDeleteAnyway(id, e, draft, current, publish)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (draft == null || current()) error.value = resource(R.string.profile_delete_failed)
            journal(resource(R.string.journal_profile_delete_error), LogLevel.ERROR)
        }
    }

    /**
     * After an editor delete conflicted: a profile deleted elsewhere is what the user asked for, so the
     * delete counts as done; a changed one asks to delete anyway; an unreadable one keeps the error.
     */
    private suspend fun offerDeleteAnyway(id: String, e: ProfileError, draft: EditorDraft, current: () -> Boolean,
                                          publish: suspend () -> Unit) {
        val read = readLatest ?: return
        if (deleteLatest == null || !current()) return
        val latest = if (e.resId == R.string.profile_deleted_elsewhere) LatestProfile.Deleted
            else try { read(id) } catch (x: CancellationException) { throw x } catch (_: Exception) { return }
        if (!current()) return
        when (latest) {
            LatestProfile.Deleted -> { error.value = null; publish() }
            is LatestProfile.Present -> { error.value = null; draft.conflict = EditorConflict.ChangedBeforeDelete }
            LatestProfile.Unreadable -> Unit
        }
    }

    fun clearError() { error.value = null }

    /**
     * After a conflict the profile changed through another path (possibly another model instance on the
     * same store), so reload what reopening the editor pins. Never touches the open editor draft.
     */
    private suspend fun reloadAfterConflict() = withContext(NonCancellable) {
        try {
            val (items, hasWarning) = load()
            profiles.value = items
            if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
            warning.value = hasWarning
            if (items.none { it.id == selected.value }) persistSelection(items.firstOrNull()?.id)
        } catch (_: Exception) { warning.value = true }
    }
}

/**
 * Whether deleting profile [id] stops the VPN first: it is the connected profile or the one a connection
 * attempt is for. The delete path and every delete confirmation's warning use this one predicate.
 */
internal fun deleteStopsVpn(vpn: VpnState, id: String): Boolean = id == vpn.activeProfileId || id == vpn.attemptProfileId

private val CORE_KILL_SWITCH_ON = Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$")

/**
 * Whether Android can start this profile as the system VPN: `Mode = vpn` and no core `KillSwitch = true`
 * (docs/PROFILE-EDITOR.md). Older imports of either kind still load but never start. The connect button,
 * Apply, the profile switch and the main page's "unsupported" state all use this one predicate.
 */
internal val Profile.startsAsSystemVpn: Boolean get() = mode == "vpn" && !CORE_KILL_SWITCH_ON.containsMatchIn(text)

internal fun sameModelSession(a: VpnState, b: VpnState): Boolean =
    a.state == b.state && a.activeProfileId == b.activeProfileId &&
        a.attemptProfileId == b.attemptProfileId && a.connectedAtElapsed == b.connectedAtElapsed

/** Recover commits even when dispatching the operation's return observes cancellation. */
internal suspend fun publishStoreCommit(store: ProfileStore, requestId: UUID,
    operation: suspend () -> Unit, publish: suspend (CommitReceipt) -> Unit) {
    try { operation() }
    finally { withContext(NonCancellable) { store.committedReceipt(requestId)?.let { publish(it) } } }
}

/**
 * Editor commits are compare-and-swap against the revision the draft was opened at (contract §8.3/§8.4),
 * never against the revision current at save time, so A->B->A changes elsewhere are detected.
 * Updates and deletes need the editor's draft for that id, unchanged since the request, with a base
 * revision; otherwise they conflict. Only creation (id == null) commits with expected == null.
 * A conflict throws [R.string.profile_edit_conflict] ([R.string.profile_deleted_elsewhere] when the
 * profile is gone) and leaves the draft and its imported SSH resources untouched; AppModelOperations
 * then reloads so that reopening pins the latest revision, and offers [rebase] for the draft. Only the
 * user applying that merge (or reloading the latest profile) advances the draft's base.
 * After a successful save the editor closes; if it stays open (stale result), its base is not
 * advanced: every commit takes a new store revision, so reusing the old base conflicts.
 * Main-thread owned.
 */
internal class EditorCommits(private val store: () -> ProfileStore) {
    private val publications = ProfileReceiptPublications()
    private val stored = mutableMapOf<String, StoredProfile>()

    fun loaded(items: List<StoredProfile>) {
        stored.clear()
        items.forEach { stored[it.id] = it }
    }

    /** The revision [profile] was loaded at, or null when the shown profile is not the stored one. */
    fun baseRevision(profile: Profile): ProfileRevision? = stored[profile.id]?.takeIf { it.profile == profile }?.revision

    private fun conflict(reason: ConflictReason? = null) = ProfileError(
        if (reason == ConflictReason.DELETED) R.string.profile_deleted_elsewhere else R.string.profile_edit_conflict)

    /** Fails early, before the VPN is stopped, when a delete from [draft] would certainly conflict. */
    fun checkDelete(id: String, draft: EditorDraft?) { base(id, draft, null) }

    private fun base(id: String, draft: EditorDraft?, text: String?): ProfileRevision {
        if (draft == null || draft.profile.id != id || (text != null && draft.source.text != text)) throw conflict()
        return draft.baseRevision ?: throw conflict()
    }

    suspend fun save(text: String, id: String?, draft: EditorDraft?, publish: suspend (Profile) -> Unit) {
        val expected = id?.let { base(it, draft, text) }
        val resources = if (id != null) draft?.resources else null
        val store = store()
        val uuid = id?.let(UUID::fromString) ?: UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val normalized = withContext(Dispatchers.IO) { resources?.resolve(text, store.resources) ?: text }
        val profile = ConfigDocument.parse(normalized).validate().copy(id = uuid.toString())
        publishStoreCommit(store, requestId, operation = {
            val result = store.compareAndSave(requestId, uuid, expected, normalized, resources)
            if (result is CommitResult.Conflict) throw conflict(result.reason)
        }) { receipt ->
            if (publications.accept(receipt, stored[profile.id]?.revision)) {
                stored[profile.id] = StoredProfile(profile, receipt.revision!!, receipt.digest!!)
                publish(profile)
            }
        }
    }

    /**
     * Reads the latest stored [id] with its revision (one read under the store lock) and rebases [text],
     * the draft's text, onto it from the draft's base text. Null if [text] is not this draft's text.
     */
    suspend fun rebase(id: String, text: String, draft: EditorDraft): EditorConflict? {
        if (draft.profile.id != id || draft.source.text != text) return null
        val base = draft.profile.text
        return when (val latest = store().readLatest(UUID.fromString(id))) {
            is LatestProfile.Present -> EditorConflict.Changed(latest.stored, text,
                withContext(Dispatchers.Default) { ProfileMerge.merge(base, text, latest.stored.text) })
            LatestProfile.Deleted -> EditorConflict.Gone(deleted = true)
            LatestProfile.Unreadable -> EditorConflict.Gone(deleted = false)
        }
    }

    /**
     * Creates [text] as a new profile. Its SSH files are copied into a new draft for the new id
     * ([SshDraftResources.copyTo]), handed to the profile by the commit and otherwise released here; the
     * editor's own draft keeps its imports until it is disposed.
     */
    suspend fun saveAsNew(text: String, draft: EditorDraft, publish: suspend (Profile) -> Unit) {
        val store = store()
        val uuid = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val (copy, rewritten) = withContext(Dispatchers.IO) { draft.resources.copyTo(uuid, text, store.resources) }
        try {
            val normalized = withContext(Dispatchers.IO) { copy.resolve(rewritten, store.resources) }
            val profile = ConfigDocument.parse(normalized).validate().copy(id = uuid.toString())
            publishStoreCommit(store, requestId, operation = {
                val result = store.compareAndSave(requestId, uuid, null, rewritten, copy)
                if (result is CommitResult.Conflict) throw ProfileError(R.string.profile_save_failed)
            }) { receipt ->
                if (publications.accept(receipt, null)) {
                    stored[profile.id] = StoredProfile(profile, receipt.revision!!, receipt.digest!!)
                    publish(profile)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { copy.dispose(store.resources) }
        }
    }

    suspend fun delete(id: String, draft: EditorDraft?, publish: suspend () -> Unit) = deleteAt(id, base(id, draft, null), publish)

    /** Compare-and-delete of [id] against [expected]; a conflict throws like [delete]. */
    suspend fun deleteAt(id: String, expected: ProfileRevision, publish: suspend () -> Unit) {
        val store = store()
        val uuid = UUID.fromString(id)
        val requestId = UUID.randomUUID()
        publishStoreCommit(store, requestId, operation = {
            val result = store.compareAndDelete(requestId, uuid, expected)
            if (result is CommitResult.Conflict) throw conflict(result.reason)
        }) { receipt ->
            if (publications.accept(receipt, stored[id]?.revision, expected)) {
                stored.remove(id)
                publish()
            }
        }
    }
}

/**
 * The name for a profile saved as a copy: [label] (base, n) is "base (copy)" for n = 1 and "base (copy n)"
 * after, localized. The first label not in [taken] wins. The base name is shortened, never inside a
 * surrogate pair, so the result stays within [Conf.NAME_LIMIT] characters.
 */
internal fun copyProfileName(name: String, taken: Set<String>, label: (String, Int) -> String): String {
    val base = name.trim()
    var number = 1
    while (true) {
        var room = (Conf.NAME_LIMIT - label("", number).length).coerceAtLeast(0)
        if (room < base.length && room > 0 && Character.isHighSurrogate(base[room - 1])) room--
        val candidate = label(base.take(room).trimEnd(), number).trim()
        if (candidate !in taken) return candidate
        number++
    }
}

internal val ProfileError.isEditConflict
    get() = resId == R.string.profile_edit_conflict || resId == R.string.profile_deleted_elsewhere

/** Keep tombstones so a late save receipt cannot resurrect a deleted profile. Main-thread owned. */
internal class ProfileReceiptPublications {
    private data class Published(val revision: ProfileRevision, val deleted: Boolean)
    private val latest = mutableMapOf<UUID, Published>()
    private val requests = mutableSetOf<UUID>()
    fun accept(receipt: CommitReceipt, observed: ProfileRevision?, deletedBase: ProfileRevision? = null): Boolean {
        if (receipt.requestId in requests) return false
        val revision = receipt.revision ?: deletedBase ?: return false
        val previous = latest[receipt.profileId] ?: observed?.let { Published(it, false) }
        val deleted = receipt.kind == CommitReceipt.Kind.DELETED
        if (previous != null && (previous.revision.epoch != revision.epoch ||
            previous.revision.counter > revision.counter ||
            (previous.revision.counter == revision.counter && (!deleted || previous.deleted)))) return false
        requests.add(receipt.requestId)
        latest[receipt.profileId] = Published(revision, deleted)
        return true
    }
}
