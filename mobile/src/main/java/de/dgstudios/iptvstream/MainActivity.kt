package de.dgstudios.iptvstream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.dgstudios.iptvstream.widget.FavoritesWidget
import kotlinx.coroutines.flow.MutableStateFlow

/** Vom Widget angeforderter Sender (Profil, Stream-ID), bis die App ihn abspielt. */
object WidgetLaunch {
    val pending = MutableStateFlow<Pair<Long, String>?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleWidget(intent)
            handlePair(intent)
        }
        setContent { MobileApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidget(intent)
        handlePair(intent)
    }

    private fun handlePair(intent: Intent?) {
        val target = TvSendLaunch.fromUri(intent?.data) ?: return
        TvSendLaunch.offer(target)
    }

    private fun handleWidget(intent: Intent?) {
        val ch = intent?.getStringExtra(FavoritesWidget.EXTRA_CHANNEL) ?: return
        val p = intent.getLongExtra(FavoritesWidget.EXTRA_PROFILE, -1L)
        if (p >= 0) WidgetLaunch.pending.value = p to ch
    }
}
