package org.bimeron.app

import android.app.LocaleConfig
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android 13+ system per-app language settings must offer exactly English and Russian (see LocaleConfigTest). */
@RunWith(AndroidJUnit4::class)
class LocaleConfigInstrumentedTest {
    @Test fun platformLocaleConfigListsExactlyEnglishAndRussian() {
        assumeTrue("per-app language settings need API 33+", Build.VERSION.SDK_INT >= 33)
        val config = LocaleConfig(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        // The platform parses the list into a set, so its order carries no meaning.
        val locales = config.supportedLocales!!
        assertEquals(setOf("en", "ru"), (0 until locales.size()).map { locales[it].toLanguageTag() }.toSet())
    }
}
