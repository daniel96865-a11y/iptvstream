package de.dgstudios.iptvstream

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Search
import de.dgstudios.iptvstream.core.ui.AccentBar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import de.dgstudios.iptvstream.core.data.ListOrder
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import de.dgstudios.iptvstream.core.ui.PosterFrame
import de.dgstudios.iptvstream.core.ui.animatedProgress
import de.dgstudios.iptvstream.core.ui.popIn
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
fun CategoryRow(
    categories: List<CategoryEntity>,
    selected: String,
    onSelect: (String) -> Unit,
    hidden: List<CategoryEntity> = emptyList(),
    onCategoryMenu: (String) -> Unit = {},
    onUnhide: (String) -> Unit = {},
    onReorder: ((List<String>) -> Unit)? = null,
) {
    var local by remember { mutableStateOf(categories) }
    var dragging by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }
    LaunchedEffect(categories) { if (!dragging) local = categories }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        if (fromId == toId) return@rememberReorderableLazyListState
        if (fromId in setOf(Cat.ALL, Cat.FAV, Cat.RECENT) || toId in setOf(Cat.ALL, Cat.FAV, Cat.RECENT)) return@rememberReorderableLazyListState
        if (fromId.startsWith("hidden-") || toId.startsWith("hidden-")) return@rememberReorderableLazyListState
        local = ListOrder.moveByKey(local, { it.id }, fromId, toId)
        moved = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = Cat.ALL) { GlassChip("Alle", selected == Cat.ALL) { onSelect(Cat.ALL) } }
        item(key = Cat.FAV) { GlassChip("★ Favoriten", selected == Cat.FAV) { onSelect(Cat.FAV) } }
        item(key = Cat.RECENT) { GlassChip("Zuletzt", selected == Cat.RECENT) { onSelect(Cat.RECENT) } }
        items(local, key = { it.id }) { c ->
            ReorderableItem(reorder, c.id, enabled = onReorder != null) { isDragging ->
                GlassChip(
                    text = c.name,
                    selected = selected == c.id,
                    lifted = isDragging,
                    modifier = if (onReorder != null) Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            dragging = true
                            moved = false
                            suppressClick = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            dragging = false
                            if (moved) onReorder(local.map { it.id })
                            else onCategoryMenu(c.id)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    ) else Modifier,
                    onLongClick = if (onReorder == null) ({ onCategoryMenu(c.id) }) else null,
                    onClick = {
                        if (suppressClick) suppressClick = false else onSelect(c.id)
                    },
                )
            }
        }
        items(hidden, key = { "hidden-${it.id}" }) { c ->
            GlassChip(c.name + " (aus)", selected = false, onClick = { onUnhide(c.id) })
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
    onMoveCategory: (String, Int) -> Unit = { _, _ -> },
    onHideCategory: (String) -> Unit = {},
    onCategoryMenu: (String) -> Unit = {},
    movingCategory: String? = null,
    hiddenCategories: List<CategoryEntity> = emptyList(),
    onUnhideCategory: (String) -> Unit = {},
    onReorderCategories: ((List<String>) -> Unit)? = null,
) {
    if (!drawer) {
        CategoryRow(
            categories = categories,
            selected = selected,
            onSelect = onSelect,
            hidden = hiddenCategories,
            onCategoryMenu = onCategoryMenu,
            onUnhide = onUnhideCategory,
            onReorder = onReorderCategories,
        )
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
            onCategoryMenu = onCategoryMenu,
            hiddenCategories = hiddenCategories,
            onUnhideCategory = onUnhideCategory,
            onReorder = onReorderCategories,
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
    onCategoryMenu: (String) -> Unit = {},
    hiddenCategories: List<CategoryEntity> = emptyList(),
    onUnhideCategory: (String) -> Unit = {},
    onReorder: ((List<String>) -> Unit)? = null,
) {
    val s = LocalAppStyle.current
    var query by remember { mutableStateOf("") }
    var localCats by remember { mutableStateOf(categories) }
    var dragging by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(categories) { if (!dragging) localCats = categories }
    val entries = remember(localCats, hiddenCategories) {
        buildList {
            add(Cat.ALL to Cat.label(Cat.ALL))
            add(Cat.FAV to Cat.label(Cat.FAV))
            add(Cat.RECENT to Cat.label(Cat.RECENT))
            localCats.forEach { add(it.id to it.name) }
            hiddenCategories.forEach { add("hidden:${it.id}" to it.name + " (ausgeblendet)") }
        }
    }
    val shown = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { it.second.contains(q, ignoreCase = true) }
    }
    val filterState = rememberLazyListState()
    val browsingAll = query.isBlank()
    val canReorder = onReorder != null && browsingAll
    val activeListState = if (browsingAll) listState else filterState
    val reorder = rememberReorderableLazyListState(activeListState) { from, to ->
        if (!canReorder) return@rememberReorderableLazyListState
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        if (fromId == toId) return@rememberReorderableLazyListState
        if (fromId.startsWith("hidden:") || toId.startsWith("hidden:")) return@rememberReorderableLazyListState
        if (fromId in setOf(Cat.ALL, Cat.FAV, Cat.RECENT) || toId in setOf(Cat.ALL, Cat.FAV, Cat.RECENT)) return@rememberReorderableLazyListState
        localCats = ListOrder.moveByKey(localCats, { it.id }, fromId, toId)
        moved = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
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
                        state = activeListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = listBottom),
                    ) {
                        items(shown, key = { it.first }) { (id, name) ->
                            val hiddenItem = id.startsWith("hidden:")
                            val realId = if (hiddenItem) id.removePrefix("hidden:") else id
                            val sortable = canReorder && !hiddenItem && realId != Cat.ALL && realId != Cat.FAV && realId != Cat.RECENT
                            ReorderableItem(reorder, id, enabled = sortable) { isDragging ->
                                CategoryDrawerRow(
                                    name = name,
                                    selected = realId == selected,
                                    sortable = sortable,
                                    emphasized = isDragging,
                                    lifted = isDragging,
                                    modifier = if (sortable) Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            dragging = true
                                            moved = false
                                            suppressClick = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = {
                                            dragging = false
                                            if (moved) onReorder?.invoke(localCats.map { it.id })
                                            else onCategoryMenu(realId)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                    ) else Modifier,
                                    onClick = {
                                        if (suppressClick) {
                                            suppressClick = false
                                        } else if (hiddenItem) {
                                            onUnhideCategory(realId)
                                        } else {
                                            onSelect(realId)
                                        }
                                    },
                                    onMenu = { onCategoryMenu(realId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Deckende Karte, gleiche Ecke und Schrift wie die Senderliste, ohne durchscheinendes Glas. */
@Composable
private fun CategoryDrawerRow(
    name: String,
    selected: Boolean,
    sortable: Boolean,
    emphasized: Boolean,
    lifted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(18.dp)
    val fill = if (selected || emphasized || lifted) solidMix(s.backgroundColors[2], s.accent, 0.5f) else s.backgroundColors[2]
    val handleActive = modifier !== Modifier
    Box(
        Modifier
            .fillMaxWidth()
            .dragLift(lifted)
            .clip(shape)
            .background(fill, shape)
            .then(if (selected || emphasized || lifted) Modifier.border(1.5.dp, s.accent, shape) else Modifier)
            .then(modifier)
            .pressable(onClick, onLongClick = if (sortable && !handleActive) onMenu else null)
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

/** Suche und „Weiterschauen“ oben in Live TV (Design „Glas & Verlauf“). Die Suche öffnet den Such-Tab. */
@Composable
internal fun LiveTopBar(recent: List<ChannelEntity>, onSearch: () -> Unit, onPlay: (ChannelEntity) -> Unit) {
    val s = LocalAppStyle.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(16.dp))
                .pressable(onSearch)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = s.onSurfaceDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Sender, Filme, Serien suchen", color = s.onSurfaceDim, fontSize = 15.sp)
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Weiterschauen", color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { ch ->
                    Row(
                        Modifier
                            .weight(1f)
                            .glass(RoundedCornerShape(16.dp))
                            .pressable({ onPlay(ch) })
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Poster(
                            ch.logo,
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1AFFFFFF)).padding(4.dp),
                            ContentScale.Fit,
                            PosterFrame.Logo,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, color = s.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (recent.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}


/** Ein Tipp wechselt zwischen Anbieter-Profil und Freien Sendern. */
@Composable
internal fun LiveSourceSwitcher(mainVm: MainViewModel) {
    val active by mainVm.active.collectAsStateWithLifecycle()
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val hasProvider = profiles.any { it.type != de.dgstudios.iptvstream.core.data.db.ProfileType.FREE }
    val hasFree = profiles.any { it.type == de.dgstudios.iptvstream.core.data.db.ProfileType.FREE }
    if (!hasProvider && !hasFree) return
    val freeOn = active.profile?.type == de.dgstudios.iptvstream.core.data.db.ProfileType.FREE
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasProvider) {
            GlassChip("Meine Sender", !freeOn) { mainVm.switchLiveSource(false) }
        }
        GlassChip("Freie Sender", freeOn) { mainVm.switchLiveSource(true) }
    }
}

@Composable
fun LiveScreen(
    vm: LiveViewModel,
    mainVm: MainViewModel,
    settings: AppSettings,
    bottomPad: Dp,
    onPlay: () -> Unit,
    onSearch: () -> Unit = {},
    topBarVisible: Boolean = true,
) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val hiddenCats by vm.hiddenCategories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val hiddenChannels by vm.hiddenChannels.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val sync by mainVm.sync.collectAsStateWithLifecycle()
    val tick by rememberClockTick(10_000)
    var menuChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var menuCategory by remember { mutableStateOf<String?>(null) }
    var showSortHelp by remember { mutableStateOf(false) }
    var showHiddenChannels by remember { mutableStateOf(false) }
    var draggingChannels by remember { mutableStateOf(false) }
    var localItems by remember { mutableStateOf(items) }
    var channelMoved by remember { mutableStateOf(false) }
    var suppressChannelClick by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(items) { if (!draggingChannels) localItems = items }

    val topMax = remember { mutableIntStateOf(0) }
    val topCollapse = remember { mutableFloatStateOf(0f) }
    val topConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val max = topMax.intValue.toFloat()
                if (max <= 0f || available.y == 0f) return Offset.Zero
                val old = topCollapse.floatValue
                val new = (old - available.y).coerceIn(0f, max)
                topCollapse.floatValue = new
                return Offset(0f, old - new)
            }
        }
    }

    Column(Modifier.fillMaxSize().nestedScroll(topConnection)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ScreenHeader("Live TV", if (items.isNotEmpty()) "${items.size} Sender" else null)
            }
            Text(
                "Sortieren",
                color = LocalAppStyle.current.onSurfaceDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 16.dp).pressable({ showSortHelp = true }),
            )
        }
        LiveSourceSwitcher(mainVm)
        // Suche + „Weiterschauen“ klappen beim Runterscrollen ein und beim Hochscrollen wieder aus (wie die untere Leiste).
        // Suche + „Weiterschauen“ klappen beim Runterscrollen fingergenau ein und beim Hochscrollen wieder aus.
        // Die Leiste verbraucht dabei den Scrollweg selbst, so springt die Liste nicht mehr.
        Box(
            Modifier
                .clipToBounds()
                .layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    topMax.intValue = p.height
                    val h = (p.height - topCollapse.floatValue.coerceAtMost(p.height.toFloat())).roundToInt().coerceAtLeast(0)
                    layout(p.width, h) { p.placeRelative(0, h - p.height) }
                }
                .graphicsLayer { alpha = if (topMax.intValue > 0) 1f - topCollapse.floatValue / topMax.intValue else 1f },
        ) {
            LiveTopBar(recent, onSearch) { ch ->
                vm.play(ch)
                onPlay()
            }
        }
        CategoryPicker(
            categories,
            selected,
            settings.categoryDrawer,
            vm::selectCategory,
            drawerIndex = vm.drawerIndex,
            drawerOffset = vm.drawerOffset,
            onDrawerScroll = vm::onDrawerScroll,
            onCategoryMenu = { menuCategory = it },
            hiddenCategories = hiddenCats,
            onUnhideCategory = vm::unhideCategory,
            onReorderCategories = { vm.setCategoryOrder(it) },
        )
        if (!restored) return@Column

        val listState = rememberLazyListState(vm.scrollIndex, vm.scrollOffset)
        val reorder = rememberReorderableLazyListState(
            listState,
            scrollThresholdPadding = PaddingValues(bottom = bottomPad),
        ) { from, to ->
            val fromId = from.key as? String ?: return@rememberReorderableLazyListState
            val toId = to.key as? String ?: return@rememberReorderableLazyListState
            if (fromId == toId || fromId == "hidden-channels" || toId == "hidden-channels") return@rememberReorderableLazyListState
            localItems = ListOrder.moveByKey(localItems, { it.streamId }, fromId, toId)
            channelMoved = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        val hasItems = localItems.isNotEmpty()
        LaunchedEffect(hasItems, selected) {
            if (!hasItems) return@LaunchedEffect
            listState.scrollToItem(vm.scrollIndex.coerceAtMost(items.lastIndex), vm.scrollOffset)
            // Zurück aus dem Player: der zuletzt gespielte Sender bleibt sichtbar.
            vm.takeReturnTarget(items)?.let { t ->
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.none { it.index == t }) {
                    listState.scrollToItem((t - 1).coerceAtLeast(0))
                    vm.onScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
            }
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (i, o) -> vm.onScroll(i, o) }
        }

        if (localItems.isEmpty() && items.isEmpty()) {
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
                items(localItems, key = { it.streamId }, contentType = { "channel" }) { ch ->
                    ReorderableItem(reorder, ch.streamId) { isDragging ->
                        ChannelRow(
                            ch = ch,
                            showNumber = settings.showNumbers,
                            fav = ch.streamId in favs,
                            tick = tick,
                            lifted = isDragging,
                            rowModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    draggingChannels = true
                                    channelMoved = false
                                    suppressChannelClick = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    draggingChannels = false
                                    if (channelMoved) vm.setChannelOrder(localItems.map { it.streamId })
                                    else menuChannel = ch
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                            ),
                            onClick = {
                                if (suppressChannelClick) {
                                    suppressChannelClick = false
                                } else {
                                    vm.play(ch)
                                    onPlay()
                                }
                            },
                            onFav = { vm.toggleFavorite(ch.streamId) },
                            onMenu = { menuChannel = ch },
                        )
                    }
                }
                if (hiddenChannels.isNotEmpty()) {
                    item(key = "hidden-channels") {
                        HiddenLine("${hiddenChannels.size} ausgeblendete Sender · zum Einblenden tippen") {
                            showHiddenChannels = true
                        }
                    }
                }
            }
        }
    }

    val mc = menuChannel
    if (mc != null) {
        SortActionMenu(
            title = mc.name,
            onDismiss = { menuChannel = null },
            onToStart = { vm.moveChannelToStart(mc) },
            onToEnd = { vm.moveChannelToEnd(mc) },
            onHide = { vm.hideChannel(mc) },
            extra = listOf(
                (if (mc.streamId in favs) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") to { vm.toggleFavorite(mc.streamId) },
                "Als Startsender festlegen" to { vm.setStartChannel(mc) },
                "Alphabetisch" to { vm.sortChannelsByName() },
                "Zurücksetzen" to { mainVm.resetLiveOrder() },
            ),
        )
    }
    val mcat = menuCategory
    if (mcat != null && mcat != Cat.ALL && mcat != Cat.FAV && mcat != Cat.RECENT) {
        SortActionMenu(
            title = categories.firstOrNull { it.id == mcat }?.name ?: mcat,
            onDismiss = { menuCategory = null },
            onToStart = { vm.moveCategoryToStart(mcat) },
            onToEnd = { vm.moveCategoryToEnd(mcat) },
            onHide = { vm.hideCategory(mcat) },
            extra = listOf(
                "Alphabetisch" to { vm.sortCategoriesByName() },
                "Zurücksetzen" to { mainVm.resetLiveOrder() },
            ),
        )
    }
    if (showSortHelp) {
        AlertDialog(
            onDismissRequest = { showSortHelp = false },
            title = { Text("Sortieren") },
            text = { Text("Halte einen Sender oder eine Kategorie gedrückt und schiebe ihn an den neuen Platz. Loslassen speichert sofort. Wenn du ohne Schieben loslässt, erscheint ein kleines Menü.") },
            confirmButton = { TextButton(onClick = { showSortHelp = false }) { Text("OK") } },
        )
    }
    if (showHiddenChannels) {
        AlertDialog(
            onDismissRequest = { showHiddenChannels = false },
            title = { Text("Ausgeblendete Sender") },
            text = {
                Column {
                    hiddenChannels.forEach { ch ->
                        Text(
                            ch.name,
                            modifier = Modifier.fillMaxWidth().pressable({
                                vm.unhideChannel(ch.streamId)
                            }).padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHiddenChannels = false }) { Text("Fertig") } },
        )
    }
}

@Composable
internal fun ChannelRow(
    ch: ChannelEntity,
    showNumber: Boolean,
    fav: Boolean,
    tick: Long,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onMenu: () -> Unit = {},
    lifted: Boolean = false,
    rowModifier: Modifier = Modifier,
) {
    val s = LocalAppStyle.current
    val nowNext by rememberNowNext(ch.profileId, ch.epgKey, tick)
    val nn = nowNext
    val cur = nn?.now
    val next = nn?.next
    val shape = RoundedCornerShape(18.dp)

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .dragLift(lifted)
                .glass(shape)
                .then(if (lifted) Modifier.border(2.dp, s.accent, shape) else Modifier)
                .then(rowModifier)
                .pressable(onClick, onLongClick = if (rowModifier === Modifier) onMenu else null)
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
                PosterFrame.Logo,
                lite = true,
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
                    val fraction = animatedProgress(nn?.progress(tick) ?: 0f)
                    AccentBar(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        trackColor = Color.White.copy(alpha = 0.2f),
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
                    modifier = if (fav) Modifier.popIn() else Modifier,
                )
            }
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
            Poster(image, Modifier.fillMaxSize(), lite = true)
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
