package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.settings.ActionDef
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.ChoiceDef
import de.dgstudios.iptvstream.core.settings.InfoDef
import de.dgstudios.iptvstream.core.settings.SettingDef
import de.dgstudios.iptvstream.core.settings.SettingsSchema
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import de.dgstudios.iptvstream.core.update.installedVersionName
import de.dgstudios.iptvstream.core.vm.MainViewModel

@Composable
fun TvSettingsScreen(mainVm: MainViewModel, settings: AppSettings, entry: EntryHandle, onProfiles: () -> Unit) {
    val s = LocalAppStyle.current
    val context = LocalContext.current
    val active by mainVm.active.collectAsStateWithLifecycle()
    val epg by mainVm.epg.collectAsStateWithLifecycle()
    var sectionIndex by rememberSaveable { mutableStateOf(0) }
    val sections = SettingsSchema.sections
    val sectionReqs = remember { sections.map { FocusRequester() } }

    RegisterEntry(entry) {
        try {
            sectionReqs[sectionIndex.coerceIn(0, sections.lastIndex)].requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(260.dp).fillMaxHeight().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEachIndexed { i, sec ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .tvFocus(
                            onClick = { sectionIndex = i },
                            onFocus = { if (it) sectionIndex = i },
                            requester = sectionReqs[i],
                            selected = i == sectionIndex,
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(sec.title, color = s.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        val section = sections[sectionIndex.coerceIn(0, sections.lastIndex)]
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (section.id == "general") {
                item(key = "profile_info") {
                    val p = active.profile
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                        Text("Aktives Profil", color = s.onSurfaceDim, fontSize = 14.sp)
                        Text(p?.name ?: "–", color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (p != null) {
                            val sync = if (p.lastSync > 0) formatDate(p.lastSync) else "noch nicht geladen"
                            val epgT = if (p.lastEpgSync > 0) formatDate(p.lastEpgSync) else "noch nicht geladen"
                            Text("Inhalte: $sync  ·  EPG: $epgT", color = s.onSurfaceDim, fontSize = 14.sp)
                        }
                    }
                }
            }
            items(section.items, key = { section.id + "/" + it.id }) { def ->
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
                            "check_update" -> context.container.updates.checkNow()
                        }
                    },
                )
            }
        }
    }
}

private fun formatDate(ms: Long): String =
    java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ms))

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
            val label = def.labelOf(value)
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(onClick = {
                        // OK schaltet zur nächsten Option weiter (bei Schaltern: ein/aus).
                        val idx = def.options.indexOfFirst { it.id == value }.coerceAtLeast(0)
                        onChange(def.id, def.options[(idx + 1) % def.options.size].id)
                    })
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(def.title, color = s.onSurface, fontSize = 20.sp)
                    if (def.subtitle.isNotEmpty()) Text(def.subtitle, color = s.onSurfaceDim, fontSize = 14.sp)
                }
                Text(
                    if (def.isToggle) (if (value == "on") "● Ein" else "○ Aus") else "‹ $label ›",
                    color = s.accent,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        is ActionDef -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(onClick = { onAction(def.id) })
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(def.title, color = s.accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (def.id == "epg_refresh" && epgRunning) {
                    Text(epgMessage, color = s.onSurfaceDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    if (epgFraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { epgFraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                            color = s.accent,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                            color = s.accent,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }
                } else if (def.subtitle.isNotEmpty()) {
                    Text(def.subtitle, color = s.onSurfaceDim, fontSize = 14.sp)
                }
            }
        }
        is InfoDef -> {
            val value = if (def.id == "version") {
                installedVersionName(LocalContext.current).ifBlank { def.value }
            } else {
                def.value
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(def.title, color = s.onSurface, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Text(value, color = s.onSurfaceDim, fontSize = 18.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------- Profile

@Composable
fun TvProfilesScreen(mainVm: MainViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val s = LocalAppStyle.current
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val active by mainVm.active.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    val addFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            try {
                addFocus.requestFocus()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvTitle("Profile", Modifier.weight(1f))
            TvButton("Zurück", onBack)
            Spacer(Modifier.width(12.dp))
            TvButton("Profil hinzufügen", { onEdit(0L) }, requester = addFocus)
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
            items(profiles, key = { it.id }) { p ->
                val isActive = p.id == active.profile?.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .tvFocus(onClick = { mainVm.selectProfile(p.id) }, selected = isActive)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            p.name + if (isActive) "   ✓ aktiv" else "",
                            color = s.onSurface, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (p.type == ProfileType.XTREAM) "Xtream Codes" else "M3U") + " · " +
                                p.url.removePrefix("http://").removePrefix("https://"),
                            color = s.onSurfaceDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    TvButton("Bearbeiten", { onEdit(p.id) })
                    Spacer(Modifier.width(10.dp))
                    TvButton("Löschen", { toDelete = p })
                }
            }
        }
    }

    val del = toDelete
    if (del != null) {
        TvActionDialog(
            title = "„${del.name}“ löschen? Zwischengespeicherte Daten, Favoriten und Fortschritte dieses Profils gehen verloren.",
            actions = listOf(
                TvAction("Abbrechen") {},
                TvAction("Endgültig löschen") { mainVm.deleteProfile(del.id) },
            ),
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
fun TvProfileForm(mainVm: MainViewModel, profileId: Long, first: Boolean, onDone: () -> Unit) {
    val s = LocalAppStyle.current
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val initial = profiles.firstOrNull { it.id == profileId }
    val form = remember(initial?.id) { ProfileFormState(initial) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            try {
                firstField.requestFocus()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
            }
        }
    }

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
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (first) {
            Text("Willkommen", color = s.onSurface, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(
                "Verbinde deinen IPTV-Anbieter. Nach der Anmeldung werden Sender, Filme und Serien automatisch geladen.",
                color = s.onSurfaceDim, fontSize = 17.sp,
            )
        } else {
            TvTitle(if (form.isEdit) "Profil bearbeiten" else "Neues Profil")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Xtream Codes", { form.type = ProfileType.XTREAM }, selected = form.type == ProfileType.XTREAM, requester = firstField)
            TvButton("M3U / M3U8", { form.type = ProfileType.M3U }, selected = form.type == ProfileType.M3U)
        }
        TvField("Profilname (optional)", form.name, { form.name = it })
        if (form.type == ProfileType.XTREAM) {
            TvField("Server-URL, z. B. http://server:8080", form.url, { form.url = it })
            TvField("Benutzername", form.username, { form.username = it })
            TvField("Passwort", form.password, { form.password = it }, password = true)
        } else {
            TvField("Playlist-URL (M3U/M3U8)", form.url, { form.url = it })
            TvField("EPG-URL (XMLTV, optional)", form.epgUrl, { form.epgUrl = it })
        }
        val err = form.error
        if (err != null) Text(err, color = Color(0xFFFF8A8A), fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (form.busy) "Prüfe Zugangsdaten …" else if (form.isEdit) "Speichern" else "Anmelden & Laden",
                onClick = { submit() },
                selected = form.valid,
            )
            if (!first) TvButton("Abbrechen", onDone)
        }
        if (form.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = s.accent, trackColor = Color.White.copy(alpha = 0.15f))
        }
    }
}
