package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.EpisodeInfo
import de.dgstudios.iptvstream.core.data.WatchPos
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.Poster
import de.dgstudios.iptvstream.core.ui.formatDuration
import de.dgstudios.iptvstream.core.ui.formatRating
import de.dgstudios.iptvstream.core.vm.BrowseViewModel
import de.dgstudios.iptvstream.core.vm.MovieDetailViewModel
import de.dgstudios.iptvstream.core.vm.MoviesViewModel
import de.dgstudios.iptvstream.core.vm.SeriesDetailViewModel
import de.dgstudios.iptvstream.core.vm.SeriesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PosterItem(val id: String, val name: String, val poster: String?, val rating: Double)

@Composable
fun TvMoviesScreen(
    vm: MoviesViewModel,
    settings: AppSettings,
    entry: EntryHandle,
    first: FirstFocus,
    onOpen: (String) -> Unit,
    onEscapeUp: () -> Unit,
) {
    PosterBrowse(vm, settings.categoryDrawer, entry, first, onOpen, onEscapeUp, toItem = { PosterItem(it.streamId, it.name, it.poster, it.rating) }, emptyFav = "Favoriten setzt du in der Detailansicht.")
}

@Composable
fun TvSeriesScreen(
    vm: SeriesViewModel,
    settings: AppSettings,
    entry: EntryHandle,
    first: FirstFocus,
    onOpen: (String) -> Unit,
    onEscapeUp: () -> Unit,
) {
    PosterBrowse(vm, settings.categoryDrawer, entry, first, onOpen, onEscapeUp, toItem = { PosterItem(it.seriesId, it.name, it.poster, it.rating) }, emptyFav = "Favoriten setzt du in der Detailansicht.")
}

