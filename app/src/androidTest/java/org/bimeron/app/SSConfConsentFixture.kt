package org.bimeron.app

import android.content.Context
import android.net.VpnService
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only for the test-owned CI / coordinator-allocated isolated device. */
internal object SSConfConsentFixture {
    private const val target = "org.bimeron.app"
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SSConf app-op fixture").apply { isDaemon = true } }
        try {
            return executor.submit<String> {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }.get(5, TimeUnit.SECONDS).also {
                check(!it.contains("Error", ignoreCase = true) && !it.contains("Exception")) { "app-op command failed: $it" }
            }
        } finally {
            descriptor.close()
            executor.shutdownNow()
        }
    }
    private fun mode(): SSConfConsentMode =
        SSConfConsentMode.parse(shell("cmd appops get --user current $target ACTIVATE_VPN"))

    fun <T> withConsent(context: Context, prepared: Boolean, block: () -> T): T = synchronized(this) {
        check(context.packageName == target)
        val prior = mode()
        var failure: Throwable? = null
        try {
            val requested = if (prepared) "allow" else "deny"
            shell("cmd appops set --user current $target ACTIVATE_VPN $requested")
            assertEquals("Requested app-op must be applied", requested, mode().mode)
            assertEquals("Actual VPN consent must match fixture", prepared, VpnService.prepare(context) == null)
            block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                shell("cmd appops set --user current $target ACTIVATE_VPN ${prior.mode}")
                val restored = mode()
                assertEquals("Restore prior explicit mode or reported platform default", prior.mode, restored.mode)
                if (!prior.absent) {
                    assertEquals("Preserve an explicit entry, including explicit default", prior, restored)
                }
            } catch (restore: Throwable) {
                val original = failure
                if (original != null) original.addSuppressed(restore) else throw restore
            }
        }
    }
}
