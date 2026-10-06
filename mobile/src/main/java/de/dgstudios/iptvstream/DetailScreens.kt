package de.dgstudios.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dgstudios.iptvstream.core.data.EpisodeInfo
import de.dgstudios.iptvstream.core.data.WatchPos
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.Poster
import de.dgstudios.iptvstream.core.ui.formatDuration
import de.dgstudios.iptvstream.core.ui.formatRating
import de.dgstudios.iptvstream.core.vm.MovieDetailViewModel
import de.dgstudios.iptvstream.core.vm.SeriesDetailViewModel

@Composable
fun GlassIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color? = null) {
    val s = LocalAppStyle.current
    Box(
        Modifier.size(44.dp).glass(CircleShape).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint ?: s.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalAppStyle.current
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(s.accent, RoundedCornerShape(50))
            .pressable(onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalAppStyle.current
    Box(
        modifier.height(48.dp).glass(RoundedCornerShape(50)).pressable(onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = s.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun Backdrop(url: String?) {
    val s = LocalAppStyle.current
    Box(Modifier.fillMaxSize()) {
        Poster(url, Modifier.fillMaxSize().graphicsLayer { alpha = 0.32f })
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, s.backgroundColors[0].copy(alpha = 0.92f), s.backgroundColors[0]))),
        )
    }
}

@Composable
private fun MetaLine(parts: List<String?>) {
    val s = LocalAppStyle.current
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotEmpty()) Text(text, color = s.onSurfaceDim, fontSize = 13.sp)
}

@Composable
fun MovieDetailScreen(onBack: () -> Unit, onPlay: () -> Unit) {
    val s = LocalAppStyle.current
    val vm: MovieDetailViewModel = viewModel()
    val movie by vm.movie.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()

    val m = movie
    Box(Modifier.fillMaxSize()) {
        Backdrop(detail?.backdrop ?: m?.poster)
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notFound) Text("Film nicht gefunden", color = s.onSurface) else CircularProgressIndicator(color = s.accent)
            }
        } else {
            val d = detail
            val resume = progress?.positionMs ?: 0L
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack)
                    GlassIconButton(
                        if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        "Favorit",
                        { vm.toggleFavorite() },
                        tint = if (fav) Color(0xFFFFC857) else null,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    Poster(
                        m.poster,
                        Modifier.width(120.dp).height(180.dp).glass(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        MetaLine(
                            listOf(
                                d?.year ?: m.year,
                                d?.durationMin?.takeIf { it > 0 }?.let { "$it Min." },
                                formatRating(d?.rating?.takeIf { it > 0 } ?: m.rating),
                            ),
                        )
                        d?.genre?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = s.onSurfaceDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (resume > 10_000) {
                        PrimaryButton("Fortsetzen ab ${formatDuration(resume)}", Icons.Rounded.PlayArrow, {
                            vm.play(false)
                            onPlay()
                        })
                        SecondaryButton("Von vorn", {
                            vm.play(true)
                            onPlay()
                        })
                    } else {
                        PrimaryButton("Abspielen", Icons.Rounded.PlayArrow, {
                            vm.play(false)
                            onPlay()
                        })
                    }
                }
                val plot = d?.plot
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(plot, color = s.onSurface.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
                }
                val cast = d?.cast
                if (!cast.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Besetzung", color = s.onSurfaceDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(cast, color = s.onSurface.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                val director = d?.director
                if (!director.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Regie", color = s.onSurfaceDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(director, color = s.onSurface.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(onBack: () -> Unit, onPlay: () -> Unit) {
    val s = LocalAppStyle.current
    val vm: SeriesDetailViewModel = viewModel()
    val series by vm.series.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val season by vm.selectedSeason.collectAsStateWithLifecycle()

    val sr = series
    Box(Modifier.fillMaxSize()) {
        Backdrop(detail?.backdrop ?: sr?.poster)
        if (sr == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notFound) Text("Serie nicht gefunden", color = s.onSurface) else CircularProgressIndicator(color = s.accent)
            }
            return@Box
        }
        val d = detail
        val progressById = progress.associateBy { it.itemId }
        val next = d?.let { vm.continueEpisode(it, progress) }
        val episodes = d?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty()

        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack)
                    GlassIconButton(
                        if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        "Favorit",
                        { vm.toggleFavorite() },
                        tint = if (fav) Color(0xFFFFC857) else null,
                    )
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp)) {
                    Poster(sr.poster, Modifier.width(120.dp).height(180.dp).glass(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sr.name, color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        MetaLine(listOf(d?.year ?: sr.year, formatRating(d?.rating?.takeIf { it > 0 } ?: sr.rating)))
                        (d?.genre ?: sr.genre)?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = s.onSurfaceDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val plot = d?.plot ?: sr.plot
            if (!plot.isNullOrBlank()) {
                item {
                    Text(plot, color = s.onSurface.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            if (loading) {
                item { EmptyState("Staffeln werden geladen …", busy = true) }
            } else if (d == null || d.seasons.isEmpty()) {
                item { EmptyState("Keine Episoden gefunden", "Der Anbieter liefert für diese Serie keine Folgen.") }
            } else {
                if (next != null) {
                    item {
                        val label = "Weiter: S%02d E%02d".format(next.season, next.number)
                        PrimaryButton(label, Icons.Rounded.PlayArrow, {
                            vm.play(next)
                            onPlay()
                        }, Modifier.padding(vertical = 6.dp))
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(d.seasons, key = { it.number }) { se ->
                            GlassChip("Staffel ${se.number}", se.number == season) { vm.selectSeason(se.number) }
                        }
                    }
                }
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(ep, progressById[ep.id]?.let { it.positionMs to it.durationMs }, progressById.containsKey(ep.id)) {
                        vm.play(ep)
                        onPlay()
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, pos: Pair<Long, Long>?, hasProgress: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    val watched = hasProgress && (pos?.first ?: 0L) == WatchPos.COMPLETED_MS
    val fraction = if (pos != null && pos.second > 0 && pos.first > 0) (pos.first.toFloat() / pos.second).coerceIn(0f, 1f) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "E${ep.number}",
            color = s.accent,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = s.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ep.durationSec > 0) {
                Text("${ep.durationSec / 60} Min.", color = s.onSurfaceDim, fontSize = 12.sp)
            }
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.14f),
                )
            }
        }
        if (watched) {
            Icon(Icons.Rounded.Check, contentDescription = "Gesehen", tint = s.accent, modifier = Modifier.size(22.dp))
        }
    }
}
