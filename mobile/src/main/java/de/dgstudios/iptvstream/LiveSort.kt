package de.dgstudios.iptvstream

import de.dgstudios.iptvstream.core.ui.MonoWindow
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.dgstudios.iptvstream.core.ui.LocalAppStyle

/**
 * Kleines Menü nach langem Druck ohne Ziehen:
 * Ganz nach oben/unten, Ausblenden, optional weitere Aktionen.
 */
@Composable
internal fun SortActionMenu(
    title: String,
    onDismiss: () -> Unit,
    onToStart: () -> Unit,
    onToEnd: () -> Unit,
    onHide: (() -> Unit)? = null,
    onShow: (() -> Unit)? = null,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { MonoWindow(); Text(title) },
        text = null,
        confirmButton = {},
        dismissButton = {
            Column {
                TextButton(onClick = { onDismiss(); onToStart() }) { Text("Ganz nach oben") }
                TextButton(onClick = { onDismiss(); onToEnd() }) { Text("Ganz nach unten") }
                if (onHide != null) {
                    TextButton(onClick = { onDismiss(); onHide() }) { Text("Ausblenden") }
                }
                if (onShow != null) {
                    TextButton(onClick = { onDismiss(); onShow() }) { Text("Einblenden") }
                }
                extra.forEach { (label, action) ->
                    TextButton(onClick = { onDismiss(); action() }) { Text(label) }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
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

/** Angehobenes Listenelement während des Ziehens (Schatten, leicht vergrößert). */
internal fun Modifier.dragLift(lifted: Boolean): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return if (!lifted) this
    else this
        .zIndex(1f)
        .shadow(12.dp, shape, clip = false)
        .graphicsLayer {
            scaleX = 1.03f
            scaleY = 1.03f
        }
}


@Composable
internal fun SortDragPreview(names: List<String>, liftedIndex: Int) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { i, name ->
            val lifted = i == liftedIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .dragLift(lifted)
                    .glass(shape)
                    .then(if (lifted) Modifier.border(2.dp, s.accent, shape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    color = s.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
