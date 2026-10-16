package de.dgstudios.iptvstream.core.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Merkt die zuletzt gesehene Version und liest die Neuerungen aus release-notes/vX.Y.Z.txt (Asset). */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY = "last_seen_version"

    /** Neuerungen als Stichpunkte, wenn nach einem Update zum ersten Mal gestartet wird. */
    fun pending(context: Context): Pair<String, List<String>>? {
        val name = versionName(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY, null)
        if (last == name) return null
        if (last == null) {
            // Neuinstallation: nichts Neues zu zeigen.
            prefs.edit().putString(KEY, name).apply()
            return null
        }
        val lines = notes(context, name)
        if (lines.isEmpty()) {
            prefs.edit().putString(KEY, name).apply()
            return null
        }
        return name to lines
    }

    fun markSeen(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, name).apply()
    }

    fun notes(context: Context, name: String): List<String> = try {
        context.assets.open("v$name.txt").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    } catch (_: Exception) {
        emptyList()
    }

    fun parse(text: String): List<String> =
        text.lines().map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }
}

@Composable
fun WhatsNewHost(show: Boolean, television: Boolean) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show && !checked) {
            checked = true
            data = WhatsNew.pending(context)
        }
    }
    val current = data ?: return
    if (!show) return
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            primary.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
    val close = {
        WhatsNew.markSeen(context, current.first)
        data = null
    }
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(max = if (television) 640.dp else 440.dp)
                .background(Color(0xFF121A24), RoundedCornerShape(20.dp))
                .padding(if (television) 28.dp else 22.dp)
                .focusGroup(),
        ) {
            Text(
                "Neu in dieser Version",
                color = Color.White,
                fontSize = if (television) 28.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Version ${current.first}",
                color = Color(0xFFB7C0CC),
                fontSize = if (television) 16.sp else 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .heightIn(max = if (television) 280.dp else 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                current.second.forEach {
                    Text("• $it", color = Color.White, fontSize = if (television) 18.sp else 15.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { close() },
                    modifier = Modifier.focusRequester(primary),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D7EFF), contentColor = Color.White),
                ) { Text("OK", fontSize = if (television) 18.sp else 15.sp) }
            }
        }
    }
}
