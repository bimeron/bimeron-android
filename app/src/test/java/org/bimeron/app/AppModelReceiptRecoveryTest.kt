package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppModelReceiptRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun cancelledReturnAndFailedReloadStillPublishAndConflictNeverCallsDone() = runBlocking {
        val store = ProfileStore(null, temp.newFolder(), SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        val text = Conf.fromKey("A", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
        val profile = ConfigDocument.parse(text).validate().copy(id = id.toString())
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        var failReload = false
        var conflict = false
        var done = 0
        val model = AppModelOperations(scope, { if (failReload) error("reload"); emptyList<Profile>() to false },
            { _, _ -> error("legacy save") }, { error("legacy delete") }, { null }, {}, MutableStateFlow(VpnState()), {}, {},
            saveCommitted = { _, _, _, publish ->
                val request = UUID.randomUUID()
                publishStoreCommit(store, request, operation = {
                    val result = store.compareAndSave(request, id, null, text, null)
                    if (result is CommitResult.Conflict) throw ProfileError(result.reason.name)
                }) { publish(profile) }
                false
            })
        try {
            model.refresh().join()
            lateinit var saving: Job
            store.renameProfile = { source, target -> source.renameTo(target).also { saving.cancel() } }
            failReload = true
            saving = model.save(text, null, "saved") { done++ }
            saving.join()
            assertTrue(saving.isCancelled)
            assertEquals(listOf(profile), model.profiles.value)
            assertTrue(model.warning.value)
            assertEquals(1, done)
            store.renameProfile = { _, _ -> error("conflict must precede rename") }
            model.save(text, null, "conflict") { conflict = true }.join()
            assertFalse(conflict)
            assertEquals(1, done)
            assertEquals("EXISTS", model.error.value)
        } finally { owner.cancelAndJoin() }
    }
}
