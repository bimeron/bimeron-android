package org.bimeron.app

import android.animation.ValueAnimator
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val export: ProfileExport by viewModels()
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        export.complete(uri, applicationContext.contentResolver)
    }
    private fun launchExport() {
        if (!export.confirm()) return
        try { exportDocument.launch("bimeron-profile.conf") }
        catch (_: Exception) { export.fail() }
    }
    private var pendingImport: String? = null
    private var pendingName: String? = null
    private val keyDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = pendingImport; val name = pendingName
        pendingImport = null; pendingName = null
        if (uri == null || text == null || name == null) { model.error.value = getString(R.string.import_error); return@registerForActivityResult }
        lifecycleScope.launch {
            try { model.save(Conf.ensureName(Conf.inlineFile(text, model.store.readDocument(uri)), name), null, getString(R.string.profile_imported)) {} }
            catch (_: Exception) { model.error.value = getString(R.string.import_error) }
        }
    }
    private val confDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val text = model.store.readDocument(uri)
                val name = model.store.documentName(uri).removeSuffix(".conf")
                val parsed = Conf.parse(text, name)
                if (Regex("(?m)^\\s*File\\s*=").containsMatchIn(text)) {
                    pendingImport = text; pendingName = name
                    keyDocument.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                } else model.save(Conf.ensureName(text, parsed.name), null, getString(R.string.profile_imported)) {}
            } catch (e: ProfileError) { model.error.value = e.text(this@MainActivity) }
            catch (_: Exception) { model.error.value = getString(R.string.import_error) }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = model.pendingPermissionId
        model.pendingPermissionId = null
        if (result.resultCode == RESULT_OK && id != null && model.selected.value == id && VpnLive.state.value.state == "permission") startVpn(id)
        else if (VpnLive.state.value.state == "permission") {
            VpnLive.publish(failureState(VpnErrorCode.PERMISSION_DENIED, { getString(it) }), VpnLive.gate.next())
            model.log(getString(R.string.journal_vpn_permission_denied), LogLevel.WARNING)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        JournalRuntime.start(applicationContext)
        setContent { BimeronApp(model, export, ::launchExport, onImport = { confDocument.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, onPower = { toggleVpn() }, onSwitch = { startVpn(it) }, onApply = { startVpn(it, true) }) }
    }
    private fun toggleVpn() {
        val state = VpnLive.state.value.state
        if (state == "permission") {
            model.pendingPermissionId = null
            VpnLive.publish(VpnState(), VpnLive.gate.next())
            return
        }
        if (state in setOf("connected", "connecting", "reconnecting")) {
            startService(Intent(this, BimeronVpnService::class.java).setAction(BimeronVpnService.STOP))
            return
        }
        val profile = model.profiles.value.firstOrNull { it.id == model.selected.value } ?: return
        if (profile.mode != "vpn" || Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$").containsMatchIn(profile.text)) return
        val prepared = VpnService.prepare(this)
        if (prepared != null) {
            model.pendingPermissionId = profile.id
            VpnLive.publish(VpnState("permission", attemptProfileId = profile.id), VpnLive.gate.next())
            permission.launch(prepared)
        } else startVpn(profile.id)
    }
    private fun startVpn(id: String, restart: Boolean = false) {
        val intent = Intent(this, BimeronVpnService::class.java).setAction(BimeronVpnService.START).putExtra(BimeronVpnService.PROFILE, id).putExtra(BimeronVpnService.FORCE_RESTART, restart)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }
}

private val blue = Color(0xFF287BFF)
// Secondary text on background must stay at or above WCAG AA 4.5:1 (see A11yContrastTest).
internal const val MUTED_TEXT_ALPHA = .62f
internal val subtleTextLight = Color(0xFF596575)
internal val subtleTextDark = Color(0xFFB4BFCE)
internal val lightScheme = lightColorScheme(primary = Color(0xFF145DCD), onPrimary = Color.White, background = Color(0xFFF2F4F7), surface = Color.White, onSurface = Color(0xFF111820), onBackground = Color(0xFF111820), outline = Color(0xFFDFE5ED), secondaryContainer = Color(0xFFEAF2FF))
internal val darkScheme = darkColorScheme(primary = Color(0xFF9AC1FF), onPrimary = Color(0xFF202630), background = Color(0xFF202630), surface = Color(0xFF2A323F), onSurface = Color.White, onBackground = Color.White, outline = Color(0xFF414C5D), secondaryContainer = Color(0xFF293F60))
internal enum class Page { CONNECTION, JOURNAL, SETTINGS, ABOUT, ADD, EDIT }

@Composable
private fun BimeronApp(model: AppModel, export: ProfileExport, onExport: () -> Unit, onImport: () -> Unit, onPower: () -> Unit, onSwitch: (String) -> Unit, onApply: (String) -> Unit) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val selectedId by model.selected.collectAsStateWithLifecycle()
    val loading by model.loading.collectAsStateWithLifecycle()
    val warning by model.warning.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val logs by model.logs.collectAsStateWithLifecycle()
    val expanded by model.expanded.collectAsStateWithLifecycle()
    val pendingSwitch by model.pendingSwitch.collectAsStateWithLifecycle()
    val pendingApply by model.pendingApply.collectAsStateWithLifecycle()
    val vpn by VpnLive.state.collectAsStateWithLifecycle()
    val theme by model.theme.collectAsStateWithLifecycle("system")
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = theme == "dark" || (theme == "system" && systemDark)
    val activity = LocalContext.current as? ComponentActivity
    val chosen = profiles.firstOrNull { it.id == selectedId }
    var page by rememberSaveable { mutableStateOf(Page.CONNECTION) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var addChoice by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme) {
        SideEffect {
            activity?.window?.let { window ->
                window.statusBarColor = if (dark) android.graphics.Color.rgb(32, 38, 48) else android.graphics.Color.rgb(242, 244, 247)
                window.navigationBarColor = if (dark) android.graphics.Color.rgb(32, 38, 48) else android.graphics.Color.rgb(242, 244, 247)
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                AndroidView(factory = { context -> ImageView(context).apply { contentDescription = context.getString(R.string.bimeron_loading_accessibility); setImageResource(R.drawable.bimeron_loading_animation); if (ValueAnimator.areAnimatorsEnabled()) (drawable as? android.graphics.drawable.AnimationDrawable)?.start() else (drawable as? android.graphics.drawable.AnimationDrawable)?.selectDrawable(0) } }, modifier = Modifier.size(200.dp))
            }
            return@MaterialTheme
        }
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (wide && page != Page.EDIT) SideNav(page) { page = it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f)) {
                    when (page) {
                        Page.CONNECTION -> MainPage(chosen, profiles, vpn, expanded, warning, onPower, onAdd = { addChoice = true }, onToggle = model::toggleExpanded, onSelect = model::requestSelect, onEdit = { editingId = it; page = Page.EDIT })
                        Page.JOURNAL -> LogJournalPage(logs)
                        Page.SETTINGS -> SettingsPage(theme, onTheme = model::setTheme, onJournal = { page = Page.JOURNAL }, onAbout = { page = Page.ABOUT })
                        Page.ABOUT -> SimplePage(stringResource(R.string.about), { page = Page.SETTINGS }) { CoreAboutCard() }
                        Page.ADD -> AddPage(model, busy, error, onBack = { page = Page.CONNECTION }) { page = Page.CONNECTION }
                        Page.EDIT -> profiles.firstOrNull { it.id == editingId }?.let { ProfileEditor(model, it, busy, error, vpn, onBack = { page = Page.CONNECTION }) } ?: run { model.editorDraft = null; page = Page.CONNECTION }
                    }
                }
                if (page == Page.CONNECTION) TextButton(
                    onClick = { chosen?.let { export.request(it.text) } },
                    enabled = chosen != null && !export.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.export_selected_profile)) }
                if (!wide && page in setOf(Page.CONNECTION, Page.JOURNAL, Page.SETTINGS)) BottomNav(page) { page = it }
            }
        }
        }
        if (export.confirming) AlertDialog(
            onDismissRequest = export::cancel,
            title = { Text(stringResource(R.string.export_selected_profile)) },
            text = { Text(stringResource(R.string.export_warning)) },
            confirmButton = { TextButton(onClick = onExport) { Text(stringResource(R.string.export_selected_profile)) } },
            dismissButton = { TextButton(onClick = export::cancel) { Text(stringResource(R.string.cancel)) } }
        )
        export.message?.let { message ->
            AlertDialog(onDismissRequest = export::dismissMessage,
                text = { Text(stringResource(message)) },
                confirmButton = { TextButton(onClick = export::dismissMessage) { Text("OK") } })
        }
        if (addChoice) AlertDialog(onDismissRequest = { addChoice = false }, title = { Text(stringResource(R.string.add_profile)) }, text = { Column { Text(stringResource(R.string.supported_keys), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)); TextButton(onClick = { addChoice = false; model.clearError(); page = Page.ADD }) { Text(stringResource(R.string.paste_key)) }; TextButton(onClick = { addChoice = false; onImport() }) { Text(stringResource(R.string.import_conf)) } } }, confirmButton = { TextButton(onClick = { addChoice = false }) { Text(stringResource(R.string.cancel)) } })
        if (pendingSwitch != null) {
            val target = profiles.firstOrNull { it.id == pendingSwitch }
            AlertDialog(onDismissRequest = model::cancelSwitch, title = { Text(stringResource(R.string.switch_profile_title)) },
                text = { Text(stringResource(if (target?.mode == "vpn") R.string.switch_profile_message else R.string.switch_proxy_message, target?.name ?: "")) },
                confirmButton = { TextButton(onClick = { model.confirmSwitch(onSwitch) }) { Text(stringResource(if (target?.mode == "vpn") R.string.switch_profile_action else R.string.select_proxy_action)) } },
                dismissButton = { TextButton(onClick = model::cancelSwitch) { Text(stringResource(R.string.cancel)) } })
        }
        if (pendingApply != null) {
            val changed = profiles.firstOrNull { it.id == pendingApply }
            val canReconnect = changed?.mode == "vpn" && !Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$").containsMatchIn(changed.text)
            AlertDialog(onDismissRequest = model::clearPendingApply, title = { Text(stringResource(R.string.config_saved_title)) },
                text = { Text(stringResource(if (canReconnect) R.string.config_saved_message else R.string.config_unavailable_message)) },
                confirmButton = { if (canReconnect) TextButton(onClick = { model.applyPending(onApply) }) { Text(stringResource(R.string.reconnect_now)) } },
                dismissButton = { TextButton(onClick = model::clearPendingApply) { Text(stringResource(R.string.later)) } })
        }
        if (error != null && page !in setOf(Page.ADD, Page.EDIT)) AlertDialog(onDismissRequest = model::clearError, title = { Text(stringResource(R.string.vpn_failed)) }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = model::clearError) { Text("OK") } })
    }
}

