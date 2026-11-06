package org.bimeron.app

internal object AuthTestSupport {
    const val SESSION = "0123456789abcdef0123456789abcdef0123456789abcdef"
    const val REQUEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val REQUEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val PROFILE = "8b0f0d52-5d1e-4c0f-9a4e-3f7b2d2a1c11"

    fun wire(requestId: String, session: String = SESSION) =
        """{"sessionId":"$session","requestId":"$requestId","kind":"password","prompts":["Password:"],"echo":[false]}"""

    /** Fresh attempt in [state], as the service publishes it. */
    fun attempt(state: String = "connecting"): Long {
        VpnLive.resetAfterProcessStart()
        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState(state, attemptProfileId = PROFILE, activeProfileId = PROFILE.takeIf { state != "connecting" }), token)
        return token
    }
}

/** Scripted Core auth surface; records calls but keeps no answer text beyond the call. */
internal class FakeCorePort(var pending: String? = "null") : CoreSessionPort {
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    /** Runs inside cancelAuth before Core's result, to interleave a status tick with the cancel. */
    var duringCancel: () -> Unit = {}
    override fun pendingAuth(): String? = pending
    override fun replyAuth(sessionId: String, requestId: String, answers: String) {
        failWith?.let { throw it }
        calls += "reply:$requestId"
        pending = "null"
    }
    override fun cancelAuth(sessionId: String, requestId: String) {
        duringCancel()
        failWith?.let { throw it }
        calls += "cancel:$requestId"
        pending = "null"
    }
}
