package org.bimeron.app

import android.app.Application
import android.app.LocaleManager
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Diagnostics deliberately never read node text or content descriptions. */
class LifecycleTestState : TestRule {
    var phase = "setup"
    var scenario: ActivityScenario<MainActivity>? = null
    var editorDiagnostics: (() -> String)? = null
    private var activityLocales = "unobserved"
    private var failedPhase: String? = null
    private var failureDetails: String? = null
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                captureFailure()
                throw AssertionError("${description.methodName}: phase=${failedPhase ?: phase} $failureDetails", failure)
            }
        }
    }

    fun captureFailure() {
        if (failedPhase != null) return
        failedPhase = phase
        failureDetails = runCatching {
            val nodes = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 12 || nodes.size >= 60) return
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                nodes += "windowID=${node.windowId} package=${node.packageName} bounds=$bounds visible=${node.isVisibleToUser} focused=${node.isFocused} accessibilityFocused=${node.isAccessibilityFocused} selected=${node.isSelected} scrollable=${node.isScrollable} depth=$depth class=${node.className} editable=${node.isEditable} clickable=${node.isClickable} enabled=${node.isEnabled} children=${node.childCount}"
                for (i in 0 until minOf(node.childCount, 60)) {
                    if (nodes.size >= 60) break
                    visit(node.getChild(i), depth + 1)
                }
            }
            val editorStatus = runCatching { editorDiagnostics?.invoke() }.getOrElse { "unavailable" }
            visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, 0)
            "resources=${app.resources.configuration.locales.toLanguageTags()} " +
                "requested=${if (Build.VERSION.SDK_INT >= 33) app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else "system"} " +
                "activityResources=$activityLocales scenario=${scenario?.state} editor=$editorStatus\nnodes (max 60, depth 12; values omitted):\n${nodes.joinToString("\n")}"
        }.getOrElse { "diagnostics unavailable: ${it.javaClass.name}" }
    }

    fun <T> step(name: String, block: () -> T): T {
        phase = name
        try { return block() } catch (failure: Throwable) {
            captureFailure()
            throw failure
        }
    }

    fun withLocale(tag: String, block: () -> Unit) {
        val previous = if (Build.VERSION.SDK_INT >= 33) app.getSystemService(LocaleManager::class.java).applicationLocales else app.resources.configuration.locales
        var failure: Throwable? = null
        try {
            setLocale(LocaleList.forLanguageTags(tag), "establish $tag")
            block()
        } catch (t: Throwable) {
            failure = t
            captureFailure()
            throw t
        } finally {
            val failedPhase = phase
            try { setLocale(previous, "restore locale") } catch (restore: Throwable) {
                if (failure != null) failure.addSuppressed(AssertionError("phase=restore locale", restore)) else throw restore
            } finally { if (failure != null) phase = failedPhase }
        }
    }

    @Suppress("DEPRECATION")
    private fun setLocale(locales: LocaleList, name: String) = step(name) {
        val expected = if (locales.isEmpty) android.content.res.Resources.getSystem().configuration.locales else locales
        if (Build.VERSION.SDK_INT >= 33) app.getSystemService(LocaleManager::class.java).applicationLocales = locales
        else InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.resources.updateConfiguration(android.content.res.Configuration(app.resources.configuration).apply { setLocales(locales) }, app.resources.displayMetrics)
        }
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val requestedMatches = Build.VERSION.SDK_INT < 33 || app.getSystemService(LocaleManager::class.java).applicationLocales == locales
            if (requestedMatches && app.resources.configuration.locales[0] == expected[0]) return@step
            Thread.sleep(100)
        }
        error("Application locale/resources did not settle")
    }

    fun awaitActivityLocale(tag: String, title: String) = step("verify activity resources $tag") {
        repeat(100) {
            var matches = false
            checkNotNull(scenario).onActivity {
                activityLocales = it.resources.configuration.locales.toLanguageTags()
                matches = it.resources.configuration.locales[0].language == tag && it.getString(R.string.profile_editor) == title
            }
            if (matches) {
                checkNotNull(scenario).onActivity {
                    assertEquals(tag, it.resources.configuration.locales[0].language)
                    assertEquals(title, it.getString(R.string.profile_editor))
                }
                return@step
            }
            Thread.sleep(100)
        }
        error("Activity locale/title did not settle")
    }
}
