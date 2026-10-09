package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import de.dgstudios.iptvstream.core.ui.rememberClockTick
import de.dgstudios.iptvstream.core.ui.rememberNowNext
import de.dgstudios.iptvstream.core.vm.LiveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CatItem(val id: String, val label: String)

fun buildCatItems(cats: List<CategoryEntity>, withRecent: Boolean = true): List<CatItem> =
    buildList {
        add(CatItem(Cat.ALL, "Alle"))
        add(CatItem(Cat.FAV, "★ Favoriten"))
        if (withRecent) add(CatItem(Cat.RECENT, "Zuletzt"))
        cats.forEach { add(CatItem(it.id, it.name)) }
    }

/** Kategorieliste links (Auswahl per OK). Hoch vom ersten Eintrag verlässt die Leiste. */
@Composable
fun TvCategoryList(
    items: List<CatItem>,
    selected: String,
    registry: FocusRegistry,
    onSelect: (String) -> Unit,
    onEscapeUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStyle.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val move = remember { MoveGate() }
    LazyColumn(
        state = state,
        modifier = modifier.width(240.dp).fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, c -> c.id }) { index, c ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .registered(registry, index)
                    .tvMove(
                        onUp = {
                            move.launch(scope) {
                                if (index <= 0) {
                                    onEscapeUp()
                                } else {
                                    state.scrollToItem(index - 1)
                                    if (!registry.focus(index - 1)) onEscapeUp()
                                }
                            }
                        },
                        onDown = {
                            move.launch(scope) {
                                if (index < items.lastIndex) state.focusItem(registry, index + 1)
                            }
                        },
                    )
                    .tvFocus(onClick = { onSelect(c.id) }, selected = c.id == selected)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    c.label,
                    color = s.onSurface,
                    fontSize = 18.sp,
                    fontWeight = if (c.id == selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Seitenleiste fährt zu, sobald der Fokus in der Inhaltsliste ist. */
@Composable
fun TvSplitRail(
    open: Boolean,
    rail: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val s = LocalAppStyle.current
    val width by animateDpAsState(
        targetValue = if (open) 256.dp else 0.dp,
        animationSpec = if (s.animations) tween(220) else tween(0),
        label = "railWidth",
    )
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .width(width)
                .fillMaxHeight()
                .clipToBounds()
                .focusProperties { canFocus = open },
        ) {
            Row(Modifier.width(256.dp).fillMaxHeight()) {
                rail()
                Spacer(Modifier.width(16.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/**
 * Durchsuchbare Kategorien von der Seite. Zurück schließt, OK wählt aus,
 * Hoch/Runter bewegt den Fokus, das Suchfeld filtert die Liste.
 */
@Composable
fun TvCategoryDrawer(
    items: List<CatItem>,
    selected: String,
    listState: LazyListState,
    outerBottom: Dp,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStyle.current
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { it.label.contains(q, ignoreCase = true) }
    }
    val reg = remember { FocusRegistry() }
    val search = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var searchFocused by remember { mutableStateOf(false) }
    val filterState = rememberLazyListState()
    val browsingAll = query.isBlank()
    val rowShape = RoundedCornerShape(14.dp)
    val listBottom = maxOf(drawerSystemBottom(), outerBottom) + 48.dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(480.dp)
                    .fillMaxHeight()
                    .background(s.backgroundColors[0])
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text("Kategorien", color = s.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = s.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(s.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(search)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && shown.isNotEmpty()) {
                                scope.launch { reg.focus(0) }
                                true
                            } else {
                                false
                            }
                        }
                        .background(s.backgroundColors[2], rowShape)
                        .then(
                            if (searchFocused) Modifier.border(3.dp, Color.White, rowShape) else Modifier,
                        )
                        .padding(16.dp),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Kategorie suchen", color = s.onSurfaceDim, fontSize = 20.sp)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) {
                    Text("Keine Kategorie", color = s.onSurfaceDim, fontSize = 20.sp)
                } else {
                    LazyColumn(
                        state = if (browsingAll) listState else filterState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = listBottom),
                    ) {
                        itemsIndexed(shown, key = { _, c -> c.id }) { index, c ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .registered(reg, index)
                                    .then(if (index == 0) Modifier.focusProperties { up = search } else Modifier)
                                    .tvMove(onDown = {
                                        scope.launch {
                                            if (index < shown.lastIndex) {
                                                (if (browsingAll) listState else filterState).focusItem(reg, index + 1)
                                            }
                                        }
                                    })
                                    .tvFocus(onClick = { onSelect(c.id) }, selected = c.id == selected, opaque = true)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    c.label,
                                    color = s.onSurface,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 26.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
    }
    LaunchedEffect(browsingAll, selected, items) {
        if (!browsingAll) {
            filterState.scrollToItem(0)
            return@LaunchedEffect
        }
        val idx = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        listState.reveal(idx)
        if (!reg.focus(idx)) {
            try {
                search.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
}

/** Unterer Systembereich. Im Dialog oft 0, deshalb zusätzlich von außen übergeben. */
@Composable
internal fun drawerSystemBottom(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safe = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val imePad = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    return maxOf(nav, safe, imePad)
}

/** Zeigt die gewählte Kategorie, ohne eine fast weggescrollte Zeile als Haarlinie stehen zu lassen. */
private suspend fun LazyListState.reveal(index: Int) {
    if (index < 0) return
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
fun TvLiveScreen(
    vm: LiveViewModel,
    settings: AppSettings,
    entry: EntryHandle,
    first: FirstFocus,
    onPlay: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val tick by rememberClockTick(30_000)

    val listState = rememberLazyListState()
    val listReg = remember { FocusRegistry() }
    val catReg = remember { FocusRegistry() }
    val scope = rememberCoroutineScope()
    val itemsRef = rememberUpdatedState(items)
    val selectedRef = rememberUpdatedState(selected)
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var listFocused by remember { mutableStateOf(false) }
    var pendingFrom by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    val catItems = remember(cats) { buildCatItems(cats) }
    val catItemsRef = rememberUpdatedState(catItems)
    val drawer = settings.categoryDrawer
    val openButton = remember { FocusRequester() }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerListState = rememberLazyListState(vm.drawerIndex, vm.drawerOffset)
    val drawerBottom = drawerSystemBottom()
    var railOpen by remember { mutableStateOf(true) }
    val move = remember { MoveGate() }
    val escapeRef = rememberUpdatedState(onEscapeUp)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Der erste Start zählt nicht: erst die Rückkehr aus dem Hintergrund setzt die Liste zurück.
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
                listState.scrollToItem(0)
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
                val idx = listState.firstVisibleItemIndex.coerceIn(0, list.lastIndex)
                listState.scrollToItem(idx)
                if (!listReg.focus(idx)) {
                    listState.scrollToItem(0)
                    if (!listReg.focus(0)) onEscapeUp()
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
            if (index <= 0) {
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
                val above = index - 1
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == above }) {
                    listState.scrollToItem(above)
                }
                if (!listReg.focus(above)) {
                    listState.scrollToItem(above)
                    if (!listReg.focus(above)) onEscapeUp()
                }
            }
        }
    }

    fun stepDown(index: Int) {
        move.launch(scope) {
            if (index < itemsRef.value.lastIndex) listState.focusItem(listReg, index + 1)
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

    // Nicht tief in die Liste springen: das hat den Fokus ohne Weg nach oben gefangen.
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        // Ist der Nutzer schon in der Senderliste (langsame Geräte), Fokus und Position nicht wegreißen.
        if (listFocused) return@LaunchedEffect
        first.pending = false
        vm.focusIndex = 0
        vm.onScroll(0, 0)
        listState.scrollToItem(0)
    }

    // Nach Kategoriewechsel (OK) in die neue Liste springen, sobald sie da ist.
    LaunchedEffect(items, pendingFrom) {
        val p = pendingFrom ?: return@LaunchedEffect
        if (items !== p) {
            pendingFrom = null
            listState.scrollToItem(0)
            if (items.isNotEmpty()) listReg.focus(0)
        }
    }
    LaunchedEffect(pendingFrom) {
        if (pendingFrom != null) {
            delay(2_000)
            pendingFrom = null
        }
    }

    @Composable
    fun LiveChannels() {
        if (items.isEmpty()) {
            when (selected) {
                Cat.FAV -> TvEmpty("Noch keine Favoriten", "Sender lange drücken (oder Menü-Taste) und zu Favoriten hinzufügen.")
                Cat.RECENT -> TvEmpty("Noch nichts gesehen", "Zuletzt gesehene Sender erscheinen hier.")
                else -> TvEmpty("Keine Sender", "Inhalte werden geladen oder die Kategorie ist leer.")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().onFocusChanged { listFocused = it.hasFocus },
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, c -> c.streamId }) { index, ch ->
                    ChannelRow(
                        ch = ch,
                        index = index,
                        showNumber = settings.showNumbers,
                        isFav = ch.streamId in favs,
                        tick = tick,
                        registry = listReg,
                        onFocus = { if (!drawer) railOpen = false },
                        onUp = { stepUp(index) },
                        onDown = { stepDown(index) },
                        onLeft = if (drawer) null else ::openRail,
                        onClick = {
                            vm.play(ch)
                            onPlay()
                        },
                        onMenu = { menuFor = ch },
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
            Box(Modifier.weight(1f).fillMaxSize()) { LiveChannels() }
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
            rail = { TvCategoryList(catItems, selected, catReg, ::chooseCategory, onEscapeUp) },
            content = { LiveChannels() },
        )
    }

    val m = menuFor
    if (m != null) {
        val isFav = m.streamId in favs
        TvActionDialog(
            title = m.name,
            actions = listOf(
                TvAction(if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") { vm.toggleFavorite(m.streamId) },
                TvAction("Als Startsender festlegen") { vm.setStartChannel(m) },
                TvAction("Schließen") {},
            ),
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun ChannelRow(
    ch: ChannelEntity,
    index: Int,
    showNumber: Boolean,
    isFav: Boolean,
    tick: Long,
    registry: FocusRegistry,
    onFocus: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val s = LocalAppStyle.current
    val nn by rememberNowNext(ch.profileId, ch.epgKey, tick)
    Row(
        Modifier
            .fillMaxWidth()
            .height(78.dp)
            .registered(registry, index)
            .tvMove(onUp = onUp, onLeft = onLeft, onDown = onDown)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                    onMenu()
                    true
                } else {
                    false
                }
            }
            .tvFocus(onClick = onClick, onLongClick = onMenu, onFocus = { if (it) onFocus() })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                ch.num.toString(),
                color = s.onSurfaceDim,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(62.dp),
            )
        }
        Poster(
            ch.logo,
            Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1AFFFFFF)).padding(4.dp),
            ContentScale.Fit,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(ch.name, color = s.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = nn?.now
            if (now != null) {
                Text(now.title, color = s.onSurfaceDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { nn?.progress(tick) ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        }
        if (isFav) {
            Spacer(Modifier.width(10.dp))
            Text("★", color = Color(0xFFFFC857), fontSize = 22.sp)
        }
    }
}
