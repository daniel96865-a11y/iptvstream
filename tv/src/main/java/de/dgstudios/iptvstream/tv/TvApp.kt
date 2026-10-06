package de.dgstudios.iptvstream.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
            // Zusätzlicher Rand wegen Overscan, damit die Uhr nie abgeschnitten wird.
            ClockOverlay(s, Modifier.padding(horizontal = 36.dp, vertical = 15.dp))
            UpdateHost(updates, television = true)
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
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .padding(end = 110.dp)
                    .onFocusChanged { inTabs = it.hasFocus }
                    .focusProperties { enter = { tabRequesters[tab.ordinal] } }
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (t in TvTab.entries) {
                    TabButton(
                        t = t,
                        selected = t == tab,
                        requester = tabRequesters[t.ordinal],
                        onFocus = { tab = t },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TvStatusLine(sync, epg)
            Box(Modifier.weight(1f)) {
                val escapeToTab = {
                    try {
                        tabRequesters[tab.ordinal].requestFocus()
                    } catch (_: IllegalStateException) {
                    }
                }
                when (tab) {
                    TvTab.LIVE -> TvLiveScreen(liveVm, s, entry, first, onPlay, escapeToTab)
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

@Composable
private fun TabButton(t: TvTab, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit) {
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
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.icon, contentDescription = null, tint = s.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(t.label, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