@Composable
private fun <T> PosterBrowse(
    vm: BrowseViewModel<T>,
    drawer: Boolean,
    entry: EntryHandle,
    first: FirstFocus,
    onOpen: (String) -> Unit,
    onEscapeUp: () -> Unit,
    toItem: (T) -> PosterItem,
    emptyFav: String,
) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    val gridReg = remember { FocusRegistry() }
    val catReg = remember { FocusRegistry() }
    val scope = rememberCoroutineScope()
    val itemsRef = rememberUpdatedState(items)
    val selectedRef = rememberUpdatedState(selected)
    var pendingFrom by remember { mutableStateOf<List<T>?>(null) }
    var gridFocused by remember { mutableStateOf(false) }
    val catItems = remember(cats) { buildCatItems(cats) }
    val catItemsRef = rememberUpdatedState(catItems)
    val openButton = remember { FocusRequester() }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerListState = rememberLazyListState(vm.drawerIndex, vm.drawerOffset)
    val drawerBottom = drawerSystemBottom()
    var railOpen by remember { mutableStateOf(true) }
    val move = remember { MoveGate() }
    val escapeRef = rememberUpdatedState(onEscapeUp)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var skipFirst = !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            if (skipFirst) {
                skipFirst = false
                return@LifecycleEventObserver
            }
            railOpen = true
            vm.focusIndex = 0
            vm.onScroll(0, 0)
            scope.launch {
                gridState.scrollToItem(0)
                escapeRef.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(drawerListState) {
        snapshotFlow { drawerListState.firstVisibleItemIndex to drawerListState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.onDrawerScroll(i, o) }
    }

    fun enter() {
        first.pending = false
        move.launch(scope) {
            val list = itemsRef.value
            if (list.isNotEmpty()) {
                if (!drawer) railOpen = false
                val idx = gridState.firstVisibleItemIndex.coerceIn(0, list.lastIndex)
                gridState.scrollToItem(idx)
                if (!gridReg.focus(idx)) {
                    gridState.scrollToItem(0)
                    if (!gridReg.focus(0)) onEscapeUp()
                }
            } else if (drawer) {
                try {
                    openButton.requestFocus()
                } catch (_: IllegalStateException) {
                }
            } else {
                railOpen = true
                val ci = catItemsRef.value.indexOfFirst { it.id == selectedRef.value }.coerceAtLeast(0)
                if (!catReg.focus(ci)) onEscapeUp()
            }
        }
    }

    fun openRail() {
        railOpen = true
        move.launch(scope) {
            delay(240)
            val ci = catItemsRef.value.indexOfFirst { it.id == selectedRef.value }.coerceAtLeast(0)
            if (!catReg.focus(ci)) onEscapeUp()
        }
    }

    fun stepUp(index: Int) {
        move.launch(scope) {
            val cols = gridColumns(gridState)
            val above = index - cols
            if (above < 0) {
                if (drawer) {
                    try {
                        openButton.requestFocus()
                    } catch (_: IllegalStateException) {
                        onEscapeUp()
                    }
                } else {
                    onEscapeUp()
                }
            } else {
                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == above }) {
                    gridState.scrollToItem(above)
                }
                if (!gridReg.focus(above)) {
                    gridState.scrollToItem(above)
                    if (!gridReg.focus(above)) onEscapeUp()
                }
            }
        }
    }

    fun stepDown(index: Int) {
        move.launch(scope) {
            val list = itemsRef.value
            val cols = gridColumns(gridState)
            val row = index / cols
            val lastRow = list.lastIndex / cols
            if (row >= lastRow) return@launch
            // Letzte Zeile kürzer: auf den letzten Eintrag gehen.
            val below = (index + cols).coerceAtMost(list.lastIndex)
            gridState.focusItem(gridReg, below, row * cols)
        }
    }

    fun stepLeft(index: Int) {
        move.launch(scope) {
            val col = gridState.layoutInfo.visibleItemsInfo.find { it.index == index }?.column ?: 0
            if (col > 0) {
                val left = index - 1
                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == left }) {
                    gridState.scrollToItem(left)
                }
                if (!gridReg.focus(left)) onEscapeUp()
            } else {
                railOpen = true
                delay(240)
                val ci = catItemsRef.value.indexOfFirst { it.id == selectedRef.value }.coerceAtLeast(0)
                if (!catReg.focus(ci)) onEscapeUp()
            }
        }
    }

    fun chooseCategory(id: String) {
        if (id == selected) {
            enter()
        } else {
            pendingFrom = items
            vm.selectCategory(id)
        }
    }
    RegisterEntry(entry) { enter() }

    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        first.pending = false
        vm.focusIndex = 0
        vm.onScroll(0, 0)
        gridState.scrollToItem(0)
    }

    LaunchedEffect(items.firstOrNull()?.let { toItem(it).id }) {
        if (!gridFocused && pendingFrom == null && items.isNotEmpty() && gridState.firstVisibleItemIndex != 0) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(items, pendingFrom) {
        val p = pendingFrom ?: return@LaunchedEffect
        if (items !== p) {
            pendingFrom = null
            gridState.scrollToItem(0)
            if (items.isNotEmpty()) gridReg.focus(0)
        }
    }
    LaunchedEffect(pendingFrom) {
        if (pendingFrom != null) {
            delay(2_000)
            pendingFrom = null
        }
    }

    @Composable
    fun Posters() {
        if (items.isEmpty()) {
            when (selected) {
                Cat.FAV -> TvEmpty("Noch keine Favoriten", emptyFav)
                Cat.RECENT -> TvEmpty("Noch nichts angesehen")
                else -> TvEmpty("Keine Einträge", "Inhalte werden geladen oder die Kategorie ist leer.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(138.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().onFocusChanged { gridFocused = it.hasFocus },
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items, key = { _, it -> toItem(it).id }) { index, raw ->
                    val p = toItem(raw)
                    PosterCell(
                        p = p,
                        index = index,
                        registry = gridReg,
                        onFocus = { if (!drawer) railOpen = false },
                        onUp = { stepUp(index) },
                        onDown = { stepDown(index) },
                        onLeft = if (drawer) null else ({ stepLeft(index) }),
                        onClick = { onOpen(p.id) },
                    )
                }
            }
        }
    }

    if (drawer) {
        Column(Modifier.fillMaxSize()) {
            TvButton(
                text = "Kategorien: ${Cat.label(selected, cats.firstOrNull { it.id == selected }?.name)}",
                onClick = { drawerOpen = true },
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                icon = Icons.Rounded.Menu,
                requester = openButton,
            )
            Box(Modifier.weight(1f).fillMaxSize()) { Posters() }
        }
        if (drawerOpen) {
            TvCategoryDrawer(
                items = catItems,
                selected = selected,
                listState = drawerListState,
                outerBottom = drawerBottom,
                onSelect = {
                    drawerOpen = false
                    chooseCategory(it)
                },
                onDismiss = { drawerOpen = false },
            )
        }
    } else {
        TvSplitRail(
            open = railOpen,
            rail = { TvCategoryList(catItems, selected, catReg, ::chooseCategory, onEscapeUp, onRight = ::enter) },
            content = { Posters() },
        )
    }
}

@Composable
private fun PosterCell(
    p: PosterItem,
    index: Int,
    registry: FocusRegistry,
    onFocus: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val s = LocalAppStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .registered(registry, index)
            .tvMove(onUp = onUp, onLeft = onLeft, onDown = onDown)
            .tvFocus(onClick = onClick, shape = RoundedCornerShape(12.dp), onFocus = { if (it) onFocus() })
            .padding(6.dp),
    ) {
        Poster(p.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
        formatRating(p.rating)?.let { Text(it, color = s.onSurfaceDim, fontSize = 13.sp) }
    }
}

// ---------------------------------------------------------------------- Details

@Composable
private fun Backdrop(url: String?) {
    val s = LocalAppStyle.current
    Box(Modifier.fillMaxSize()) {
        Poster(url, Modifier.fillMaxSize().graphicsLayer { alpha = 0.30f })
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, s.backgroundColors[0].copy(alpha = 0.92f), s.backgroundColors[0]))),
        )
    }
}

private fun metaLine(parts: List<String?>): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")

private fun gridColumns(state: LazyGridState): Int {
    val cols = state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0
    return (cols + 1).coerceAtLeast(1)
}

internal suspend fun FocusRequester.tryFocus() {
    repeat(12) {
        withFrameNanos { }
        try {
            requestFocus()
            return
        } catch (e: IllegalStateException) {
        }
    }
}

