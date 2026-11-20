package de.dgstudios.iptvstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dgstudios.iptvstream.core.ui.LocalAppStyle

/**
 * Menü nach langem Druck: zuerst „Verschieben“, dann Sprünge und Ausblenden.
 */
@Composable
internal fun SortActionMenu(
    title: String,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onToStart: () -> Unit,
    onToEnd: () -> Unit,
    onHide: (() -> Unit)?,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                "Wähle, wie der Eintrag einsortiert werden soll.",
                fontSize = 14.sp,
            )
        },
        confirmButton = {},
        dismissButton = {
            Column {
                TextButton(onClick = { onDismiss(); onMove() }) { Text("Verschieben") }
                TextButton(onClick = { onDismiss(); onToStart() }) { Text("Ganz nach oben") }
                TextButton(onClick = { onDismiss(); onToEnd() }) { Text("Ganz nach unten") }
                if (onHide != null) {
                    TextButton(onClick = { onDismiss(); onHide() }) { Text("Ausblenden") }
                }
                extra.forEach { (label, action) ->
                    TextButton(onClick = { onDismiss(); action() }) { Text(label) }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/** Leiste im Verschiebe-Modus: Pfeile, Fertig, optional alphabetisch und zurücksetzen. */
@Composable
internal fun MoveHintBar(
    name: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDone: () -> Unit,
    onAlpha: () -> Unit,
    onReset: () -> Unit,
    remoteHint: Boolean = false,
) {
    val s = LocalAppStyle.current
    Column(
        Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "„$name“ verschieben",
            color = s.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (remoteHint) "↑↓ verschieben, OK ablegen" else "Ziehen oder mit den Pfeilen verschieben",
            color = s.onSurfaceDim,
            fontSize = 13.sp,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("↑", selected = false, onClick = onUp)
            GlassChip("↓", selected = false, onClick = onDown)
            Spacer(Modifier.weight(1f))
            GlassChip("Fertig", selected = true, onClick = onDone)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onAlpha) { Text("Alphabetisch", color = s.onSurface, fontSize = 13.sp) }
            TextButton(onClick = onReset) { Text("Zurücksetzen", color = s.onSurface, fontSize = 13.sp) }
        }
    }
}

@Composable
internal fun HiddenLine(label: String, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Text(
        text = label,
        color = s.onSurfaceDim,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
