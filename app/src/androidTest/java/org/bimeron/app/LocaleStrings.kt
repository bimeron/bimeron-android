package org.bimeron.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider

/**
 * Resolves app strings for an explicit locale, independent of the device or app locale,
 * so tests never hard-code translated UI text.
 */
object LocaleStrings {
    /** Locale that CI applies to the app before instrumentation (see the connected-test runner). */
    const val CI_LOCALE = "ru"

    fun context(tag: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return app.createConfigurationContext(config)
    }

    fun get(tag: String, id: Int, vararg args: Any): String = context(tag).getString(id, *args)
}
