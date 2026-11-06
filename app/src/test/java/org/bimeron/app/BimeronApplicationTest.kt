package org.bimeron.app

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BimeronApplicationTest {
    @After fun reset() { VpnLive.resetAfterProcessStart(); AppEvents.clear() }

    @Test fun manifestApplicationResetsRuntimeWithoutClearingJournal() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(app is BimeronApplication)
        val token = VpnLive.gate.next()
        VpnLive.publishFailure(VpnErrorCode.NETWORK_ERROR, "network", "p", token)
        val journal = AppEvents.entries.value
        assertTrue(journal.isNotEmpty())
        (app as BimeronApplication).onCreate()
        assertEquals(VpnState(), VpnLive.state.value)
        assertFalse(VpnLive.gate.accepts(token))
        assertEquals(journal, AppEvents.entries.value)
    }

    @Test fun everyCodeHasEnglishAndRussianText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        fun localized(language: String) = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale(language)) })
        val en = localized("en")
        val ru = localized("ru")
        for (code in VpnErrorCode.entries) {
            assertTrue(code.name, en.getString(code.messageRes).isNotBlank())
            assertTrue(code.name, ru.getString(code.messageRes).isNotBlank())
            assertNotEquals(code.name, en.getString(code.messageRes), ru.getString(code.messageRes))
        }
    }
}
