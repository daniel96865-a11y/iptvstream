package de.dgstudios.iptvstream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.pairing.LanPairing
import de.dgstudios.iptvstream.core.pairing.LanPairingBrowser
import de.dgstudios.iptvstream.core.pairing.LanPairingClient
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import de.dgstudios.iptvstream.core.vm.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL

/** Ziel, das der QR-Code bzw. die App-Verknüpfung öffnet. */
data class TvPairTarget(val host: String, val port: Int, val token: String)

object TvSendLaunch {
    val pending = MutableStateFlow<TvPairTarget?>(null)

    fun offer(target: TvPairTarget) {
        if (pending.value == target) pending.value = null
        pending.value = target
    }

    fun fromUri(uri: Uri?): TvPairTarget? {
        if (uri == null || uri.scheme != "iptvstream" || uri.host != "pair") return null
        val host = uri.getQueryParameter("host") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val token = uri.getQueryParameter("token") ?: return null
        if (!LanPairing.isPrivateHost(host) || port !in 1..65535) return null
        if (token.length !in 6..64 || token.any { !it.isLetterOrDigit() }) return null
        return TvPairTarget(host, port, token)
    }
}

@Composable
fun SendToTvDialog(profile: ProfileEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(LanPairing.AddressFields()) }
    var jump by remember { mutableStateOf(FieldJump(-1, 0)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (done) "Gesendet" else "An TV senden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Text("Der Fernseher hat „${profile.name}“ übernommen und lädt die Sender.")
                } else {
                    Text("Gib den Code ein, der auf dem Fernseher steht. Beide Geräte müssen im selben WLAN sein. Wenn die Suche nicht klappt, tippe die Adresse. Die Punkte setzt die App.")
                    GlassTextField(code, { code = it.take(12) }, "Code vom Fernseher", imeAction = ImeAction.Next)
                    TvAddressInput(address, jump) { index, raw ->
                        val edit = if (raw == BACKSPACE) {
                            LanPairing.backspaceAddress(address, index)
                        } else {
                            LanPairing.editAddress(address, index, raw)
                        }
                        address = edit.fields
                        if (edit.focus != index) jump = FieldJump(edit.focus, jump.seq + 1)
                    }
                    val err = error
                    if (err != null) Text(err, color = Color(0xFFFF6B6B))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Schließen") }
            } else {
                TextButton(
                    enabled = !busy && LanPairing.normalizeCode(code).length == LanPairing.CODE_LENGTH,
                        onClick = {
                        val typed = address.wire()
                        if (typed != null && typed.isEmpty()) {
                            error = "Die Adresse ist unvollständig. Vier Zahlen, die Punkte setzt die App."
                            return@TextButton
                        }
                        busy = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                deliver(context, profile, code, typed)
                            }
                            busy = false
                            if (result == null) {
                                done = true
                            } else if (result == NOT_FOUND) {
                                error = "Kein Fernseher mit diesem Code gefunden. Trag die Adresse vom Fernseher ein, zum Beispiel 192.168.1.57:28765."
                            } else {
                                error = result
                            }
                        }
                    },
                ) { Text("Senden") }
            }
        },
        dismissButton = {
            if (!done) TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Abbrechen") }
        },
    )
}

