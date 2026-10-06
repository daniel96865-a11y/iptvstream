package de.dgstudios.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.Poster
import de.dgstudios.iptvstream.core.vm.SearchViewModel

@Composable
fun GlassTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    val s = LocalAppStyle.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = s.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(s.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { onDone?.invoke() },
            onDone = { onDone?.invoke() },
        ),
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = s.onSurfaceDim, fontSize = 16.sp)
                    inner()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    val s = LocalAppStyle.current
    Text(text, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    settings: AppSettings,
    bottomPad: Dp,
    onPlay: () -> Unit,
    onMovie: (String) -> Unit,
    onSeries: (String) -> Unit,
) {
    val s = LocalAppStyle.current
    val q by vm.query.collectAsStateWithLifecycle()
    val res by vm.results.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Suche")
        GlassTextField(
            value = q,
            onChange = vm::setQuery,
            placeholder = "Sender, Filme, Serien …",
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            imeAction = ImeAction.Search,
            leading = { Icon(Icons.Rounded.Search, null, tint = s.onSurfaceDim, modifier = Modifier.size(22.dp)) },
            trailing = {
                if (q.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Löschen",
                        tint = s.onSurfaceDim,
                        modifier = Modifier.size(20.dp).pressable({ vm.setQuery("") }),
                    )
                }
            },
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPad),
        ) {
            if (q.trim().length < 2) {
                item { EmptyState("Alles durchsuchen", "Mindestens zwei Buchstaben eingeben.") }
            } else if (res.isEmpty) {
                item { EmptyState("Nichts gefunden", "Anderen Suchbegriff versuchen.") }
            }
            if (res.channels.isNotEmpty()) {
                item { SectionTitle("Live TV") }
                items(res.channels, key = { "c" + it.streamId }) { ch ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        SearchChannelRow(ch, settings.showNumbers) {
                            vm.playChannel(ch)
                            onPlay()
                        }
                    }
                }
            }
            if (res.movies.isNotEmpty()) {
                item { SectionTitle("Filme") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(res.movies, key = { it.streamId }) { m ->
                            PosterCard(m.name, m.poster, m.rating, false, { onMovie(m.streamId) }, Modifier.width(112.dp))
                        }
                    }
                }
            }
            if (res.series.isNotEmpty()) {
                item { SectionTitle("Serien") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(res.series, key = { it.seriesId }) { se ->
                            PosterCard(se.name, se.poster, se.rating, false, { onSeries(se.seriesId) }, Modifier.width(112.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchChannelRow(ch: ChannelEntity, showNumber: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(ch.num.toString(), color = s.onSurfaceDim, fontSize = 13.sp, modifier = Modifier.width(38.dp))
        }
        Poster(
            ch.logo,
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1AFFFFFF)).padding(4.dp),
            ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Text(ch.name, color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
