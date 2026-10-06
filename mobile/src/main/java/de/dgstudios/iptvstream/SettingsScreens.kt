package de.dgstudios.iptvstream

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.settings.ActionDef
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.ChoiceDef
import de.dgstudios.iptvstream.core.settings.InfoDef
import de.dgstudios.iptvstream.core.settings.SettingDef
import de.dgstudios.iptvstream.core.settings.SettingsSchema
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import de.dgstudios.iptvstream.core.ui.formatClock
import de.dgstudios.iptvstream.core.vm.MainViewModel

@Composable
fun SettingsScreen(mainVm: MainViewModel, settings: AppSettings, bottomPad: Dp, onProfiles: () -> Unit) {
    val s = LocalAppStyle.current
    val active by mainVm.active.collectAsStateWithLifecycle()
    val epg by mainVm.epg.collectAsStateWithLifecycle()
    val profile = active.profile

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPad),
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
        for (section in SettingsSchema.sections) {
            item(key = "h_" + section.id) {
                Text(
                    section.title,
                    color = s.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                )
            }
            item(key = "s_" + section.id) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
                    section.items.forEachIndexed { i, def ->
                        if (i > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.08f)))
                        }
                        SettingRow(
                            def = def,
                            settings = settings,
                            epgRunning = epg.running,
                            epgFraction = epg.fraction,
                            epgMessage = epg.message,
                            onChange = { k, v -> mainVm.set(k, v) },
                            onAction = { id ->
                                when (id) {
                                    "profiles" -> onProfiles()
                                    "reload" -> mainVm.reloadContent()
                                    "epg_refresh" -> mainVm.refreshEpg()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY)
    return fmt.format(java.util.Date(ms))
}

@Composable
private fun SettingRow(
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
                        LinearProgressIndicator(
                            progress = { epgFraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = s.accent,
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(def.title, color = s.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(def.value, color = s.onSurfaceDim, fontSize = 14.sp)
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
                            (if (p.type == ProfileType.XTREAM) "Xtream Codes" else "M3U") + " · " + p.url.removePrefix("http://").removePrefix("https://"),
                            color = s.onSurfaceDim,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlassIconButton(Icons.Rounded.Edit, "Bearbeiten", { onEdit(p.id) })
                    Spacer(Modifier.width(6.dp))
                    GlassIconButton(Icons.Rounded.Delete, "Löschen", { toDelete = p }, tint = Color(0xFFFF8A8A))
                }
            }
        }
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
    val form = remember(initial?.id) { ProfileFormState(initial) }

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
                "Verbinde deinen IPTV-Anbieter. Nach der Anmeldung werden Sender, Filme und Serien automatisch geladen.",
                color = s.onSurfaceDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Xtream Codes", form.type == ProfileType.XTREAM) { form.type = ProfileType.XTREAM }
            GlassChip("M3U / M3U8", form.type == ProfileType.M3U) { form.type = ProfileType.M3U }
        }

        GlassTextField(form.name, { form.name = it }, "Profilname (optional)")
        if (form.type == ProfileType.XTREAM) {
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
            text = if (form.busy) "Prüfe Zugangsdaten …" else if (form.isEdit) "Speichern" else "Anmelden & Laden",
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
