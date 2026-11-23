package de.dgstudios.iptvstream.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.ClockOverlay
import de.dgstudios.iptvstream.core.update.UpdateHost
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.NumberOverlay
import de.dgstudios.iptvstream.core.ui.digitOrNull
import de.dgstudios.iptvstream.core.ui.rememberNumberEntry
import de.dgstudios.iptvstream.core.vm.LiveViewModel
import de.dgstudios.iptvstream.core.vm.MainViewModel
import de.dgstudios.iptvstream.core.vm.MoviesViewModel
import de.dgstudios.iptvstream.core.vm.SearchViewModel
import de.dgstudios.iptvstream.core.vm.SeriesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TvTab(val label: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Filme", Icons.Rounded.Movie),
    SERIES("Serien", Icons.Rounded.VideoLibrary),
    SEARCH("Suche", Icons.Rounded.Search),
    SETTINGS("Einstellungen", Icons.Rounded.Settings),
}

@Composable
fun TvApp(mainVm: MainViewModel = viewModel()) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val active by mainVm.active.collectAsStateWithLifecycle()
    val s = settings
    if (s == null || !active.loaded) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05070F)))
        return
    }

    AppTheme(s, isTv = true) {
        androidx.compose.runtime.CompositionLocalProvider(LocalTvSafe provides tvSafeFor(s.tvMargin)) {
        AppBackground {
            val nav = rememberNavController()
            val ctx = LocalContext.current
            val updates = ctx.container.updates
            val backStack by nav.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            LaunchedEffect(route) {
                if (route == null || route == "home") updates.onStartScreenVisible()
                else updates.onStartScreenHidden()
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, route) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> if (route == null || route == "home") updates.onStartScreenResume()
                        Lifecycle.Event.ON_STOP -> updates.onAppBackground()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val auto by mainVm.autoStart.collectAsStateWithLifecycle()
            LaunchedEffect(auto) {
                val req = auto ?: return@LaunchedEffect
                ctx.container.session.request = req
                mainVm.consumeAutoStart()
                nav.navigate("player")
            }

            val anim = s.animations
            NavHost(
                navController = nav,
                startDestination = "home",
                enterTransition = { if (anim) fadeIn(tween(200)) else EnterTransition.None },
                exitTransition = { if (anim) fadeOut(tween(120)) else ExitTransition.None },
                popEnterTransition = { if (anim) fadeIn(tween(200)) else EnterTransition.None },
                popExitTransition = { if (anim) fadeOut(tween(120)) else ExitTransition.None },
            ) {
                composable("home") {
                    TvHome(
                        mainVm = mainVm,
                        profile = active.profile,
                        onPlay = { nav.navigate("player") },
                        onMovie = { nav.navigate("movie/$it") },
                        onSeries = { nav.navigate("series/$it") },
                        onProfiles = { nav.navigate("profiles") },
                    )
                }
                composable("movie/{id}") {
                    TvMovieDetail(onPlay = { nav.navigate("player") })
                }
                composable("series/{id}") {
                    TvSeriesDetail(onPlay = { nav.navigate("player") })
                }
                composable("player") {
                    TvPlayerScreen(onBack = { nav.popBackStack() })
                }
                composable("profiles") {
                    TvProfilesScreen(
                        mainVm = mainVm,
                        onBack = { nav.popBackStack() },
                        onEdit = { id -> nav.navigate("profile_edit/$id") },
                    )
                }
                composable("profile_edit/{id}") { entry ->
                    val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                    TvProfileForm(mainVm = mainVm, profileId = id, first = false, onDone = { nav.popBackStack() })
                }
            }
            // Uhr im sicheren Bereich (ClockOverlay hat selbst 12 dp Abstand).
            ClockOverlay(s, Modifier.padding(horizontal = tvSafe.horizontal - 12.dp, vertical = (tvSafe.vertical - 12.dp).coerceAtLeast(0.dp)))
            UpdateHost(updates, television = true)
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvHome(
    mainVm: MainViewModel,
    profile: ProfileEntity?,
    onPlay: () -> Unit,
    onMovie: (String) -> Unit,
    onSeries: (String) -> Unit,
    onProfiles: () -> Unit,
) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()

    if (profile == null) {
        TvProfileForm(mainVm = mainVm, profileId = 0L, first = true, onDone = {})
        return
    }

    val liveVm: LiveViewModel = viewModel()
    val moviesVm: MoviesViewModel = viewModel()
    val seriesVm: SeriesViewModel = viewModel()
    val searchVm: SearchViewModel = viewModel()
    val sync by mainVm.sync.collectAsStateWithLifecycle()
    val epg by mainVm.epg.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(TvTab.LIVE) }
    val entry = remember { EntryHandle() }
    val first = remember { FirstFocus() }
    val tabRequesters = remember { TvTab.entries.map { FocusRequester() } }
    var inTabs by remember { mutableStateOf(false) }
    var numMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val numEntry = rememberNumberEntry { n ->
        scope.launch {
            val ch = liveVm.channelByNumber(n)
            if (ch != null) {
                liveVm.play(ch)
                onPlay()
            } else {
                numMsg = "Sender $n nicht gefunden"
                delay(2_000)
                numMsg = null
            }
        }
    }

    BackHandler(enabled = !inTabs) {
        try {
            tabRequesters[tab.ordinal].requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    // Start: Fokus auf die Tab-Leiste; der Inhalt übernimmt, sobald seine Daten da sind.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        try {
            tabRequesters[tab.ordinal].requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                first.pending = false
                val digit = ev.digitOrNull()
                when {
                    tab == TvTab.LIVE && digit != null -> {
                        numEntry.digit(digit)
                        true
                    }
                    numEntry.text.isNotEmpty() && ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.Enter || ev.key == Key.DirectionCenter || ev.key == Key.NumPadEnter) -> {
                        numEntry.commit()
                        true
                    }
                    else -> false
                }
            }
            .tvSafePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TvTabBar(
                // Platz für die Uhr oben rechts (Uhr ≈ 60 dp breit + Abstand), sonst volle Breite.
                reserveEnd = if (s.clockOn && s.clockPos == "tr") 76.dp else 0.dp,
                modifier = Modifier
                    // Passt normalerweise ganz hinein (kompakte Tabs). Bei großer Schrift rollt die
                    // Leiste mit dem Fokus, damit kein Tab dauerhaft abgeschnitten bleibt.
                    .horizontalScroll(rememberScrollState())
                    .onFocusChanged { inTabs = it.hasFocus }
                    .focusProperties { onEnter = { tabRequesters[tab.ordinal].requestFocus() } }
                    .focusGroup()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                            val action = entry.action
                            if (action != null) {
                                action()
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    },
                tab = tab,
                requesters = tabRequesters,
                onTab = { tab = it },
            )
            Spacer(Modifier.height(10.dp))
            TvStatusLine(sync, epg)
            Box(Modifier.weight(1f)) {
                val escapeToTab: () -> Unit = {
                    try {
                        tabRequesters[tab.ordinal].requestFocus()
                    } catch (_: IllegalStateException) {
                    }
                }
                when (tab) {
                    TvTab.LIVE -> TvLiveScreen(liveVm, mainVm, s, entry, first, onPlay, escapeToTab)
                    TvTab.MOVIES -> TvMoviesScreen(moviesVm, s, entry, first, onMovie, escapeToTab)
                    TvTab.SERIES -> TvSeriesScreen(seriesVm, s, entry, first, onSeries, escapeToTab)
                    TvTab.SEARCH -> TvSearchScreen(searchVm, s, entry, onPlay, onMovie, onSeries)
                    TvTab.SETTINGS -> TvSettingsScreen(mainVm, s, entry, onProfiles)
                }
            }
        }
        NumberOverlay(numEntry.text, Modifier.padding(horizontal = 36.dp, vertical = 15.dp))
        val msg = numMsg
        if (msg != null) {
            val st = LocalAppStyle.current
            Text(
                msg,
                color = st.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

/** Tab-Leiste (Live TV … Einstellungen, Browser). Fokus-Logik kommt über [modifier] vom Aufrufer. */
@Composable
internal fun TvTabBar(
    reserveEnd: Dp,
    modifier: Modifier,
    tab: TvTab,
    requesters: List<FocusRequester>,
    onTab: (TvTab) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(end = reserveEnd)) {
        // Bei großem Bildschirmrand wird es eng: erst „Browser“ nur als Symbol, dann Tabs ohne Symbol.
        // Reicht es trotzdem nicht (z. B. große Schrift), rollt die Leiste mit dem Fokus.
        val compactBrowser = maxWidth < 775.dp
        val noIcons = maxWidth < 710.dp
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in TvTab.entries) {
                TabButton(
                    t = t,
                    selected = t == tab,
                    requester = requesters[t.ordinal],
                    onFocus = { onTab(t) },
                    showIcon = !noIcons,
                )
            }
            val context = LocalContext.current
            BrowserButton(compact = compactBrowser) { de.dgstudios.iptvstream.core.browser.BrowserActivity.start(context, tv = true) }
        }
    }
}

/** Menüpunkt „Browser“ (Beta) neben den Tabs: öffnet den eingebauten Browser, wechselt keinen Tab. */
@Composable
internal fun BrowserButton(compact: Boolean = false, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Row(
        Modifier
            .height(50.dp)
            .tvFocus(onClick = onClick, shape = RoundedCornerShape(25.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Language, contentDescription = null, tint = s.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(5.dp))
        if (!compact) {
            Text("Browser", color = s.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Spacer(Modifier.width(5.dp))
        Text(
            "Beta",
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0xFF14B8A6), RoundedCornerShape(8.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun TabButton(t: TvTab, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, showIcon: Boolean = true) {
    val s = LocalAppStyle.current
    Row(
        Modifier
            .height(50.dp)
            .tvFocus(
                onClick = onFocus,
                shape = RoundedCornerShape(25.dp),
                onFocus = { if (it) onFocus() },
                requester = requester,
                selected = selected,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(t.icon, contentDescription = null, tint = s.onSurface, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(t.label, color = s.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
