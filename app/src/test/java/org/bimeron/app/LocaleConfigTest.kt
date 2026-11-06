package org.bimeron.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The per-app language list in Android 13+ system settings comes from the manifest's android:localeConfig,
 * which AGP generates from the app's resource dirs (androidResources.generateLocaleConfig). It must offer
 * exactly the languages the app is translated into: English (values/) and Russian (values-ru/).
 *
 * Robolectric does not expose ApplicationInfo's locale-config resource, so the reference is read from the
 * same merged manifest Robolectric loads and resolved through the app's compiled resources.
 * LocaleConfigInstrumentedTest checks the same contract through the platform LocaleConfig API on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleConfigTest {
    @Test fun manifestLocaleConfigListsExactlyEnglishAndRussian() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reference = mergedManifestApplicationAttribute("localeConfig")
        assertTrue("android:localeConfig must point to an xml resource: $reference", reference.startsWith("@xml/"))
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
        assertEquals(listOf("en", "ru"), locales)
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
    }
}
