package de.dgstudios.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.coroutines.flow.first

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

/** Leiste wie bisher, oder ein Knopf, der die durchsuchbare Schublade öffnet. */
@Composable
fun CategoryPicker(
    categories: List<CategoryEntity>,
    selected: String,
    drawer: Boolean,
    onSelect: (String) -> Unit,
    drawerIndex: Int = 0,
    drawerOffset: Int = 0,
    onDrawerScroll: (Int, Int) -> Unit = { _, _ -> },
) {
    if (!drawer) {
        CategoryRow(categories, selected, onSelect)
        return
    }
    var open by remember { mutableStateOf(false) }
    val outerBottom = drawerSystemBottom()
    val listState = rememberLazyListState(drawerIndex, drawerOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> onDrawerScroll(i, o) }
    }
    val label = Cat.label(selected, categories.firstOrNull { it.id == selected }?.name)
    val s = LocalAppStyle.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(36.dp)
            .glass(RoundedCornerShape(50))
            .pressable({ open = true })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Menu, contentDescription = "Kategorien öffnen", tint = s.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = s.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) {
        CategoryDrawer(
            categories = categories,
            selected = selected,
            listState = listState,
            outerBottom = outerBottom,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun CategoryDrawer(
    categories: List<CategoryEntity>,
    selected: String,
    listState: LazyListState,
    outerBottom: Dp,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStyle.current
    var query by remember { mutableStateOf("") }
    val entries = remember(categories) {
        buildList {
            add(Cat.ALL to Cat.label(Cat.ALL))
            add(Cat.FAV to Cat.label(Cat.FAV))
            add(Cat.RECENT to Cat.label(Cat.RECENT))
            categories.forEach { add(it.id to it.name) }
        }
    }
    val shown = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { it.second.contains(q, ignoreCase = true) }
    }
    val filterState = rememberLazyListState()
    val browsingAll = query.isBlank()
    // Dialog liefert die Navigationsleiste oft nicht. Der Wert von außen (Activity) bleibt gültig.
    val listBottom = maxOf(drawerSystemBottom(), outerBottom) + 48.dp
    LaunchedEffect(browsingAll, selected, entries) {
        if (!browsingAll) {
            filterState.scrollToItem(0)
            return@LaunchedEffect
        }
        val index = entries.indexOfFirst { it.first == selected }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        listState.reveal(index)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 420.dp)
                    .background(s.backgroundColors[0])
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("Kategorien", color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                DrawerSearch(query) { query = it }
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) {
                    Text("Keine Kategorie", color = s.onSurfaceDim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                } else {
                    LazyColumn(
                        state = if (browsingAll) listState else filterState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = listBottom),
                    ) {
                        items(shown, key = { it.first }) { (id, name) ->
                            CategoryDrawerRow(name, id == selected) { onSelect(id) }
                        }
                    }
                }
            }
        }
    }
}

/** Deckende Karte, gleiche Ecke und Schrift wie die Senderliste, ohne durchscheinendes Glas. */
@Composable
private fun CategoryDrawerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(18.dp)
    val fill = if (selected) solidMix(s.backgroundColors[2], s.accent, 0.5f) else s.backgroundColors[2]
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill, shape)
            .then(if (selected) Modifier.border(1.5.dp, s.accent, shape) else Modifier)
            .pressable({ onClick() })
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name,
            color = s.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Suchfeld ohne zusätzliche Haarlinie über der Fläche. */
@Composable
private fun DrawerSearch(value: String, onChange: (String) -> Unit) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = s.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(s.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(s.backgroundColors[2], shape)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text("Kategorie suchen", color = s.onSurfaceDim, fontSize = 16.sp)
                }
                inner()
            }
        },
    )
}

private fun solidMix(base: Color, accent: Color, amount: Float): Color = Color(
    red = base.red * (1f - amount) + accent.red * amount,
    green = base.green * (1f - amount) + accent.green * amount,
    blue = base.blue * (1f - amount) + accent.blue * amount,
    alpha = 1f,
)

/** Unterer Systembereich (Navigation, Gestenleiste, Tastatur), wie ihn das aktuelle Fenster meldet. */
@Composable
private fun drawerSystemBottom(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safe = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val imePad = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    return maxOf(nav, safe, imePad)
}

/** Zeigt die gewählte Kategorie, ohne eine fast weggescrollte Zeile als Haarlinie stehen zu lassen. */
private suspend fun LazyListState.reveal(index: Int) {
    if (index < 0) return
    snapshotFlow { layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
    fun visibleFully(): Boolean {
        val info = layoutInfo
        val vis = info.visibleItemsInfo.find { it.index == index } ?: return false
        return vis.offset >= info.viewportStartOffset && vis.offset + vis.size <= info.viewportEndOffset
    }
    if (!visibleFully()) scrollToItem(index)
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    if (first != null && first.index != index && first.offset < info.viewportStartOffset) {
        val shown = first.size - (info.viewportStartOffset - first.offset)
        if (shown in 1 until 24) scrollToItem(first.index + 1)
    }
    if (!visibleFully()) scrollToItem(index)
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
        CategoryPicker(
            categories,
            selected,
            settings.categoryDrawer,
            vm::selectCategory,
            drawerIndex = vm.drawerIndex,
            drawerOffset = vm.drawerOffset,
            onDrawerScroll = vm::onDrawerScroll,
        )
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
fun MoviesScreen(vm: MoviesViewModel, settings: AppSettings, bottomPad: Dp, onOpen: (String) -> Unit) {
    PosterBrowse(
        title = "Filme",
        vm = vm,
        drawer = settings.categoryDrawer,
        bottomPad = bottomPad,
        key = { it.streamId },
        name = { it.name },
        image = { it.poster },
        rating = { it.rating },
        onOpen = onOpen,
    )
}

@Composable
fun SeriesScreen(vm: SeriesViewModel, settings: AppSettings, bottomPad: Dp, onOpen: (String) -> Unit) {
    PosterBrowse(
        title = "Serien",
        vm = vm,
        drawer = settings.categoryDrawer,
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
    drawer: Boolean,
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
        CategoryPicker(
            categories,
            selected,
            drawer,
            vm::selectCategory,
            drawerIndex = vm.drawerIndex,
            drawerOffset = vm.drawerOffset,
            onDrawerScroll = vm::onDrawerScroll,
        )
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