@Composable
fun TvMovieDetail(onPlay: () -> Unit) {
    val s = LocalAppStyle.current
    val vm: MovieDetailViewModel = viewModel()
    val movie by vm.movie.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val m = movie

    LaunchedEffect(m != null) { if (m != null) playFocus.tryFocus() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(detail?.backdrop ?: m?.poster)
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notFound) Text("Film nicht gefunden", color = s.onSurface, fontSize = 22.sp) else CircularProgressIndicator(color = s.accent)
            }
            return@Box
        }
        val d = detail
        val resume = progress?.positionMs ?: 0L
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
            Poster(m.poster, Modifier.width(240.dp).height(360.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, color = s.onSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val meta = metaLine(
                    listOf(
                        d?.year ?: m.year,
                        d?.durationMin?.takeIf { it > 0 }?.let { "$it Min." },
                        formatRating(d?.rating?.takeIf { it > 0 } ?: m.rating),
                        d?.genre,
                    ),
                )
                if (meta.isNotEmpty()) Text(meta, color = s.onSurfaceDim, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (resume > 10_000) {
                        TvButton("Fortsetzen ab ${formatDuration(resume)}", {
                            vm.play(false)
                            onPlay()
                        }, icon = Icons.Rounded.PlayArrow, requester = playFocus)
                        TvButton("Von vorn", {
                            vm.play(true)
                            onPlay()
                        })
                    } else {
                        TvButton("Abspielen", {
                            vm.play(false)
                            onPlay()
                        }, icon = Icons.Rounded.PlayArrow, requester = playFocus)
                    }
                    TvButton(
                        if (fav) "Favorit" else "Merken",
                        { vm.toggleFavorite() },
                        icon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    )
                }
                val plot = d?.plot
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Text(plot, color = s.onSurface.copy(alpha = 0.9f), fontSize = 17.sp, lineHeight = 24.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
                }
                val cast = d?.cast
                if (!cast.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Besetzung: $cast", color = s.onSurfaceDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val director = d?.director
                if (!director.isNullOrBlank()) {
                    Text("Regie: $director", color = s.onSurfaceDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun TvSeriesDetail(onPlay: () -> Unit) {
    val s = LocalAppStyle.current
    val vm: SeriesDetailViewModel = viewModel()
    val series by vm.series.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val season by vm.selectedSeason.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val sr = series
    val d = detail

    LaunchedEffect(sr != null, loading) { if (sr != null) playFocus.tryFocus() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdrop ?: sr?.poster)
        if (sr == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notFound) Text("Serie nicht gefunden", color = s.onSurface, fontSize = 22.sp) else CircularProgressIndicator(color = s.accent)
            }
            return@Box
        }
        val progressById = progress.associateBy { it.itemId }
        val next = d?.let { vm.continueEpisode(it, progress) }
        val episodes = d?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row {
                    Poster(sr.poster, Modifier.width(170.dp).height(255.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sr.name, color = s.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        val meta = metaLine(listOf(d?.year ?: sr.year, formatRating(d?.rating?.takeIf { it > 0 } ?: sr.rating), d?.genre ?: sr.genre))
                        if (meta.isNotEmpty()) Text(meta, color = s.onSurfaceDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (next != null) {
                                TvButton(
                                    "Weiter: S%02d E%02d".format(next.season, next.number),
                                    {
                                        vm.play(next)
                                        onPlay()
                                    },
                                    icon = Icons.Rounded.PlayArrow,
                                    requester = playFocus,
                                )
                            }
                            TvButton(
                                if (fav) "Favorit" else "Merken",
                                { vm.toggleFavorite() },
                                icon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                requester = if (next == null) playFocus else null,
                            )
                        }
                        val plot = d?.plot ?: sr.plot
                        if (!plot.isNullOrBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(plot, color = s.onSurface.copy(alpha = 0.9f), fontSize = 16.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (loading) {
                item { TvEmpty("Staffeln werden geladen …", busy = true) }
            } else if (d == null || d.seasons.isEmpty()) {
                item { TvEmpty("Keine Episoden gefunden", "Der Anbieter liefert für diese Serie keine Folgen.") }
            } else {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        items(d.seasons, key = { it.number }) { se ->
                            TvButton("Staffel ${se.number}", { vm.selectSeason(se.number) }, selected = se.number == season)
                        }
                    }
                }
                items(episodes, key = { it.id }) { ep ->
                    val pr = progressById[ep.id]
                    EpisodeRow(ep, pr?.positionMs ?: 0L, pr?.durationMs ?: 0L, pr != null) {
                        vm.play(ep)
                        onPlay()
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, positionMs: Long, durationMs: Long, hasProgress: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    val watched = hasProgress && positionMs == WatchPos.COMPLETED_MS
    val fraction = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("E${ep.number}", color = s.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(60.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ep.durationSec > 0) Text("${ep.durationSec / 60} Min.", color = s.onSurfaceDim, fontSize = 14.sp)
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.14f),
                )
            }
        }
        if (watched) Text("✓", color = s.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
