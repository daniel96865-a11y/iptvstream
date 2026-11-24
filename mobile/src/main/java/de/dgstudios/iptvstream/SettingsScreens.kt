package de.dgstudios.iptvstream

import de.dgstudios.iptvstream.core.ui.AccentBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dgstudios.iptvstream.core.settings.SettingsSection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.FreeLanguages
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.ui.profileSubtitle
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.settings.ActionDef
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.ChoiceDef
import de.dgstudios.iptvstream.core.settings.InfoDef
import de.dgstudios.iptvstream.core.settings.SettingDef
import de.dgstudios.iptvstream.core.settings.SettingsSchema
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.update.installedVersionName
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import de.dgstudios.iptvstream.core.ui.formatClock
import de.dgstudios.iptvstream.core.vm.MainViewModel

@Composable
fun SettingsScreen(mainVm: MainViewModel, settings: AppSettings, bottomPad: Dp, onProfiles: () -> Unit) {
    val s = LocalAppStyle.current
    val context = LocalContext.current
    val active by mainVm.active.collectAsStateWithLifecycle()
    val epg by mainVm.epg.collectAsStateWithLifecycle()
    val profile = active.profile
    // Untere Leiste (Höhe + Abstand) plus System-Navigationsleiste, damit die letzte Karte
    // vollständig über die Leiste gescrollt werden kann.
    val sysNav = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    val listBottom = maxOf(bottomPad, 66.dp + 36.dp + sysNav) + 24.dp
    var openRaw by rememberSaveable { mutableStateOf("") }
    val expanded = openRaw.split(',').filter { it.isNotEmpty() }.toSet()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = listBottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Einstellungen") }
        if (profile != null) {
            item(key = "profile") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(20.dp), strong = true)
                        .pressable(onProfiles)
                        .padding(16.dp),
                ) {
                    Text("Aktives Profil", color = s.onSurfaceDim, fontSize = 12.sp)
                    Text(profile.name, color = s.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    val syncText = if (profile.lastSync > 0) "Inhalte: ${formatDate(profile.lastSync)}" else "Inhalte: noch nicht geladen"
                    val epgText = if (profile.lastEpgSync > 0) "EPG: ${formatDate(profile.lastEpgSync)}" else "EPG: noch nicht geladen"
                    Text("$syncText  ·  $epgText", color = s.onSurfaceDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Profile verwalten", color = s.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item(key = "sections") {
            SettingsSectionList(
                settings = settings,
                expanded = expanded,
                epgRunning = epg.running,
                epgFraction = epg.fraction,
                epgMessage = epg.message,
                onToggle = { id ->
                    openRaw = (if (id in expanded) expanded - id else expanded + id).joinToString(",")
                },
                onChange = { k, v -> mainVm.set(k, v) },
                onAction = { id ->
                    when (id) {
                        "profiles" -> onProfiles()
                        "reload" -> mainVm.reloadContent()
                        "reset_order" -> mainVm.resetLiveOrder()
                        "epg_refresh" -> mainVm.refreshEpg()
                        "check_update" -> context.container.updates.checkNow()
                    }
                },
            )
        }
    }
}

/** Bereiche untereinander. Tippen klappt die Optionen nach unten auf, so wie früher in der Liste. */
@Composable
internal fun SettingsSectionList(
    settings: AppSettings,
    expanded: Set<String> = emptySet(),
    epgRunning: Boolean = false,
    epgFraction: Float = 0f,
    epgMessage: String = "",
    onToggle: (String) -> Unit = {},
    onChange: (String, String) -> Unit = { _, _ -> },
    onAction: (String) -> Unit = {},
) {
    val s = LocalAppStyle.current
    val motion = s.animations
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
        SettingsSchema.sections.forEachIndexed { i, sec ->
            val open = sec.id in expanded
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.08f)))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable({ onToggle(sec.id) })
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sec.title,
                    color = s.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (open) "Zuklappen" else "Aufklappen",
                    tint = s.onSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = if (motion) expandVertically(tween(240)) + fadeIn(tween(180)) else EnterTransition.None,
                exit = if (motion) shrinkVertically(tween(180)) + fadeOut(tween(120)) else ExitTransition.None,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.08f)))
                    SettingsRows(sec, settings, epgRunning, epgFraction, epgMessage, onChange, onAction)
                }
            }
        }
    }
}

@Composable
private fun SettingsRows(
    section: SettingsSection,
    settings: AppSettings,
    epgRunning: Boolean,
    epgFraction: Float,
    epgMessage: String,
    onChange: (String, String) -> Unit,
    onAction: (String) -> Unit,
) {
    // „Bildschirmrand (nur TV)“ betrifft nur den Fernseher.
    section.items.filterNot { it.id in SettingsSchema.tvOnly }.forEachIndexed { i, def ->
        if (i > 0) {
            Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 18.dp).background(Color.White.copy(alpha = 0.08f)))
        }
        SettingRow(def, settings, epgRunning, epgFraction, epgMessage, onChange, onAction)
    }
}

