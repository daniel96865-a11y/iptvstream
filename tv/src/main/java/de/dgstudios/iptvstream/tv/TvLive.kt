@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.dgstudios.iptvstream.tv

import de.dgstudios.iptvstream.core.ui.scrollToItemSafe


import de.dgstudios.iptvstream.core.ui.AccentBar
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
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.runtime.withFrameNanos
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
import de.dgstudios.iptvstream.core.ui.PosterFrame
import de.dgstudios.iptvstream.core.ui.animatedProgress
import de.dgstudios.iptvstream.core.ui.popIn
import de.dgstudios.iptvstream.core.ui.rememberClockTick
import de.dgstudios.iptvstream.core.ui.rememberNowNext
import de.dgstudios.iptvstream.core.vm.LiveViewModel
import de.dgstudios.iptvstream.core.vm.MainViewModel
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

/** Kategorieliste links (Auswahl per OK). Hoch, Runter und Rechts steuert der Bildschirm. */
@Composable
fun TvCategoryList(
    items: List<CatItem>,
    selected: String,
    registry: FocusRegistry,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onUp: (Int) -> Unit,
    onDown: (Int) -> Unit,
    onRight: () -> Unit,
    onLongClick: (String) -> Unit = {},
    movingId: String? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStyle.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoAutoScroll) {
    LazyColumn(
        state = listState,
        modifier = modifier.width(240.dp).fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, c -> c.id }) { index, c ->
            var rowFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .registered(registry, index)
                    .tvMove(
                        onUp = { onUp(index) },
                        onDown = { onDown(index) },
                        onLeft = {},
                        onRight = onRight,
                    )
                    .tvFocus(
                        onClick = { onSelect(c.id) },
                        onLongClick = { onLongClick(c.id) },
                        selected = c.id == selected || c.id == movingId,
                        lockDirections = true,
                        onFocus = { rowFocused = it },
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TvMarqueeText(
                    text = c.label,
                    color = s.onSurface,
                    fontSize = 18.sp,
                    fontWeight = if (c.id == selected) FontWeight.Bold else FontWeight.Medium,
                    focused = rowFocused,
                )
            }
        }
    }
    }
}

