package org.bimeron.app

import android.app.Application

/**
 * Process entry point. A new process never inherits a live session, so the runtime
 * state and attempt gate are reset here. The journal is owned by [JournalRuntime]
 * and is neither initialized nor cleared here.
 */
class BimeronApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnLive.resetAfterProcessStart()
    }
}
