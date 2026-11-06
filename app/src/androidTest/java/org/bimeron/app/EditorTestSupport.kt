package org.bimeron.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.util.UUID

/**
 * Reloads profiles and opens [id] through the production editor entry point, which pins the base
 * revision that editor saves and deletes require. Returns null when the profile is not stored.
 */
internal suspend fun AppModel.openStoredEditor(id: String): EditorDraft? {
    refresh().join()
    return withContext(Dispatchers.Main) { profiles.value.firstOrNull { it.id == id }?.let(::openEditor) }
}

/** Deletes a test fixture through the editor path and fails loudly if it is left behind. */
internal suspend fun AppModel.deleteStoredFixture(store: ProfileStore, id: String) {
    checkNotNull(openStoredEditor(id)) { "fixture $id is not stored" }
    delete(id) {}.join()
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