@Composable internal fun BottomNav(page: Page, go: (Page) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).selectableGroup()) {
            NavItem(Page.CONNECTION, page, Icons.Default.PowerSettingsNew, stringResource(R.string.connection), go)
            NavItem(Page.JOURNAL, page, Icons.Default.History, stringResource(R.string.journal), go)
            NavItem(Page.SETTINGS, page, Icons.Default.Settings, stringResource(R.string.settings), go)
        }
    }
}

@Composable private fun RowScope.NavItem(dest: Page, current: Page, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, go: (Page) -> Unit) {
    val active = current == dest
    Column(Modifier.weight(1f).selectable(active, role = Role.Tab) { go(dest) }.heightIn(min = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = if (active) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        Spacer(Modifier.height(4.dp))
        // Two lines with hyphenation keep long labels readable at large font scales; one line at default size.
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, textAlign = TextAlign.Center, style = LocalTextStyle.current.copy(hyphens = Hyphens.Auto), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
    }
}

@Composable internal fun SideNav(page: Page, go: (Page) -> Unit) {
    Column(Modifier.widthIn(min = 88.dp).width(IntrinsicSize.Max).fillMaxHeight().selectableGroup().background(MaterialTheme.colorScheme.surface).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.bimeron_logo), stringResource(R.string.app_name), Modifier.size(36.dp))
        Spacer(Modifier.height(35.dp))
        listOf(Triple(Page.CONNECTION, Icons.Default.PowerSettingsNew, stringResource(R.string.connection)), Triple(Page.JOURNAL, Icons.Default.History, stringResource(R.string.journal)), Triple(Page.SETTINGS, Icons.Default.Settings, stringResource(R.string.settings))).forEach { (dest, icon, label) ->
            val active = page == dest
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp).background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp)).selectable(active, role = Role.Tab) { go(dest) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = if (active) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                Spacer(Modifier.height(5.dp))
                Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
        }
    }
}

