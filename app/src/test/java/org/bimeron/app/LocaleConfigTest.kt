package org.bimeron.app

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The per-app language list in Android 13+ system settings comes from the manifest's android:localeConfig,
 * the hand-written res/xml/locales_config.xml. It lists regional tags (en-US, ru-RU) so that the system
 * picker offers each language in one step, without a region sub-picker.
 *
 * Robolectric does not expose ApplicationInfo's locale-config resource, so the reference is read from the
 * same merged manifest Robolectric loads and resolved through the app's compiled resources.
 * LocaleConfigInstrumentedTest checks the same contract through the platform LocaleConfig API on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleConfigTest {
    @Test fun manifestLocaleConfigListsExactlyUsEnglishAndRussianRussian() {
        assertEquals(listOf("en-US", "ru-RU"), localeConfigTags())
    }

    /**
     * defaultConfig.resourceConfigurations (the APK locale filter) and locales_config.xml are maintained
     * separately. A language only in the filters would ship translations nobody can pick; a language only
     * in locales_config.xml would be offered in settings while its translations are stripped from the APK.
     * build.gradle.kts passes the effective filter set to this test as a system property.
     */
    @Test fun localeFiltersAndLocaleConfigListTheSameLanguages() {
        val property = System.getProperty(RESOURCE_CONFIGURATIONS_PROPERTY)
        assertNotNull("$RESOURCE_CONFIGURATIONS_PROPERTY is not set; run through Gradle (app/build.gradle.kts)", property)
        val filters = property!!.split(',').filter { it.isNotBlank() }
        // resourceConfigurations may also hold density/other qualifiers; only locale entries matter here.
        val filterLanguages = filters.mapNotNull(::languageOfResourceConfiguration).toSortedSet()
        assertTrue("no locale in resourceConfigurations: $filters", filterLanguages.isNotEmpty())
        val configLanguages = localeConfigTags().map { Locale.forLanguageTag(it).language }.toSortedSet()
        assertEquals(
            "locale filter languages (app/build.gradle.kts appLocaleFilters) and res/xml/locales_config.xml languages differ",
            filterLanguages,
            configLanguages,
        )
    }

    /**
     * values/ is the default, unqualified English and values-ru/ is Russian; there are no region-qualified
     * dirs. So the listed tags (en-US, ru-RU) and any region a user stored with the earlier bare-language
     * list (for example en-GB or ru-KG picked through the region sub-picker) resolve to the same strings.
     */
    @Test fun regionalTagsResolveToLanguageResources() {
        val english = "No profiles yet"
        val russian = stringFor("ru")
        assertNotEquals("values-ru/ must translate profiles_empty", english, russian)
        for (tag in listOf("en-US", "en-GB", "en")) assertEquals("app locale $tag", english, stringFor(tag))
        for (tag in listOf("ru-RU", "ru-KG")) assertEquals("app locale $tag", russian, stringFor(tag))
        // A language without translations falls back to the default, unqualified English resources.
        assertEquals("app locale de-DE", english, stringFor("de-DE"))
    }

    private fun stringFor(languageTags: String): String {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(languageTags)) }
        return app.createConfigurationContext(configuration).getString(R.string.profiles_empty)
    }

    private fun localeConfigTags(): List<String> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reference = mergedManifestApplicationAttribute("localeConfig")
        assertEquals("android:localeConfig must point to the hand-written list", "@xml/locales_config", reference)
        val id = app.resources.getIdentifier(reference.removePrefix("@xml/"), "xml", app.packageName)
        assertNotEquals("unresolved $reference", 0, id)

        val locales = mutableListOf<String>()
        app.resources.getXml(id).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    locales += parser.getAttributeValue(ANDROID_NS, "name")
                }
            }
        }
        return locales
    }

    private fun mergedManifestApplicationAttribute(name: String): String {
        val props = Properties().apply {
            ClassLoader.getSystemClassLoader().getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        val manifest = File(props.getProperty("android_merged_manifest"))
        assertTrue("merged manifest not found: ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        return application.getAttributeNS(ANDROID_NS, name)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val RESOURCE_CONFIGURATIONS_PROPERTY = "bimeron.resourceConfigurations"

        /** Language subtag of a resource-configuration locale ("en", "en-rUS", "b+sr+Latn"), or null for other qualifiers. */
        fun languageOfResourceConfiguration(value: String): String? {
            val language = when {
                value.startsWith("b+") -> value.removePrefix("b+").substringBefore('+')
                else -> value.substringBefore("-r")
            }
            return language.lowercase().takeIf { Regex("[a-z]{2,3}").matches(it) }
        }
    }
}
