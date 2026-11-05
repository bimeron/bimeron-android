package org.bimeron.app

import org.bimeron.core.androidcore.Androidcore
import org.json.JSONException
import org.json.JSONObject

internal data class CoreCapabilities(
    val version: String? = null,
    val platform: String? = null,
    val support: Map<String, Boolean?> = emptyMap(),
    val geoDataSchema: String? = null,
) {
    val canStartVpn: Boolean
        get() = platform == "android" && listOf("vpn", "ipv4", "ipv6").all { support[it] == true }

    companion object {
        val flags = listOf("vpn", "proxy", "ipv4", "ipv6", "ip_split", "domain_split",
            "geoip_split", "geosite_split", "app_split", "kill_switch", "daemon")

        fun read(): CoreCapabilities = read({ Androidcore.version() }, { Androidcore.capabilities() })

        internal fun read(version: () -> String, capabilities: () -> String): CoreCapabilities =
            parse(capabilityJson(version), capabilityJson(capabilities))

        internal fun capabilityJson(read: () -> String): String? = try { read() }
        catch (_: NoSuchMethodError) { null }
        catch (_: UnsatisfiedLinkError) { null }
        catch (error: NoClassDefFoundError) {
            // Only recover native initialization failures, not unrelated missing classes.
            if (generateSequence(error.cause) { it.cause }.any { it is UnsatisfiedLinkError }) null
            else throw error
        }

        fun parse(version: String?, capabilities: String?): CoreCapabilities {
            fun json(text: String?): JSONObject? = try { text?.let { JSONObject(it) } }
                catch (_: JSONException) { null }
            fun JSONObject?.string(key: String) = (this?.opt(key) as? String)?.takeIf { it.isNotBlank() }
            val caps = json(capabilities)
            return CoreCapabilities(json(version).string("version"), caps.string("platform"),
                flags.associateWith { caps?.opt(it) as? Boolean } +
                    ("ikev2" to (caps?.optJSONObject("ikev2")?.opt("supported") as? Boolean)), caps.string("geodata_schema"))
        }
    }
}
