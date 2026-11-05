package org.bimeron.app

/** An absent entry has only a reported effective default, not an explicit mode. */
internal data class SSConfConsentMode(val mode: String, val absent: Boolean) {
    companion object {
        private const val modes = "(allow|ignore|deny|default|foreground)"
        private const val duration = "(?:[+-]?(?:\\d+(?:ms|[dhms]))+|0)"
        private val explicit = Regex(
            "ACTIVATE_VPN: $modes(?:; (?:time|rejectTime)=$duration ago|; duration=$duration| \\(running\\))*"
        )
        private val absent = Regex("No operations\\.\\nDefault mode: $modes")

        fun parse(output: String): SSConfConsentMode {
            val text = output.trim()
            explicit.matchEntire(text)?.let { return SSConfConsentMode(it.groupValues[1], false) }
            absent.matchEntire(text)?.let { return SSConfConsentMode(it.groupValues[1], true) }
            error("Cannot safely restore ACTIVATE_VPN output: $output")
        }
    }
}