/** Seitenleiste. Die Einträge behalten ihre Breite, auch wenn die Leiste zugeklappt ist, damit der Fokus nicht in einer leeren Fläche hängen bleibt. */
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
            // Volle Breite auch während des Einfahrens, sonst ist die Zeile 0 dp breit und der Fokus sucht endlos.
            Row(Modifier.requiredWidth(256.dp).fillMaxHeight()) {
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
    onLongClick: (String) -> Unit = {},
    movingId: String? = null,
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
    val safe = tvSafe
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(462.dp + maxOf(18.dp, safe.horizontal))
                    .fillMaxHeight()
                    .background(s.backgroundColors[0])
                    // Hintergrund bis zum Rand, Inhalt im sicheren Bereich (Overscan).
                    .padding(start = maxOf(18.dp, safe.horizontal), end = 18.dp, top = maxOf(16.dp, safe.vertical), bottom = 16.dp),
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
                            var rowFocused by remember { mutableStateOf(false) }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .registered(reg, index)
                                    .then(if (index == 0) Modifier.focusProperties { up = search } else Modifier)
                                    .tvFocus(
                                        onClick = { onSelect(c.id) },
                                        onLongClick = { onLongClick(c.id) },
                                        selected = c.id == selected || c.id == movingId,
                                        opaque = true,
                                        onFocus = { rowFocused = it },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                TvMarqueeText(
                                    text = c.label,
                                    color = s.onSurface,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    focused = rowFocused,
                                    lineHeight = 26.sp,
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
            filterState.scrollToItemSafe(0)
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
    if (!visibleFully()) scrollToItemSafe(index)
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    if (first != null && first.index != index && first.offset < info.viewportStartOffset) {
        val shown = first.size - (info.viewportStartOffset - first.offset)
        if (shown in 1 until 24) scrollToItemSafe(first.index + 1)
    }
    if (!visibleFully()) scrollToItemSafe(index)
}


/** Fokus-Ziele der Quellen-Leiste („Meine Sender“ / „Freie Sender“). */
internal class SourceBarFocus {
    val mine = FocusRequester()
    val free = FocusRequester()
}

/**
 * Quellen-Leiste unter den Tabs. Fernbedienung: Links/Rechts wechselt zwischen den Chips,
 * Hoch geht zur Tab-Leiste, Runter in Kategorien bzw. Senderliste.
 */
@Composable
private fun TvLiveSourceBar(
    mainVm: MainViewModel,
    focus: SourceBarFocus,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val active by mainVm.active.collectAsStateWithLifecycle()
    val profiles by mainVm.profiles.collectAsStateWithLifecycle()
    val freeOn = active.profile?.type == de.dgstudios.iptvstream.core.data.db.ProfileType.FREE
    val hasProvider = profiles.any { it.type != de.dgstudios.iptvstream.core.data.db.ProfileType.FREE }
    TvSourceChips(
        hasProvider = hasProvider,
        freeOn = freeOn,
        focus = focus,
        onMine = { mainVm.switchLiveSource(false) },
        onFree = { mainVm.switchLiveSource(true) },
        onUp = onUp,
        onDown = onDown,
    )
}

/** Die beiden Chips (ohne ViewModel, damit ein Test die Fokus-Wege prüfen kann). */
@Composable
internal fun TvSourceChips(
    hasProvider: Boolean,
    freeOn: Boolean,
    focus: SourceBarFocus,
    onMine: () -> Unit,
    onFree: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val upDown = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (ev.key) {
            Key.DirectionUp -> { onUp(); true }
            Key.DirectionDown -> { onDown(); true }
            else -> false
        }
    }
    Row(
        Modifier.padding(start = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasProvider) {
            TvButton(
                "Meine Sender", onMine,
                modifier = upDown.focusProperties {
                    right = focus.free
                    left = FocusRequester.Cancel
                },
                requester = focus.mine,
                selected = !freeOn,
            )
        }
        TvButton(
            "Freie Sender", onFree,
            modifier = upDown.focusProperties {
                left = if (hasProvider) focus.mine else FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            requester = focus.free,
            selected = freeOn,
        )
    }
}


@Composable
internal fun TvMoveHintBar(name: String, onUp: () -> Unit, onDown: () -> Unit, onDone: () -> Unit, onAlpha: () -> Unit, onReset: () -> Unit) {
    val s = LocalAppStyle.current
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
        Text("„$name“ verschieben", color = s.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("↑↓ verschieben, OK ablegen", color = s.onSurfaceDim, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton("Nach oben", onUp)
            TvButton("Nach unten", onDown)
            TvButton("Fertig", onDone, selected = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton("Alphabetisch", onAlpha)
            TvButton("Zurücksetzen", onReset)
        }
    }
}

@Composable
fun TvLiveScreen(
    vm: LiveViewModel,
    mainVm: MainViewModel,
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
    val tick by rememberClockTick(10_000)

    val listState = rememberLazyListState()
    val catListState = rememberLazyListState()
    val listReg = remember { FocusRegistry() }
    val catReg = remember { FocusRegistry() }
    val scope = rememberCoroutineScope()
    val itemsRef = rememberUpdatedState(items)
    val selectedRef = rememberUpdatedState(selected)
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var catMenu by remember { mutableStateOf<String?>(null) }
    var movingChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var movingCategory by remember { mutableStateOf<String?>(null) }
    val hiddenCats by vm.hiddenCategories.collectAsStateWithLifecycle()
    val hiddenChannels by vm.hiddenChannels.collectAsStateWithLifecycle()
    var showHidden by remember { mutableStateOf(false) }
    var pendingFrom by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    val catItems = remember(cats, hiddenCats) {
        buildCatItems(cats) + hiddenCats.map { CatItem(it.id, it.name + " (aus)") }
    }
    val catItemsRef = rememberUpdatedState(catItems)
    val drawer = settings.categoryDrawer
    val openButton = remember { FocusRequester() }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerListState = rememberLazyListState(vm.drawerIndex.coerceAtLeast(0), vm.drawerOffset.coerceAtLeast(0))
    val drawerBottom = drawerSystemBottom()
    val returning = remember { vm.returnPending }
    var railOpen by remember { mutableStateOf(!returning) }
    var railHold by remember { mutableStateOf(false) }
    val move = remember { MoveGate() }
    val follow = remember { ScrollFollow() }
    val anim = LocalAppStyle.current.animations
    val escapeRef = rememberUpdatedState(onEscapeUp)
    val sourceFocus = remember { SourceBarFocus() }
    val activeNow by mainVm.active.collectAsStateWithLifecycle()
    val freeOnRef = rememberUpdatedState(activeNow.profile?.type == de.dgstudios.iptvstream.core.data.db.ProfileType.FREE)
    val profilesNow by mainVm.profiles.collectAsStateWithLifecycle()
    val hasProviderRef = rememberUpdatedState(profilesNow.any { it.type != de.dgstudios.iptvstream.core.data.db.ProfileType.FREE })

    /** Fokus auf den gewählten Quellen-Chip. false, wenn die Leiste (noch) nicht da ist. */
    fun focusSourceBar(): Boolean {
        val target = if (freeOnRef.value || !hasProviderRef.value) sourceFocus.free else sourceFocus.mine
        return try {
            target.requestFocus()
        } catch (_: IllegalStateException) {
            false
        }
    }

    /** Hoch aus der Liste: erst zu „Meine Sender“/„Freie Sender“, von dort zur Tab-Leiste. */
    fun escapeUp() {
        if (!focusSourceBar()) onEscapeUp()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Der erste Start zählt nicht: erst die Rückkehr aus dem Hintergrund setzt die Liste zurück.
        var guardReturn = returning
        var skipFirst = !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            if (skipFirst) {
                skipFirst = false
                return@LifecycleEventObserver
            }
            // Zurück aus dem Player ist kein Neustart: Position und Sender bleiben.
            if (guardReturn || vm.returnPending) {
                guardReturn = false
                return@LifecycleEventObserver
            }
            railOpen = true
            vm.focusIndex = 0
            vm.onScroll(0, 0)
            scope.launch {
                listState.scrollToItemSafe(0)
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

    suspend fun focusCategoryNow() {
        val catsNow = catItemsRef.value
        if (catsNow.isEmpty()) {
            escapeUp()
            return
        }
        railHold = true
        railOpen = true
        try {
            val ci = catsNow.indexOfFirst { it.id == selectedRef.value }.coerceAtLeast(0)
            withFrameNanos { }
            withFrameNanos { }
            catListState.ensureVisible(ci)
            val landed = catReg.focus(ci, tries = 40) || run {
                catListState.scrollToItemSafe(ci)
                catReg.focus(ci, tries = 40)
            }
            if (!landed) escapeUp()
        } finally {
            railHold = false
        }
    }

    suspend fun focusChannelsNow() {
        val list = itemsRef.value
        if (list.isEmpty()) {
            if (drawer) {
                try {
                    openButton.requestFocus()
                } catch (_: IllegalStateException) {
                }
            }
            return
        }
        val idx = listState.firstVisibleItemIndex.coerceIn(0, list.lastIndex)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            listState.scrollToItemSafe(idx)
        }
        val landed = listReg.focus(idx) || run {
            listState.scrollToItemSafe(0)
            listReg.focus(0)
        }
        if (!landed) {
            escapeUp()
            return
        }
        // Erst fokussieren, danach zuklappen. Sonst sucht der Fokus durch alle Sender.
        withFrameNanos { }
        if (!drawer && !railHold) railOpen = false
    }

    fun focusCategory() {
        move.launch(scope) { focusCategoryNow() }
    }

    fun focusChannels() {
        first.pending = false
        move.launch(scope) { focusChannelsNow() }
    }

    fun enterFromTab() {
        first.pending = false
        move.launch(scope) {
            if (!drawer && (railOpen || itemsRef.value.isEmpty())) focusCategoryNow() else focusChannelsNow()
        }
    }

    fun stepChannel(index: Int, delta: Int) {
        move.launch(scope) {
            val target = index + delta
            if (target !in itemsRef.value.indices) {
                if (delta < 0) {
                    if (drawer) {
                        try {
                            openButton.requestFocus()
                        } catch (_: IllegalStateException) {
                            escapeUp()
                        }
                    } else {
                        escapeUp()
                    }
                }
                return@launch
            }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target } && !listState.slideInNeighbor(target, anim)) {
                listState.scrollToItemSafe(target)
            }
            if (!listReg.focus(target)) {
                listState.scrollToItemSafe(target)
                if (!listReg.focus(target) && delta < 0) escapeUp()
            }
            follow.list(scope, listState, target, anim)
        }
    }

    fun moveCategory(index: Int, delta: Int) {
        move.launch(scope) {
            val target = index + delta
            if (target !in catItemsRef.value.indices) {
                if (delta < 0) escapeUp()
                return@launch
            }
            railHold = true
            try {
                if (!catListState.slideInNeighbor(target, anim)) catListState.ensureVisible(target)
                if (!catReg.focus(target)) {
                    catListState.scrollToItemSafe(target)
                    if (!catReg.focus(target)) escapeUp()
                }
                follow.list(scope, catListState, target, anim)
            } finally {
                railHold = false
            }
        }
    }

    fun chooseCategory(id: String) {
        if (movingCategory != null) {
            if (id == movingCategory) movingCategory = null
            return
        }
        if (hiddenCats.any { it.id == id }) {
            vm.unhideCategory(id)
            pendingFrom = items
            vm.selectCategory(id)
            return
        }
        if (id == selected) {
            focusChannels()
        } else {
            pendingFrom = items
            vm.selectCategory(id)
        }
    }
    // Runter aus der Tab-Leiste: zuerst auf die Quellen-Chips, damit „Freie Sender“ erreichbar ist.
    RegisterEntry(entry) {
        first.pending = false
        if (!focusSourceBar()) enterFromTab()
    }

    // Nicht tief in die Liste springen: das hat den Fokus ohne Weg nach oben gefangen.
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        first.pending = false
        if (returning && vm.returnPending) return@LaunchedEffect
        vm.focusIndex = 0
        vm.onScroll(0, 0)
        listState.scrollToItemSafe(0)
    }

    // Zurück aus dem Player: Liste und Fokus auf den zuletzt gespielten Sender setzen.
    LaunchedEffect(restored, items.isNotEmpty()) {
        if (!returning || !restored || items.isEmpty()) return@LaunchedEffect
        val target = vm.takeReturnTarget(items)
        if (target == null) {
            vm.clearReturn()
            return@LaunchedEffect
        }
        first.pending = false
        vm.focusIndex = target
        listState.scrollToItemSafe((target - 2).coerceAtLeast(0))
        vm.onScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        withFrameNanos { }
        withFrameNanos { }
        withFrameNanos { }
        move.launch(scope) {
            if (!listReg.focus(target, tries = 40)) {
                listState.scrollToItemSafe(target)
                listReg.focus(target, tries = 40)
            }
        }
    }

    // Nach Kategoriewechsel (OK) in die neue Liste springen, sobald sie da ist.
    LaunchedEffect(items, pendingFrom) {
        val p = pendingFrom ?: return@LaunchedEffect
        if (items !== p) {
            pendingFrom = null
            listState.scrollToItemSafe(0)
            if (items.isNotEmpty() && listReg.focus(0)) {
                withFrameNanos { }
                if (!drawer && !railHold) railOpen = false
            }
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
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, c -> c.streamId }, contentType = { _, _ -> "channel" }) { index, ch ->
                    ChannelRow(
                        ch = ch,
                        index = index,
                        showNumber = settings.showNumbers,
                        isFav = ch.streamId in favs,
                        tick = tick,
                        registry = listReg,
                        onUp = {
                            if (movingChannel?.streamId == ch.streamId) vm.moveChannel(ch, -1)
                            else stepChannel(index, -1)
                        },
                        onDown = {
                            if (movingChannel?.streamId == ch.streamId) vm.moveChannel(ch, 1)
                            else stepChannel(index, 1)
                        },
                        onLeft = if (drawer) null else ::focusCategory,
                        onRight = {},
                        onClick = {
                            if (movingChannel != null) {
                                if (movingChannel?.streamId == ch.streamId) movingChannel = null
                            } else {
                                vm.play(ch)
                                onPlay()
                            }
                        },
                        onMenu = { if (movingChannel == null) menuFor = ch },
                        moving = movingChannel?.streamId == ch.streamId,
                    )
                }
            }
            }
        }
    }

    if (drawer) {
        Column(Modifier.fillMaxSize()) {
            TvLiveSourceBar(mainVm, sourceFocus, onUp = onEscapeUp, onDown = ::enterFromTab)
            val moveName = movingChannel?.name ?: cats.firstOrNull { it.id == movingCategory }?.name
            if (moveName != null) {
                TvMoveHintBar(
                    name = moveName,
                    onUp = { movingChannel?.let { vm.moveChannel(it, -1) }; movingCategory?.let { vm.moveCategory(it, -1) } },
                    onDown = { movingChannel?.let { vm.moveChannel(it, 1) }; movingCategory?.let { vm.moveCategory(it, 1) } },
                    onDone = { movingChannel = null; movingCategory = null },
                    onAlpha = { if (movingChannel != null) vm.sortChannelsByName() else vm.sortCategoriesByName() },
                    onReset = { mainVm.resetLiveOrder(); movingChannel = null; movingCategory = null },
                )
            }
            if (hiddenChannels.isNotEmpty()) {
                TvButton("${hiddenChannels.size} ausgeblendete Sender", { showHidden = true }, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            }
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
                onLongClick = { if (it !in setOf(Cat.ALL, Cat.FAV, Cat.RECENT)) catMenu = it },
                movingId = movingCategory,
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TvLiveSourceBar(mainVm, sourceFocus, onUp = onEscapeUp, onDown = ::enterFromTab)
            val moveNameSplit = movingChannel?.name ?: cats.firstOrNull { it.id == movingCategory }?.name
            if (moveNameSplit != null) {
                TvMoveHintBar(
                    name = moveNameSplit,
                    onUp = { movingChannel?.let { vm.moveChannel(it, -1) }; movingCategory?.let { vm.moveCategory(it, -1) } },
                    onDown = { movingChannel?.let { vm.moveChannel(it, 1) }; movingCategory?.let { vm.moveCategory(it, 1) } },
                    onDone = { movingChannel = null; movingCategory = null },
                    onAlpha = { if (movingChannel != null) vm.sortChannelsByName() else vm.sortCategoriesByName() },
                    onReset = { mainVm.resetLiveOrder(); movingChannel = null; movingCategory = null },
                )
            }
            if (hiddenChannels.isNotEmpty()) {
                TvButton("${hiddenChannels.size} ausgeblendete Sender", { showHidden = true }, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                TvSplitRail(
                    open = railOpen,
                    rail = {
                        TvCategoryList(
                            items = catItems,
                            selected = selected,
                            registry = catReg,
                            listState = catListState,
                            onSelect = ::chooseCategory,
                            onUp = { idx ->
                                val id = catItemsRef.value.getOrNull(idx)?.id
                                if (id != null && id == movingCategory) vm.moveCategory(id, -1)
                                else moveCategory(idx, -1)
                            },
                            onDown = { idx ->
                                val id = catItemsRef.value.getOrNull(idx)?.id
                                if (id != null && id == movingCategory) vm.moveCategory(id, 1)
                                else moveCategory(idx, 1)
                            },
                            onRight = ::focusChannels,
                            onLongClick = { if (movingCategory == null && it !in setOf(Cat.ALL, Cat.FAV, Cat.RECENT)) catMenu = it },
                            movingId = movingCategory,
                        )
                    },
                    content = { LiveChannels() },
                )
            }
        }
    }

    val m = menuFor
    if (m != null) {
        val isFav = m.streamId in favs
        TvActionDialog(
            title = m.name,
            actions = listOf(
                TvAction("Verschieben") { movingChannel = m; movingCategory = null },
                TvAction("Ganz nach oben") { vm.moveChannelToStart(m) },
                TvAction("Ganz nach unten") { vm.moveChannelToEnd(m) },
                TvAction("Ausblenden") { vm.hideChannel(m) },
                TvAction(if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") { vm.toggleFavorite(m.streamId) },
                TvAction("Als Startsender festlegen") { vm.setStartChannel(m) },
                TvAction("Schließen") {},
            ),
            onDismiss = { menuFor = null },
        )
    }
    val cm = catMenu
    if (cm != null && cm != Cat.ALL && cm != Cat.FAV && cm != Cat.RECENT) {
        val label = cats.firstOrNull { it.id == cm }?.name ?: cm
        TvActionDialog(
            title = label,
            actions = listOf(
                TvAction("Verschieben") { movingCategory = cm; movingChannel = null },
                TvAction("Ganz nach oben") { vm.moveCategoryToStart(cm) },
                TvAction("Ganz nach unten") { vm.moveCategoryToEnd(cm) },
                TvAction("Ausblenden") { vm.hideCategory(cm) },
                TvAction("Schließen") {},
            ),
            onDismiss = { catMenu = null },
        )
    }
    if (showHidden) {
        TvActionDialog(
            title = "Ausgeblendete Sender",
            actions = hiddenChannels.map { ch ->
                TvAction(ch.name + " · einblenden") { vm.unhideChannel(ch.streamId) }
            } + TvAction("Schließen") {},
            onDismiss = { showHidden = false },
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
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)?,
    onRight: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    moving: Boolean = false,
) {
    val s = LocalAppStyle.current
    val nn by rememberNowNext(ch.profileId, ch.epgKey, tick)
    Row(
        Modifier
            .fillMaxWidth()
            .height(78.dp)
            .registered(registry, index)
            .tvMove(onUp = onUp, onDown = onDown, onLeft = onLeft, onRight = onRight)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                    onMenu()
                    true
                } else {
                    false
                }
            }
            .tvFocus(onClick = onClick, onLongClick = onMenu, lockDirections = true, selected = moving)
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
            PosterFrame.Logo,
            lite = true,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(ch.name, color = s.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = nn?.now
            if (now != null) {
                Text(now.title, color = s.onSurfaceDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = animatedProgress(nn?.progress(tick) ?: 0f)
                AccentBar(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        }
        if (isFav) {
            Spacer(Modifier.width(10.dp))
            Text("★", color = Color(0xFFFFC857), fontSize = 22.sp, modifier = Modifier.popIn())
        }
    }
}