private fun formatDate(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY)
    return fmt.format(java.util.Date(ms))
}

@Composable
internal fun SettingRow(
    def: SettingDef,
    settings: AppSettings,
    epgRunning: Boolean,
    epgFraction: Float,
    epgMessage: String,
    onChange: (String, String) -> Unit,
    onAction: (String) -> Unit,
) {
    val s = LocalAppStyle.current
    when (def) {
        is ChoiceDef -> {
            val value = settings.get(def.id)
            if (def.isToggle) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(def.title, color = s.onSurface, fontSize = 15.sp)
                        if (def.subtitle.isNotEmpty()) Text(def.subtitle, color = s.onSurfaceDim, fontSize = 12.sp)
                    }
                    Switch(
                        checked = value == "on",
                        onCheckedChange = { onChange(def.id, if (it) "on" else "off") },
                        colors = SwitchDefaults.colors(checkedTrackColor = s.accent, checkedThumbColor = Color.White),
                    )
                }
            } else {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().pressable({ open = true }).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(def.title, color = s.onSurface, fontSize = 15.sp)
                            if (def.subtitle.isNotEmpty()) Text(def.subtitle, color = s.onSurfaceDim, fontSize = 12.sp)
                        }
                        Text(def.labelOf(value), color = s.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (o in def.options) {
                            DropdownMenuItem(
                                text = { Text(o.label) },
                                trailingIcon = {
                                    if (o.id == value) Icon(Icons.Rounded.Check, null, tint = s.accent)
                                },
                                onClick = {
                                    open = false
                                    onChange(def.id, o.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        is ActionDef -> {
            Column(
                Modifier.fillMaxWidth().pressable({ onAction(def.id) }).padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(def.title, color = s.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (def.id == "epg_refresh" && epgRunning) {
                    Text(epgMessage, color = s.onSurfaceDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    if (epgFraction >= 0f) {
                        AccentBar(
                            progress = { epgFraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = s.accent,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }
                } else if (def.subtitle.isNotEmpty()) {
                    Text(def.subtitle, color = s.onSurfaceDim, fontSize = 12.sp)
                }
            }
        }
        is InfoDef -> {
            val value = if (def.id == "version") {
                installedVersionName(LocalContext.current).ifBlank { def.value }
            } else {
                def.value
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(def.title, color = s.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(value, color = s.onSurfaceDim, fontSize = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------- Profile

@Composable
fun ProfilesScreen(mainVm: MainViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val s = LocalAppStyle.current
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val active by mainVm.active.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    var toTv by remember { mutableStateOf<ProfileEntity?>(null) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack)
            Text(
                "Profile",
                color = s.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            GlassIconButton(Icons.Rounded.Add, "Profil hinzufügen", { onEdit(0L) })
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(profiles, key = { it.id }) { p ->
                val isActive = p.id == active.profile?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), strong = isActive)
                        .pressable({ mainVm.selectProfile(p.id) })
                        .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = s.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Rounded.Check, "Aktiv", tint = s.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(
                            profileSubtitle(p),
                            color = s.onSurfaceDim,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (p.type != ProfileType.FREE) {
                        GlassIconButton(Icons.Rounded.Tv, "An TV senden", { toTv = p })
                        Spacer(Modifier.width(6.dp))
                    }
                    GlassIconButton(Icons.Rounded.Edit, "Bearbeiten", { onEdit(p.id) })
                    Spacer(Modifier.width(6.dp))
                    GlassIconButton(Icons.Rounded.Delete, "Löschen", { toDelete = p }, tint = Color(0xFFFF8A8A))
                }
            }
            if (profiles.none { it.type == ProfileType.FREE }) {
                item(key = "free-add") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(18.dp))
                            .pressable({ onEdit(NEW_FREE_PROFILE) })
                            .padding(16.dp),
                    ) {
                        Text("＋ Freie Sender hinzufügen", color = s.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Frei empfangbare Sender aus dem Projekt iptv-org: Deutsch, Italienisch (u. a. Rai) und viele weitere Sprachen. Ohne Anbieter, ohne Zugangsdaten. Dein Anbieter-Profil bleibt unberührt.",
                            color = s.onSurfaceDim, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    val tv = toTv
    if (tv != null) {
        SendToTvDialog(tv, onDismiss = { toTv = null })
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Profil löschen?") },
            text = { Text("„${del.name}“ und alle zwischengespeicherten Daten, Favoriten und Fortschritte dieses Profils werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    mainVm.deleteProfile(del.id)
                    toDelete = null
                }) { Text("Löschen", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun ProfileFormScreen(mainVm: MainViewModel, profileId: Long, first: Boolean, onDone: () -> Unit) {
    val s = LocalAppStyle.current
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val initial = profiles.firstOrNull { it.id == profileId }
    val form = remember(initial?.id, profileId) { ProfileFormState(initial, startFree = profileId == NEW_FREE_PROFILE) }

    fun submit() {
        if (!form.valid || form.busy) return
        form.busy = true
        form.error = null
        mainVm.saveProfile(form.toEntity()) { err ->
            form.busy = false
            if (err == null) onDone() else form.error = err
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!first) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onDone)
                Text(
                    if (form.isEdit) "Profil bearbeiten" else "Neues Profil",
                    color = s.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        } else {
            Spacer(Modifier.height(24.dp))
            Text("Willkommen", color = s.onSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Verbinde deinen IPTV-Anbieter oder nimm „Freie Sender“ ohne Anbieter. Nach der Anmeldung werden Sender, Filme und Serien automatisch geladen.",
                color = s.onSurfaceDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("Xtream Codes", form.type == ProfileType.XTREAM) { form.chooseType(ProfileType.XTREAM) }
            GlassChip("M3U / M3U8", form.type == ProfileType.M3U) { form.chooseType(ProfileType.M3U) }
            GlassChip("Freie Sender", form.type == ProfileType.FREE) { form.chooseType(ProfileType.FREE) }
        }

        GlassTextField(form.name, { form.name = it }, if (form.isFree) "Profilname (Freie Sender)" else "Profilname (optional)")
        if (form.isFree) {
            FreeSendersSection(form)
        } else if (form.type == ProfileType.XTREAM) {
            GlassTextField(form.url, { form.url = it }, "Server-URL, z. B. http://server:8080")
            GlassTextField(form.username, { form.username = it }, "Benutzername")
            GlassTextField(
                form.password, { form.password = it }, "Passwort", password = true,
                imeAction = ImeAction.Done, onDone = { submit() },
            )
        } else {
            GlassTextField(form.url, { form.url = it }, "Playlist-URL (M3U/M3U8)")
            GlassTextField(
                form.epgUrl, { form.epgUrl = it }, "EPG-URL (XMLTV, optional)",
                imeAction = ImeAction.Done, onDone = { submit() },
            )
        }

        val err = form.error
        if (err != null) {
            Text(err, color = Color(0xFFFF8A8A), fontSize = 14.sp)
        }
        PrimaryButton(
            text = when {
                form.busy -> if (form.isFree) "Lade Sender …" else "Prüfe Zugangsdaten …"
                form.isEdit -> "Speichern"
                form.isFree -> "Sender laden"
                else -> "Anmelden & Laden"
            },
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = s.accent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Profil-ID, mit der das Formular direkt als neues „Freie Sender“-Profil startet. */
const val NEW_FREE_PROFILE = -1L

/** Auswahl der Sprachen für Freie Sender (iptv-org). Deutsch und Italienisch sind vorgewählt. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FreeSendersSection(form: ProfileFormState) {
    val s = LocalAppStyle.current
    var showAll by remember { mutableStateOf(false) }
    val selected = form.freeLanguages

    Text(
        "Frei empfangbare Sender aus dem GitHub-Projekt iptv-org. Dazu brauchst du keinen Anbieter. " +
            "Manche Sender sind nach Land gesperrt oder gerade offline.",
        color = s.onSurfaceDim, fontSize = 13.sp,
    )
    Text("Sprachen", color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (code in FreeLanguages.PROMINENT) {
            val lang = FreeLanguages.find(code) ?: continue
            GlassChip(lang.label, code in selected) { form.toggleLanguage(code) }
        }
        GlassChip("Weitere Sprachen …", false) { showAll = true }
    }
    val extra = selected.filter { it !in FreeLanguages.PROMINENT }
    if (extra.isNotEmpty()) {
        Text(
            "Außerdem: " + extra.joinToString(", ") { FreeLanguages.find(it)?.label ?: it },
            color = s.onSurfaceDim, fontSize = 13.sp,
        )
    }
    if (selected.isEmpty()) {
        Text("Bitte mindestens eine Sprache wählen.", color = Color(0xFFFF8A8A), fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Geo-gesperrte Sender ausblenden", color = s.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = form.freeHideGeo,
            onCheckedChange = { form.freeHideGeo = it },
            colors = SwitchDefaults.colors(checkedTrackColor = s.accent),
        )
    }

    if (showAll) FreeLanguagesDialog(form, onClose = { showAll = false })
}

@Composable
private fun FreeLanguagesDialog(form: ProfileFormState, onClose: () -> Unit) {
    val s = LocalAppStyle.current
    var query by remember { mutableStateOf("") }
    val all = remember { FreeLanguages.FOR_PICKER }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.english.lowercase().contains(q) }
    }
    val selected = form.freeLanguages
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sprachen (${selected.size} gewählt)") },
        text = {
            Column {
                GlassTextField(query, { query = it }, "Suchen")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(340.dp)) {
                    items(shown, key = { it.code }) { lang ->
                        val on = lang.code in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable({ form.toggleLanguage(lang.code) })
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lang.label, color = s.onSurface, fontSize = 15.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (on) Icon(Icons.Rounded.Check, "Gewählt", tint = s.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fertig") } },
    )
}