@Composable internal fun PageHeader(title: String, onAdd: (() -> Unit)? = null, onBack: (() -> Unit)? = null, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }); if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA)) }
        if (onAdd != null) IconButton(onClick = onAdd, modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)) { Icon(Icons.Default.Add, stringResource(R.string.add_profile), tint = MaterialTheme.colorScheme.primary) }
    }
}
@Composable internal fun SectionTitle(value: String) { Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA), modifier = Modifier.padding(bottom = 12.dp).semantics { heading() }) }
@Composable internal fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Surface(shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), content = content) } }
@Composable private fun PageFrame(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(minOf(maxWidth, 900.dp)).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), content = content)
    }
}

@Composable internal fun MainPage(chosen: Profile?, profiles: List<Profile>, vpn: VpnState, expanded: Boolean, warning: Boolean, onPower: () -> Unit, onAdd: () -> Unit, onToggle: () -> Unit, onSelect: (String) -> Unit, onEdit: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val phone = maxWidth < 600.dp
        val sideBySide = maxWidth >= 850.dp
        Column(Modifier.width(minOf(maxWidth, 900.dp)).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = if (phone) 20.dp else 26.dp, vertical = if (phone) 10.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.bimeron_logo), null, Modifier.size(31.dp)); Spacer(Modifier.width(11.dp))
                Text("Bimeron", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
            }
            Spacer(Modifier.height(14.dp))
            if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { ConnectionCard(chosen, vpn, onPower); if (profiles.isNotEmpty()) { Spacer(Modifier.height(20.dp)); TrafficCard(vpn) } }
                Column(Modifier.weight(1f)) { CollapsibleProfiles(chosen, profiles, expanded, warning, vpn, onAdd, onToggle, onSelect, onEdit) }
            } else {
                ConnectionCard(chosen, vpn, onPower)
                Spacer(Modifier.height(20.dp))
                CollapsibleProfiles(chosen, profiles, expanded, warning, vpn, onAdd, onToggle, onSelect, onEdit)
                if (profiles.isNotEmpty()) { Spacer(Modifier.height(20.dp)); TrafficCard(vpn) }
            }
        }
    }
}

