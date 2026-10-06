package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.Poster
import de.dgstudios.iptvstream.core.vm.SearchViewModel

@Composable
fun TvSearchScreen(
    vm: SearchViewModel,
    settings: AppSettings,
    entry: EntryHandle,
    onPlay: () -> Unit,
    onMovie: (String) -> Unit,
    onSeries: (String) -> Unit,
) {
    val s = LocalAppStyle.current
    val q by vm.query.collectAsStateWithLifecycle()
    val res by vm.results.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }
    RegisterEntry(entry) {
        try {
            inputFocus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "input") {
            TvField(
                label = "Suche (Sender, Filme, Serien)",
                value = q,
                onChange = { vm.setQuery(it) },
                modifier = Modifier.focusRequesterIf(inputFocus),
            )
        }
        if (q.trim().length < 2) {
            item(key = "hint") { TvEmpty("Alles durchsuchen", "Feld auswählen und mindestens zwei Buchstaben eingeben.") }
        } else if (res.isEmpty) {
            item(key = "none") { TvEmpty("Nichts gefunden", "Anderen Suchbegriff versuchen.") }
        }
        if (res.channels.isNotEmpty()) {
            item(key = "h_live") { SectionTitle("Live TV") }
            items(res.channels, key = { "c" + it.streamId }) { ch ->
                SearchChannel(ch, settings.showNumbers) {
                    vm.playChannel(ch)
                    onPlay()
                }
            }
        }
        if (res.movies.isNotEmpty()) {
            item(key = "h_movies") { SectionTitle("Filme") }
            item(key = "movies") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(6.dp)) {
                    items(res.movies, key = { it.streamId }) { m ->
                        SearchPoster(m.name, m.poster) { onMovie(m.streamId) }
                    }
                }
            }
        }
        if (res.series.isNotEmpty()) {
            item(key = "h_series") { SectionTitle("Serien") }
            item(key = "series") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(6.dp)) {
                    items(res.series, key = { it.seriesId }) { se ->
                        SearchPoster(se.name, se.poster) { onSeries(se.seriesId) }
                    }
                }
            }
        }
    }
}

private fun Modifier.focusRequesterIf(r: FocusRequester): Modifier = this.then(Modifier.focusRequester(r))

@Composable
private fun SectionTitle(text: String) {
    val s = LocalAppStyle.current
    Text(text, color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun SearchChannel(ch: ChannelEntity, showNumber: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .tvFocus(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) Text(ch.num.toString(), color = s.onSurfaceDim, fontSize = 17.sp, modifier = Modifier.width(60.dp))
        Poster(ch.logo, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).padding(3.dp), ContentScale.Fit)
        Spacer(Modifier.width(14.dp))
        Text(ch.name, color = s.onSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchPoster(name: String, poster: String?, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Column(
        Modifier
            .width(140.dp)
            .tvFocus(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(6.dp),
    ) {
        Poster(poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(6.dp))
        Text(name, color = s.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
    }
}
