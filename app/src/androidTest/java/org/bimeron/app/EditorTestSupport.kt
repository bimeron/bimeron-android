package org.bimeron.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Deletes every stored profile named one of [names] through compare-and-delete with the revision
 * from [ProfileStore.load], including duplicates left behind by aborted runs, and fails loudly if
 * any remain. A conflict (another writer changed the file meanwhile) is retried with a fresh load.
 */
internal suspend fun ProfileStore.deleteFixturesNamed(vararg names: String) {
    repeat(3) {
        val leftovers = load().first.filter { it.name in names }
        if (leftovers.isEmpty()) return
        leftovers.forEach { compareAndDelete(UUID.randomUUID(), UUID.fromString(it.id), it.revision) }
    }
    val remaining = load().first.filter { it.name in names }
    check(remaining.isEmpty()) { "fixtures were not deleted: ${remaining.map { it.name to it.id }}" }
}