@Composable internal fun CollapsibleProfiles(chosen: Profile?, profiles: List<Profile>, expanded: Boolean, warning: Boolean, vpn: VpnState, onAdd: () -> Unit, onToggle: () -> Unit, onSelect: (String) -> Unit, onEdit: (String) -> Unit) {
    val locked = vpn.state in setOf("permission", "connecting", "stopping")
    val subColor = if (MaterialTheme.colorScheme.background == darkScheme.background) subtleTextDark else subtleTextLight
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val headingLabel = if (profiles.isEmpty()) stringResource(R.string.profiles) else stringResource(R.string.a11y_profiles_heading, profiles.size)
        Text(stringResource(R.string.profiles), fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading(); contentDescription = headingLabel })
        if (profiles.isNotEmpty()) {
            Text("  ${profiles.size}", fontSize = 11.sp, color = subColor, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            val addLabel = stringResource(R.string.add_profile)
            TextButton(onClick = onAdd, modifier = Modifier.semantics { contentDescription = addLabel }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Icon(Icons.Default.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.add), fontSize = 12.sp) }
        }
    }
    if (warning) Text(stringResource(R.string.read_warning), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        if (profiles.isEmpty()) {
            Column(Modifier.padding(horizontal = 17.dp, vertical = 19.dp)) {
                Text(stringResource(R.string.profiles_empty_hint), fontSize = 13.sp, lineHeight = 21.sp, color = subColor)
                Spacer(Modifier.height(18.dp))
                FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_profile)) }
            }
        } else {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    (if (expanded) profiles else listOfNotNull(chosen)).forEachIndexed { index, profile ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        val selected = profile.id == chosen?.id
                        val hoverSource = remember { MutableInteractionSource() }
                        val pickSource = remember { MutableInteractionSource() }
                        val editSource = remember { MutableInteractionSource() }
                        val editLabel = stringResource(R.string.configure_profile, profile.name)
                        val hovered by hoverSource.collectIsHoveredAsState()
                        val pickFocused by pickSource.collectIsFocusedAsState()
                        val editFocused by editSource.collectIsFocusedAsState()
                        val rowColor = when {
                            selected -> MaterialTheme.colorScheme.secondaryContainer
                            hovered -> lerp(MaterialTheme.colorScheme.surface, blue, .06f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(rowColor).hoverable(hoverSource), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 74.dp).then(if (pickFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier).selectable(selected = selected, enabled = !locked, interactionSource = pickSource, indication = null, role = Role.RadioButton) { onSelect(profile.id) }.padding(start = 16.dp, top = 17.dp, bottom = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(39.dp).background(if (selected) rowColor else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(if (profile.protocol.equals("VLESS", true)) R.drawable.profile_server else R.drawable.profile_layers), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(6.dp))
                                    Text(profileProtocol(profile), fontSize = 11.sp, lineHeight = 16.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Box(Modifier.width(48.dp).fillMaxHeight().then(if (editFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier).clickable(enabled = !locked, interactionSource = editSource, indication = null, role = Role.Button, onClick = { onEdit(profile.id) }).semantics { contentDescription = editLabel }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.profile_settings), null, tint = subColor, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                val disclosureState = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = disclosureState }.heightIn(min = 48.dp).padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (expanded) R.string.collapse else R.string.expand), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun profileProtocol(profile: Profile): String = when (profile.protocol.uppercase()) { "SS", "SSCONF" -> "Shadowsocks"; else -> profile.protocol }

@Composable internal fun ConnectionCard(chosen: Profile?, vpn: VpnState, onPower: () -> Unit) {
    val active = vpn.state in setOf("connected", "connecting", "reconnecting", "permission")
    val unsupported = chosen != null && (chosen.mode != "vpn" || Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$").containsMatchIn(chosen.text))
    val blocked = !active && (chosen == null || unsupported)
    val waiting = vpn.state in setOf("connecting", "reconnecting")
    val status = when {
        vpn.state == "connected" -> stringResource(R.string.vpn_connected)
        waiting -> stringResource(R.string.vpn_connecting)
        vpn.state == "permission" -> stringResource(R.string.vpn_permission)
        vpn.state == "stopping" -> stringResource(R.string.vpn_stopping)
        vpn.state == "failed" -> stringResource(R.string.vpn_failed)
        chosen == null -> stringResource(R.string.no_profiles_status)
        else -> stringResource(R.string.vpn_disconnected)
    }
    val hint = when {
        vpn.message != null -> vpn.message
        chosen == null -> stringResource(R.string.profiles_empty_hint)
        chosen.mode == "proxy" -> stringResource(R.string.vpn_proxy_explanation)
        unsupported -> stringResource(R.string.vpn_killswitch_explanation)
        vpn.state == "failed" -> stringResource(R.string.retry_hint)
        else -> null
    }
    val framed = LocalConfiguration.current.screenWidthDp >= 600
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), color = if (framed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background, border = if (framed) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val action = when {
                waiting || vpn.state == "permission" -> stringResource(R.string.cancel)
                vpn.state == "connected" -> stringResource(R.string.disconnect)
                vpn.state == "failed" -> stringResource(R.string.retry)
                else -> stringResource(R.string.connect)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(160.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().padding(10.dp).border(1.dp, blue.copy(alpha = .15f), CircleShape))
                FilledIconButton(onClick = onPower, enabled = !blocked && vpn.state != "stopping", modifier = Modifier.size(116.dp).semantics { contentDescription = action; stateDescription = status }, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (vpn.state == "connected") blue else MaterialTheme.colorScheme.surface,
                        contentColor = if (vpn.state == "connected") Color.White else blue, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = blue.copy(alpha = .4f))) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(43.dp))
                        if (waiting) {
                            if (ValueAnimator.areAnimatorsEnabled()) CircularProgressIndicator(Modifier.size(88.dp), color = blue, strokeWidth = 3.dp)
                            else CircularProgressIndicator(progress = { .75f }, modifier = Modifier.size(88.dp), color = blue, strokeWidth = 3.dp)
                        }
                    }
                }
            }
            // Visual caption of the power button; the button itself carries the same label for TalkBack.
            Text(action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), modifier = Modifier.clearAndSetSemantics {})
            if (hint != null) { Spacer(Modifier.height(8.dp)); Text(hint, style = MaterialTheme.typography.bodySmall, color = if (vpn.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
@Composable private fun bytes(value: Long): String = when { value < 1024 -> stringResource(R.string.unit_bytes, value); value < 1024 * 1024 -> stringResource(R.string.unit_kilobytes, value / 1024.0); else -> stringResource(R.string.unit_megabytes, value / 1048576.0) }
@Composable internal fun TrafficCard(vpn: VpnState) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(vpn.state, vpn.connectedAtElapsed) {
        while (vpn.state == "connected") { now = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }
    val seconds = if (vpn.state == "connected" && vpn.connectedAtElapsed > 0) ((now - vpn.connectedAtElapsed) / 1000).coerceAtLeast(0) else 0
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(bottom = 9.dp)) { Text(stringResource(R.string.session_traffic), modifier = Modifier.semantics { heading() }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (MaterialTheme.colorScheme.background == darkScheme.background) subtleTextDark else subtleTextLight) }
            if (vpn.state == "connected") {
                val elapsed = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                val elapsedLabel = stringResource(R.string.a11y_session_duration, elapsed)
                Text(elapsed, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = elapsedLabel })
            }
        }
        Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            val vertical = LocalConfiguration.current.fontScale >= 1.3f
            if (vertical) Column {
                Box(Modifier.padding(horizontal = 17.dp, vertical = 14.dp)) { Metric(stringResource(R.string.received), vpn.received, Icons.Default.ArrowDownward) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Box(Modifier.padding(horizontal = 17.dp, vertical = 14.dp)) { Metric(stringResource(R.string.sent), vpn.sent, Icons.Default.ArrowUpward) }
            } else Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.weight(1f).padding(horizontal = 17.dp, vertical = 14.dp)) { Metric(stringResource(R.string.received), vpn.received, Icons.Default.ArrowDownward) }
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outline)
                Box(Modifier.weight(1f).padding(horizontal = 17.dp, vertical = 14.dp)) { Metric(stringResource(R.string.sent), vpn.sent, Icons.Default.ArrowUpward) }
            }
        }
    }
}
@Composable private fun Metric(label: String, amount: Long, icon: androidx.compose.ui.graphics.vector.ImageVector) { Column(Modifier.semantics(mergeDescendants = true) {}) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)) }; Spacer(Modifier.height(9.dp)); Text(bytes(amount), fontSize = 22.sp, fontWeight = FontWeight.Medium) } }

