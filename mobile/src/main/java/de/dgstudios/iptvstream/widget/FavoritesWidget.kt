package de.dgstudios.iptvstream.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import de.dgstudios.iptvstream.MainActivity
import de.dgstudios.iptvstream.R
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.ui.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Ein Favorit für das Widget. */
private data class WidgetChannel(val profileId: Long, val streamId: String, val name: String, val now: String?, val logo: Bitmap?)

/** Startbildschirm-Widget mit den Lieblingssendern des aktiven Profils. */
class FavoritesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val active = withTimeoutOrNull(5_000) { c.active.first { it.loaded } }
        val p = active?.profile
        val accentIdx = runCatching { c.settings.current().accent }.getOrDefault(0)
        val accent = Palette.accents[accentIdx.coerceIn(0, Palette.accents.lastIndex)]
        val list = if (p == null) {
            emptyList()
        } else {
            c.db.content().favoriteChannels(p.id).first().take(MAX_ITEMS).map { ch ->
                val now = runCatching { c.repo.nowNext(p.id, ch.epgKey).now?.title }.getOrNull()
                WidgetChannel(p.id, ch.streamId, ch.name, now, loadLogo(context, ch.logo))
            }
        }
        provideContent { Content(context, list, accent) }
    }

    private suspend fun loadLogo(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
            (context.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap(96, 96, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    @Composable
    private fun Content(context: Context, list: List<WidgetChannel>, accent: Color) {
        val size = LocalSize.current
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.widget_bg))
                .cornerRadius(24.dp)
                .padding(10.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(8.dp).background(ColorProvider(accent)).cornerRadius(4.dp)) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    "Favoriten",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.clickable(actionStartActivity(open)),
                )
            }
            if (list.isEmpty()) {
                Box(
                    GlanceModifier.fillMaxSize().clickable(actionStartActivity(open)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Keine Favoriten – tippe zum Öffnen",
                        style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 13.sp),
                    )
                }
                return@Column
            }
            val columns = (size.width.value / 150f).toInt().coerceIn(1, 4)
            if (columns <= 1 || size.height < 120.dp && columns < 2) {
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(list, itemId = { it.streamId.hashCode().toLong() }) { ch ->
                        Column {
                            ChannelCell(context, ch, accent)
                            Spacer(GlanceModifier.height(6.dp))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(columns), GlanceModifier.fillMaxSize()) {
                    items(list, itemId = { it.streamId.hashCode().toLong() }) { ch ->
                        Box(GlanceModifier.padding(3.dp)) { ChannelCell(context, ch, accent) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChannelCell(context: Context, ch: WidgetChannel, accent: Color) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_PLAY + "." + ch.streamId)
            .putExtra(EXTRA_PROFILE, ch.profileId)
            .putExtra(EXTRA_CHANNEL, ch.streamId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Row(
            GlanceModifier
                .fillMaxWidth()
                .height(52.dp)
                .background(ImageProvider(R.drawable.widget_item_bg))
                .cornerRadius(16.dp)
                .padding(horizontal = 8.dp)
                .clickable(actionStartActivity(intent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(36.dp).cornerRadius(10.dp).background(ColorProvider(Color(0x22FFFFFF))), contentAlignment = Alignment.Center) {
                if (ch.logo != null) {
                    Image(ImageProvider(ch.logo), contentDescription = ch.name, modifier = GlanceModifier.size(32.dp))
                } else {
                    Text(
                        ch.name.take(1).uppercase(),
                        style = TextStyle(color = ColorProvider(accent), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    ch.name,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                if (!ch.now.isNullOrBlank()) {
                    Text(
                        ch.now,
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(accent), fontSize = 11.sp),
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "de.dgstudios.iptvstream.WIDGET_PLAY"
        const val EXTRA_PROFILE = "widget_profile"
        const val EXTRA_CHANNEL = "widget_channel"
        private const val MAX_ITEMS = 40

        suspend fun refresh(context: Context) {
            runCatching { FavoritesWidget().updateAll(context) }
        }
    }
}

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}
