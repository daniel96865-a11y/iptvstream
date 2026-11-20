package de.dgstudios.iptvstream.core.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Play-Variante: startet auf Wunsch des [UpdateChecker] den Play-Dialog oder öffnet den Play Store.
 * Es gibt keine Berechtigungsabfrage, der Rückgabewert (Knopf „Erlauben“) bleibt leer.
 */
@Composable
internal fun rememberFlavorUpdateActions(checker: UpdateChecker): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        checker.onFlowResult(it.resultCode)
    }

    LaunchedEffect(checker, launcher) {
        checker.actions.collect { action ->
            when (action) {
                is UpdateAction.StartFlow -> checker.startFlow(action, launcher)
                UpdateAction.OpenStore -> {
                    val opened = UpdateChecker.storeIntents(context.packageName).any { intent ->
                        try {
                            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                    }
                    if (!opened) checker.storeUnavailable()
                }
            }
        }
    }
    return {}
}
