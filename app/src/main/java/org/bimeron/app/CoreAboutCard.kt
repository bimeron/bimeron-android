package org.bimeron.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource

@Composable
internal fun CoreAboutCard() {
    val core = remember { CoreCapabilities.read() }
    Text(stringResource(R.string.about_text))
    Text(stringResource(R.string.core_version, core.version ?: stringResource(R.string.core_unknown)))
    Text(stringResource(R.string.core_platform, core.platform ?: stringResource(R.string.core_unknown)))
    val labels = listOf(R.string.core_vpn, R.string.core_proxy, R.string.core_ipv4, R.string.core_ipv6,
        R.string.core_ip_split, R.string.core_domain_split, R.string.core_geoip_split,
        R.string.core_geosite_split, R.string.core_app_split, R.string.core_kill_switch,
        R.string.core_daemon, R.string.core_ikev2)
    (CoreCapabilities.flags + "ikev2").zip(labels).forEach { (key, label) ->
        val status = when (core.support[key]) {
            true -> R.string.core_supported
            false -> R.string.core_unsupported
            null -> R.string.core_unknown
        }
        Text(stringResource(R.string.core_support_row, stringResource(label), stringResource(status)))
    }
    Text(stringResource(R.string.core_schema, core.geoDataSchema ?: stringResource(R.string.core_unknown)))
    Text(stringResource(R.string.core_build_note))
}
