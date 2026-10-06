package de.dgstudios.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.db.CategoryEntity
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.Poster
import de.dgstudios.iptvstream.core.ui.formatRating
import de.dgstudios.iptvstream.core.ui.rememberClockTick
import de.dgstudios.iptvstream.core.ui.rememberNowNext
import de.dgstudios.iptvstream.core.vm.BrowseViewModel
import de.dgstudios.iptvstream.core.vm.LiveViewModel
import de.dgstudios.iptvstream.core.vm.MainViewModel
import de.dgstudios.iptvstream.core.vm.MoviesViewModel
import de.dgstudios.iptvstream.core.vm.SeriesViewModel

/** Horizontale Kategorieauswahl inkl. "Alle", "Favoriten" und "Zuletzt". */
@Composable
fun CategoryRow(categories: List<CategoryEntity>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = Cat.ALL) { GlassChip("Alle", selected == Cat.ALL) { onSelect(Cat.ALL) } }
        item(key = Cat.FAV) { GlassChip("★ Favoriten", selected == Cat.FAV) { onSelect(Cat.FAV) } }
        item(key = Cat.RECENT) { GlassChip("Zuletzt", selected == Cat.RECENT) { onSelect(Cat.RECENT) } }
        items(categories, key = { it.id }) { c ->
            GlassChip(c.name, selected == c.id) { onSelect(c.id) }
        }
    }
}

@Composable
fun LiveScreen(
    vm: LiveViewModel,
    mainVm: MainViewModel,
    settings: AppSettings,
    bottomPad: Dp,
    onPlay: () -> Unit,
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val sync by mainVm.sync.collectAsStateWithLifecycle()
    val tick by rememberClockTick()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Live TV", if (items.isNotEmpty()) "${items.size} Sender" else null)
        CategoryRow(categories, selected, vm::selectCategory)
        if (!restored) return@Column

        val listState = rememberLazyListState(vm.scrollIndex, vm.scrollOffset)
        val hasItems = items.isNotEmpty()
        LaunchedEffect(hasItems, selected) {
            if (!hasItems) return@LaunchedEffect
            listState.scrollToItem(vm.scrollIndex.coerceAtMost(items.lastIndex), vm.scrollOffset)
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (i, o) -> vm.onScroll(i, o) }
        }

        if (items.isEmpty()) {
            when {
                sync.running -> EmptyState("Sender werden geladen …", sync.stage, busy = true)
                selected == Cat.FAV -> EmptyState("Noch keine Favoriten", "Tippe auf den Stern bei einem Sender.")
                selected == Cat.RECENT -> EmptyState("Noch nichts gesehen", "Zuletzt gesehene Sender erscheinen hier.")
                else -> EmptyState("Keine Sender gefunden", "Unter Einstellungen → Inhalte neu laden.")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.streamId }, contentType = { "channel" }) { ch ->
                    ChannelRow(
                        ch = ch,
                        showNumber = settings.showNumbers,
                        fav = ch.streamId in favs,
                        tick = tick,
                        onClick = {
                            vm.play(ch)
                            onPlay()
                        },
                        onFav = { vm.toggleFavorite(ch.streamId) },
                        onStart = { vm.setStartChannel(ch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: ChannelEntity,
    showNumber: Boolean,
    fav: Boolean,
    tick: Long,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onStart: () -> Unit,
) {
    val s = LocalAppStyle.current
    val nowNext by rememberNowNext(ch.profileId, ch.epgKey, tick)
    var menu by remember { mutableStateOf(false) }
    val nn = nowNext
    val cur = nn?.now
    val next = nn?.next

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp))
                .pressable(onClick, onLongClick = { menu = true })
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber) {
                Text(
                    ch.num.toString(),
                    color = s.onSurfaceDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(38.dp),
                )
            }
            Poster(
                ch.logo,
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1AFFFFFF)).padding(5.dp),
                ContentScale.Fit,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name,
                    color = s.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cur != null) {
                    Text(cur.title, color = s.onSurface.copy(alpha = 0.85f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { nn?.progress(tick) ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = s.accent,
                        trackColor = Color.White.copy(alpha = 0.14f),
                    )
                    if (next != null) {
                        Text(
                            "Danach: ${next.title}",
                            color = s.onSurfaceDim,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                } else {
                    Text("Keine Programminfo", color = s.onSurfaceDim, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onFav) {
                Icon(
                    if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "Favorit",
                    tint = if (fav) Color(0xFFFFC857) else s.onSurfaceDim,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") },
                onClick = {
                    menu = false
                    onFav()
                },
            )
            DropdownMenuItem(
                text = { Text("Als Startsender festlegen") },
                onClick = {
                    menu = false
                    onStart()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- Filme & Serien

@Composable
fun MoviesScreen(vm: MoviesViewModel, bottomPad: Dp, onOpen: (String) -> Unit) {
    PosterBrowse(
        title = "Filme",
        vm = vm,
        bottomPad = bottomPad,
        key = { it.streamId },
        name = { it.name },
        image = { it.poster },
        rating = { it.rating },
        onOpen = onOpen,
    )
}

@Composable
fun SeriesScreen(vm: SeriesViewModel, bottomPad: Dp, onOpen: (String) -> Unit) {
    PosterBrowse(
        title = "Serien",
        vm = vm,
        bottomPad = bottomPad,
        key = { it.seriesId },
        name = { it.name },
        image = { it.poster },
        rating = { it.rating },
        onOpen = onOpen,
    )
}

@Composable
private fun <T> PosterBrowse(
    title: String,
    vm: BrowseViewModel<T>,
    bottomPad: Dp,
    key: (T) -> String,
    name: (T) -> String,
    image: (T) -> String?,
    rating: (T) -> Double,
    onOpen: (String) -> Unit,
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, if (items.isNotEmpty()) "${items.size} Titel" else null)
        CategoryRow(categories, selected, vm::selectCategory)
        if (!restored) return@Column

        val gridState = rememberLazyGridState(vm.scrollIndex, vm.scrollOffset)
        val hasItems = items.isNotEmpty()
        LaunchedEffect(hasItems, selected) {
            if (!hasItems) return@LaunchedEffect
            gridState.scrollToItem(vm.scrollIndex.coerceAtMost(items.lastIndex), vm.scrollOffset)
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .collect { (i, o) -> vm.onScroll(i, o) }
        }

        if (items.isEmpty()) {
            when (selected) {
                Cat.FAV -> EmptyState("Noch keine Favoriten", "Favoriten setzt du in der Detailansicht.")
                Cat.RECENT -> EmptyState("Noch nichts angesehen")
                else -> EmptyState("Keine Titel gefunden", "Unter Einstellungen → Inhalte neu laden.")
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPad),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { key(it) }, contentType = { "poster" }) { item ->
                    val id = key(item)
                    PosterCard(
                        title = name(item),
                        image = image(item),
                        rating = rating(item),
                        fav = id in favs,
                        onClick = { onOpen(id) },
                    )
                }
            }
        }
    }
}

@Composable
fun PosterCard(title: String, image: String?, rating: Double, fav: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.pressable(onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .glass(shape),
        ) {
            Poster(image, Modifier.fillMaxSize())
            if (fav) StarBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            val r = formatRating(rating)
            if (r != null) {
                Text(
                    r,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            title,
            color = s.onSurface,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
