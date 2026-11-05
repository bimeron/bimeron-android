package org.bimeron.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileStorePreferencesFailureTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun preferencesFailureStillInitializesStoreBeforeOpeningActions() = runBlocking {
        File(File(temp.root.canonicalFile, "datastore").apply { mkdir() }, "settings.preferences_pb").writeBytes(byteArrayOf(0xff.toByte()))
        val application = object : android.app.Application() {
            override fun getFilesDir() = temp.root.canonicalFile
            override fun getApplicationInfo() = ApplicationProvider.getApplicationContext<Context>().applicationInfo
            override fun getApplicationContext(): Context = this
            // AppModel resolves user-facing errors from string resources.
            override fun getResources() = ApplicationProvider.getApplicationContext<Context>().resources
        }
        val model = AppModel(application)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("model", model) }
        try {
            val refresh = model.refresh()
            withTimeout(5000) {
                while (!refresh.isCompleted) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(model.loading.value)
            assertNotNull(model.error.value)
            val token = withContext(Dispatchers.IO) {
                model.store.resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
            }
            assertTrue(token.file.exists())
        } finally { owner.clear() }
    }
}