@Composable
fun SendToTvScreen(mainVm: MainViewModel, onDone: () -> Unit) {
    val s = LocalAppStyle.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target by TvSendLaunch.pending.collectAsStateWithLifecycle()
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val form = remember { ProfileFormState(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun send(profile: ProfileEntity) {
        val dest = target
        if (dest == null) {
            error = "Der Fernseher ist nicht mehr verbunden. Scanne den QR-Code erneut."
            return
        }
        if (profile.type == ProfileType.XTREAM && (profile.username.isBlank() || profile.password.isBlank() || profile.url.isBlank())) {
            error = "Server, Benutzername und Passwort fehlen."
            return
        }
        if (profile.type == ProfileType.M3U && profile.url.isBlank()) {
            error = "Die Playlist-Adresse fehlt."
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                sendProfile(context, dest.host, dest.port, dest.token, profile)
            }
            busy = false
            if (result == null) done = true else error = result
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
        Row {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onDone)
            Text(
                "An den Fernseher",
                color = s.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        if (done) {
            Text("Der Fernseher speichert das Profil und lädt die Sender.", color = s.onSurface, fontSize = 16.sp)
            PrimaryButton("Fertig", onClick = {
                TvSendLaunch.pending.value = null
                onDone()
            })
            return@Column
        }
        Text(
            "Wähle ein gespeichertes Profil oder trag die Zugangsdaten ein. Sie bleiben im eigenen WLAN.",
            color = s.onSurfaceDim,
            fontSize = 14.sp,
        )
        profiles.forEach { p ->
            PrimaryButton(
                text = p.name,
                onClick = { if (!busy) send(p.copy(id = 0)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (profiles.isNotEmpty()) {
            Text("oder neu eintragen", color = s.onSurfaceDim, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Xtream Codes", form.type == ProfileType.XTREAM) { form.type = ProfileType.XTREAM }
            GlassChip("M3U / M3U8", form.type == ProfileType.M3U) { form.type = ProfileType.M3U }
        }
        GlassTextField(form.name, { form.name = it }, "Profilname (optional)")
        if (form.type == ProfileType.XTREAM) {
            GlassTextField(form.url, { form.url = it }, "Server-URL")
            GlassTextField(form.username, { form.username = it }, "Benutzername")
            GlassTextField(form.password, { form.password = it }, "Passwort", password = true)
        } else {
            GlassTextField(form.url, { form.url = it }, "Playlist-URL (M3U/M3U8)")
            GlassTextField(form.epgUrl, { form.epgUrl = it }, "EPG-URL (optional)", imeAction = ImeAction.Done)
        }
        val err = error
        if (err != null) Text(err, color = Color(0xFFFF8A8A), fontSize = 14.sp)
        PrimaryButton(
            text = if (busy) "Sende …" else "An den Fernseher senden",
            onClick = { if (!busy && form.valid) send(form.toEntity()) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = s.accent, trackColor = Color.White.copy(alpha = 0.15f))
        }
        Spacer(Modifier.height(12.dp))
    }
}

private const val NOT_FOUND = "not-found"
private const val BACKSPACE = "\u0008"

private data class FieldJump(val field: Int, val seq: Int)

@Composable
private fun TvAddressInput(
    fields: LanPairing.AddressFields,
    jump: FieldJump,
    onEdit: (Int, String) -> Unit,
) {
    val s = LocalAppStyle.current
    val focusers = remember { List(5) { FocusRequester() } }
    LaunchedEffect(jump) {
        if (jump.field !in 0..4) return@LaunchedEffect
        try {
            focusers[jump.field].requestFocus()
        } catch (e: IllegalStateException) {
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Adresse, nur die Zahlen. Die Punkte setzt die App.", color = s.onSurfaceDim, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            fields.octets.forEachIndexed { index, value ->
                if (index > 0) {
                    Text(".", color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
                }
                AddressCell(
                    value = value,
                    requester = focusers[index],
                    modifier = Modifier.weight(1f),
                    onChange = { onEdit(index, it) },
                    onBackspace = { onEdit(index, BACKSPACE) },
                    onNext = { onEdit(index, "$value.") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Port", color = s.onSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            AddressCell(
                value = fields.port,
                requester = focusers[4],
                modifier = Modifier.weight(1f),
                onChange = { onEdit(4, it) },
                onBackspace = { onEdit(4, BACKSPACE) },
                done = true,
            )
            Spacer(Modifier.width(8.dp))
            Text("optional", color = s.onSurfaceDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AddressCell(
    value: String,
    requester: FocusRequester,
    modifier: Modifier,
    onChange: (String) -> Unit,
    onBackspace: () -> Unit,
    onNext: () -> Unit = {},
    done: Boolean = false,
) {
    val s = LocalAppStyle.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = s.onSurface, fontSize = 16.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
        cursorBrush = SolidColor(s.accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = if (done) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = modifier
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.isEmpty()) {
                    onBackspace()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                inner()
            }
        },
    )
}

private fun deliver(context: Context, profile: ProfileEntity, code: String, manualHost: String?): String? {
    val normalized = LanPairing.normalizeCode(code)
    if (normalized.length != LanPairing.CODE_LENGTH) return "Der Code hat 6 Zeichen."
    val saved = profile.copy(id = 0)
    val openers = lanOpeners(context)
    val manual = manualHost?.let { LanPairing.manualTarget(it) }
    if (manualHost != null && manual == null) return "Diese Adresse liegt nicht im Heimnetz."
    val manualPort = manual?.port
    if (manual != null && manualPort != null) {
        return sendProfile(openers, manual.host, manualPort, normalized, saved)
    }
    if (manual != null) {
        for (opener in openers) {
            val found = LanPairingClient.findOnHost(manual.host, normalized, opener = opener) ?: continue
            return sendProfile(openers, found.host, found.port, normalized, saved)
        }
        return "An dieser Adresse antwortet kein Fernseher mit dem Code."
    }
    val viaNsd = try {
        LanPairingBrowser(context).find(normalized, timeoutMs = 4_000)
    } catch (e: RuntimeException) {
        null
    }
    if (viaNsd != null) {
        val sent = sendProfile(openers, viaNsd.host, viaNsd.port, normalized, saved)
        if (sent == null || !LanPairingClient.connectFailure(sent)) return sent
    }
    val local = localLan(context)
    if (local != null) {
        val hosts = LanPairing.subnetHosts(local.host, local.prefix)
        for (opener in openers) {
            val found = LanPairingClient.findService(hosts, normalized, opener = opener) ?: continue
            return sendProfile(openers, found.host, found.port, normalized, saved)
        }
    }
    return NOT_FOUND
}

private fun sendProfile(context: Context, host: String, port: Int, secret: String, profile: ProfileEntity): String? =
    sendProfile(lanOpeners(context), host, port, secret, profile)

private fun sendProfile(
    openers: List<(URL) -> HttpURLConnection>,
    host: String,
    port: Int,
    secret: String,
    profile: ProfileEntity,
): String? {
    var last: String? = null
    for (opener in openers) {
        val result = LanPairingClient.send(host, port, secret, profile, opener = opener)
        if (result == null || !LanPairingClient.connectFailure(result)) return result
        last = result
    }
    return last
}

/** WLAN zuerst, danach die normale Verbindung. Beides, weil eines davon am Heimnetz scheitern kann. */
private fun lanOpeners(context: Context): List<(URL) -> HttpURLConnection> {
    val network = homeNetwork(context)
    val openers = ArrayList<(URL) -> HttpURLConnection>(2)
    if (network != null) {
        openers.add { url -> network.openConnection(url) as HttpURLConnection }
    }
    openers.add { url -> url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }
    return openers
}

private fun homeNetwork(context: Context): Network? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val active = cm.activeNetwork
    if (active != null && isHome(cm, active)) return active
    @Suppress("DEPRECATION")
    return cm.allNetworks.firstOrNull { isHome(cm, it) }
}

private fun isHome(cm: ConnectivityManager, network: Network): Boolean {
    val caps = cm.getNetworkCapabilities(network) ?: return false
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

private data class LocalLan(val host: String, val prefix: Int)

private fun localLan(context: Context): LocalLan? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = homeNetwork(context) ?: return null
    val links = cm.getLinkProperties(network)?.linkAddresses ?: return null
    for (link in links) {
        val addr = link.address as? Inet4Address ?: continue
        if (!LanPairing.isPrivateV4(addr)) continue
        val host = addr.hostAddress ?: continue
        return LocalLan(host, link.prefixLength)
    }
    return null
}
