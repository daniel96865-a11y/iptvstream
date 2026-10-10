package de.dgstudios.iptvstream.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import de.dgstudios.iptvstream.core.pairing.LanPairing
import de.dgstudios.iptvstream.core.pairing.LanPairingAdvertiser
import de.dgstudios.iptvstream.core.pairing.LanPairingServer
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.vm.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** QR-Code und kurzer Code, damit das Handy die Zugangsdaten schickt. */
@Composable
fun TvLanTransfer(mainVm: MainViewModel, onCancel: () -> Unit, onDone: () -> Unit) {
    val s = LocalAppStyle.current
    val context = LocalContext.current
    val server = remember(mainVm) {
        LanPairingServer(submit = { profile ->
            runBlocking(Dispatchers.IO) { mainVm.importProfile(profile) }
        })
    }
    val advertiser = remember(context) { LanPairingAdvertiser(context) }
    val phase by server.phase.collectAsStateWithLifecycle()
    val back = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(server) {
        val offer = server.start()
        if (offer != null) advertiser.register(offer.port, offer.code)
        onDispose {
            advertiser.unregister()
            server.stop()
        }
    }
    LaunchedEffect(phase) {
        if (phase is LanPairingServer.Phase.Saved) onDone()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            try {
                back.requestFocus()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
            }
        }
    }
    BackHandler(onBack = onCancel)

    val offer = when (val p = phase) {
        is LanPairingServer.Phase.Waiting -> p.offer
        is LanPairingServer.Phase.Checking -> p.offer
        else -> null
    }
    val problem = when (val p = phase) {
        is LanPairingServer.Phase.Waiting -> p.message
        is LanPairingServer.Phase.Stopped -> p.message
        else -> null
    }
    val checking = phase is LanPairingServer.Phase.Checking

    Row(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (offer != null) {
            QrImage(offer.pageUrl, Modifier.size(300.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.width(36.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mit Handy übertragen", color = s.onSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Scanne den QR-Code mit der Handy-Kamera. Oder öffne in der App „An TV senden“ und gib den Code ein. Beides muss im selben WLAN sein.",
                color = s.onSurfaceDim,
                fontSize = 18.sp,
            )
            if (offer != null) {
                Text(
                    LanPairing.displayCode(offer.code),
                    color = s.onSurface,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                )
                val left = (offer.deadlineEpochMs - now).coerceAtLeast(0L)
                val min = left / 60_000
                val sec = (left / 1000) % 60
                Text(
                    "Gültig noch %d:%02d  ·  %s".format(min, sec, offer.host),
                    color = s.onSurfaceDim,
                    fontSize = 16.sp,
                )
            }
            if (checking) {
                Text("Prüfe Zugangsdaten …", color = s.accent, fontSize = 18.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = s.accent, trackColor = Color.White.copy(alpha = 0.15f))
            } else if (problem != null) {
                Text(problem, color = Color(0xFFFF8A8A), fontSize = 18.sp)
            } else if (offer != null) {
                Text("Warte auf das Handy …", color = s.onSurfaceDim, fontSize = 18.sp)
            }
            Spacer(Modifier.height(6.dp))
            TvButton("Abbrechen", onCancel, requester = back)
        }
    }
}

@Composable
private fun QrImage(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.background(Color.White)) {
        val count = matrix.width
        val cell = size.minDimension / count
        val origin = Offset((size.width - cell * count) / 2f, (size.height - cell * count) / 2f)
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (matrix.get(x, y)) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(origin.x + x * cell, origin.y + y * cell),
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}