@Composable internal fun SettingsPage(theme: String, onTheme: (String) -> Unit, onJournal: () -> Unit, onAbout: () -> Unit) {
    PageFrame {
        PageHeader(stringResource(R.string.settings), subtitle = stringResource(R.string.settings_subtitle))
        SectionTitle(stringResource(R.string.appearance)); CardBlock { Column(Modifier.selectableGroup()) { listOf("system" to stringResource(R.string.system_theme), "light" to stringResource(R.string.light_theme), "dark" to stringResource(R.string.dark_theme)).forEach { (value, label) -> Row(Modifier.fillMaxWidth().selectable(selected = theme == value, role = Role.RadioButton) { onTheme(value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = theme == value, onClick = null, modifier = Modifier.minimumInteractiveComponentSize()); Spacer(Modifier.width(8.dp)); Text(label) } } } }
        Spacer(Modifier.height(20.dp)); SectionTitle(stringResource(R.string.diag_settings_title)); LogSettingsCard()
        Spacer(Modifier.height(20.dp)); CardBlock { TextButton(onClick = onJournal) { Text(stringResource(R.string.connection_log)) }; HorizontalDivider(); TextButton(onClick = onAbout) { Text(stringResource(R.string.about)) } }
    }
}
@Composable private fun SimplePage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) { PageFrame { PageHeader(title, onBack = back); CardBlock(content) } }

@Composable private fun AddPage(model: AppModel, busy: Boolean, error: String?, onBack: () -> Unit, done: () -> Unit) {
    var name by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }; var edited by remember { mutableStateOf(false) }
    PageFrame {
        PageHeader(stringResource(R.string.add_profile), onBack = onBack)
        CardBlock {
            OutlinedTextField(name, { name = it; edited = true }, label = { Text(stringResource(R.string.profile_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(key, { value -> key = value; if (!edited) { val fragment = value.substringAfter('#', ""); if (fragment.isNotEmpty()) name = android.net.Uri.decode(fragment).filterNot { it.isISOControl() }.take(128) } }, label = { Text(stringResource(R.string.key_or_link)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri), minLines = 4)
            Spacer(Modifier.height(10.dp)); Text(stringResource(R.string.supported_keys), style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(22.dp)); Button(onClick = { model.addKey(name, key, done) }, enabled = name.isNotBlank() && key.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add)) }
        }
    }
}
