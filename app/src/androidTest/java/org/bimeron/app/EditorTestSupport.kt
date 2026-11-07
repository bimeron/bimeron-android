package org.bimeron.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * The process-wide store that MainActivity's model and the service also use (contract D5: one production
 * store, the single writer). Its first creation runs the orphan sweep and must happen off Main.
 */
internal suspend fun sharedStore(context: Context): ProfileStore =
    withContext(Dispatchers.IO) { ProfileStore.get(context.applicationContext) }

/** The live activity's own model: the instance MainActivity's UI reads and the one a test should drive. */
internal fun ActivityScenario<MainActivity>.activityModel(): AppModel {
    var model: AppModel? = null
    onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
    return checkNotNull(model)
}

/**
 * [activityModel] once its initial load has finished. Tests that host an editor with their own content still
 * drive the activity's model; waiting for the load means the model's store is ready before a draft is
 * replaced or cleared (both dispose the old draft's resources through that store).
 */
internal fun ActivityScenario<MainActivity>.loadedActivityModel(): AppModel =
    activityModel().also { model -> runBlocking { model.awaitLoaded() } }

/**
 * Runs [body] with a model that no activity owns, for pure-model tests that involve no activity. The model
 * is created on Main in a test-owned [ViewModelStore] and cleared afterwards, as an activity would clear it.
 */
internal suspend fun <T> withDetachedModel(app: Application, body: suspend (AppModel) -> T): T {
    val owner = ViewModelStore()
    val model = withContext(Dispatchers.Main) {
        ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppModel::class.java]
    }
    return runThenCleanUp({ body(model) }) { withContext(Dispatchers.Main) { owner.clear() } }
}

/** Waits until [this] model's initial load (which also resolves the saved selection) has finished. */
internal suspend fun AppModel.awaitLoaded() {
    withTimeout(10_000) { loading.first { !it } }
}

/**
 * Reloads profiles and opens [id] through the production editor entry point, which pins the base
 * revision that editor saves and deletes require. Returns null when the profile is not stored.
 */
internal suspend fun AppModel.openStoredEditor(id: String): EditorDraft? {
    withContext(Dispatchers.Main) { refresh() }.join()
    return withContext(Dispatchers.Main) { profiles.value.firstOrNull { it.id == id }?.let(::openEditor) }
}

/** Deletes a test fixture through the editor path and fails loudly if it is left behind. */
internal suspend fun AppModel.deleteStoredFixture(store: ProfileStore, id: String) {
    checkNotNull(openStoredEditor(id)) { "fixture $id is not stored" }
    withContext(Dispatchers.Main) { delete(id) {} }.join()
    check(store.load().first.none { it.id == id }) { "fixture $id was not deleted: ${error.value}" }
}

/** Creates a fixture through the store's compare-and-save as a new profile and returns it as stored. */
internal suspend fun ProfileStore.createFixture(text: String): Profile {
    val id = UUID.randomUUID()
    val result = compareAndSave(UUID.randomUUID(), id, null, text)
    check(result is CommitResult.Committed) { "fixture was not saved: $result" }
    return load().first.single { it.id == id.toString() }.profile
}

/**
 * Deletes every stored profile whose name equals one of [names] exactly, through compare-and-delete
 * with the revision from [ProfileStore.load]. Matching is by exact name, not by id, because UI tests
 * locate rows by name: any leftover with that name, including duplicates from aborted runs whose ids
 * are unknown, would make those lookups ambiguous. Fails loudly if any remain. A conflict (another
 * writer changed the file meanwhile) is logged and retried with a fresh load.
 */
internal suspend fun ProfileStore.deleteFixturesNamed(vararg names: String) {
    repeat(3) {
        val leftovers = load().first.filter { it.name in names }
        if (leftovers.isEmpty()) return
        leftovers.forEach {
            val result = compareAndDelete(UUID.randomUUID(), UUID.fromString(it.id), it.revision)
            if (result !is CommitResult.Committed) Log.w("TestFixtures", "delete of '${it.name}' ${it.id} not committed: $result")
        }
    }
    val remaining = load().first.filter { it.name in names }
    check(remaining.isEmpty()) { "fixtures were not deleted: ${remaining.map { it.name to it.id }}" }
}

/**
 * Runs [body], then [cleanup]. If [body] fails, a cleanup failure is added to its exception as
 * suppressed instead of replacing it; a cleanup failure is thrown only when [body] succeeded.
 */
internal inline fun <T> runThenCleanUp(body: () -> T, cleanup: () -> Unit): T {
    val result = try {
        body()
    } catch (failure: Throwable) {
        try { cleanup() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
        throw failure
    }
    cleanup()
    return result
}
