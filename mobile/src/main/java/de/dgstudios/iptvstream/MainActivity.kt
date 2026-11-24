package de.dgstudios.iptvstream

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.dgstudios.iptvstream.widget.FavoritesWidget
import kotlinx.coroutines.flow.MutableStateFlow

/** Vom Widget angeforderter Sender (Profil, Stream-ID), bis die App ihn abspielt. */
object WidgetLaunch {
    val pending = MutableStateFlow<Pair<Long, String>?>(null)
}

/**
 * Bild-in-Bild für den Player. Der Player-Bildschirm schaltet es frei, solange ein Sender läuft;
 * ab Android 12 wechselt das System selbst, davor [MainActivity.onUserLeaveHint].
 */
object PlayerPip {
    @Volatile var allowed = false
        private set

    fun params(autoEnter: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun allow(activity: Activity?, on: Boolean) {
        allowed = on
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { params(on)?.let { activity.setPictureInPictureParams(it) } }
    }
}

class MainActivity : ComponentActivity() {
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Ab Android 12 erledigt das setAutoEnterEnabled.
        if (!PlayerPip.allowed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PlayerPip.params(false)?.let { enterPictureInPictureMode(it) }
            } else {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        }
    }

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
        val action = intent?.action ?: return
        if (!action.startsWith(FavoritesWidget.ACTION_PLAY)) return
        val ch = intent.getStringExtra(FavoritesWidget.EXTRA_CHANNEL) ?: return
        val p = intent.getLongExtra(FavoritesWidget.EXTRA_PROFILE, -1L)
        if (p >= 0) WidgetLaunch.pending.value = p to ch
    }
}
