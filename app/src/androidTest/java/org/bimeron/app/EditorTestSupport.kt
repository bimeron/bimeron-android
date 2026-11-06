package org.bimeron.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reloads profiles and opens [id] through the production editor entry point, which pins the base
 * revision that editor saves and deletes require. Returns null when the profile is not stored.
 */
internal suspend fun AppModel.openStoredEditor(id: String): EditorDraft? {
    refresh().join()
    return withContext(Dispatchers.Main) { profiles.value.firstOrNull { it.id == id }?.let(::openEditor) }
}
