package de.dgstudios.iptvstream.core.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Card = Color(0xFF121A24)
private val Accent = Color(0xFF3D7EFF)
private val Muted = Color(0xFFB7C0CC)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UpdateHost(checker: UpdateChecker, television: Boolean = false) {
    val prompt by checker.prompt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checker.onPermissionReturned()
    }

    LaunchedEffect(checker) {
        checker.installs.collect { file ->
            val activity = context.findActivity()
            val intent = checker.installIntent(file)
            try {
                if (activity != null) activity.startActivity(intent) else context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // Installer fehlt: Dialog bleibt, der Nutzer kann es erneut versuchen.
            }
        }
    }

    val current = prompt
    if (current is UpdatePrompt.Hidden) return

    val primary = remember { FocusRequester() }
    LaunchedEffect(current) {
        try {
            primary.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    Dialog(
        onDismissRequest = { if (current !is UpdatePrompt.Offer || !current.downloading) checker.later() },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(max = if (television) 640.dp else 440.dp)
                .background(Card, RoundedCornerShape(20.dp))
                .padding(if (television) 28.dp else 22.dp)
                .focusGroup(),
        ) {
            when (current) {
                is UpdatePrompt.Offer -> OfferBody(current, television, primary, checker, onPermission = {
                    try {
                        permissionLauncher.launch(checker.permissionIntent())
                    } catch (_: ActivityNotFoundException) {
                    }
                })
                is UpdatePrompt.Message -> {
                    Text(current.text, color = Color.White, fontSize = if (television) 22.sp else 16.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { checker.later() },
                            modifier = Modifier.focusRequester(primary),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        ) { Text("OK") }
                    }
                }
                UpdatePrompt.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun OfferBody(
    offer: UpdatePrompt.Offer,
    television: Boolean,
    primary: FocusRequester,
    checker: UpdateChecker,
    onPermission: () -> Unit,
) {
    val titleSize = if (television) 28.sp else 20.sp
    val bodySize = if (television) 18.sp else 15.sp
    Text("Update verfügbar", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Bold)
    Text(
        "Version ${offer.info.versionName}",
        color = Muted,
        fontSize = if (television) 16.sp else 13.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (offer.info.changelog.isNotBlank()) {
        Text(
            offer.info.changelog,
            color = Color.White,
            fontSize = bodySize,
            modifier = Modifier
                .padding(top = 14.dp)
                .heightIn(max = if (television) 220.dp else 160.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    if (offer.needPermission) {
        Text(
            "Zum Installieren bitte Installationen aus dieser Quelle erlauben. Danach geht es automatisch weiter.",
            color = Color.White,
            fontSize = bodySize,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
    if (offer.downloading) {
        val fraction = offer.progress
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
    ) {
        if (offer.needPermission) {
            TextButton(onClick = { checker.later() }) { Text("Später", color = Muted, fontSize = bodySize) }
            Button(
                onClick = onPermission,
                modifier = Modifier.focusRequester(primary),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) { Text("Erlauben", fontSize = bodySize) }
        } else if (offer.downloading) {
            TextButton(onClick = { checker.later() }, modifier = Modifier.focusRequester(primary)) {
                Text("Später", color = Muted, fontSize = bodySize)
            }
        } else {
            TextButton(onClick = { checker.later() }) { Text("Später", color = Muted, fontSize = bodySize) }
            Button(
                onClick = { checker.confirm() },
                modifier = Modifier.focusRequester(primary),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) { Text("Jetzt aktualisieren", fontSize = bodySize) }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
