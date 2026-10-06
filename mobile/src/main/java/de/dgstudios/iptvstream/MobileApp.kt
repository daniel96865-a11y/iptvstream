package de.dgstudios.iptvstream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.ClockOverlay
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.vm.LiveViewModel
import de.dgstudios.iptvstream.core.vm.MainViewModel
import de.dgstudios.iptvstream.core.vm.MoviesViewModel
import de.dgstudios.iptvstream.core.vm.SearchViewModel
import de.dgstudios.iptvstream.core.vm.SeriesViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Filme", Icons.Rounded.Movie),
    SERIES("Serien", Icons.Rounded.VideoLibrary),
    SEARCH("Suche", Icons.Rounded.Search),
    SETTINGS("Einstellungen", Icons.Rounded.Settings),
}

private val BAR_HEIGHT = 66.dp

@Composable
fun MobileApp(mainVm: MainViewModel = viewModel()) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    val active by mainVm.active.collectAsStateWithLifecycle()
    val s = settings
    if (s == null || !active.loaded) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05070F)))
        return
    }

    AppTheme(s, isTv = false) {
        AppBackground {
            val nav = rememberNavController()
            val ctx = LocalContext.current
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
                enterTransition = {
                    if (anim) fadeIn(tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(240)) else EnterTransition.None
                },
                exitTransition = { if (anim) fadeOut(tween(160)) else ExitTransition.None },
                popEnterTransition = { if (anim) fadeIn(tween(220)) else EnterTransition.None },
                popExitTransition = { if (anim) fadeOut(tween(180)) else ExitTransition.None },
            ) {
                composable("home") {
                    HomeScreen(
                        mainVm = mainVm,
                        profile = active.profile,
                        onPlay = { nav.navigate("player") },
                        onMovie = { nav.navigate("movie/$it") },
                        onSeries = { nav.navigate("series/$it") },
                        onProfiles = { nav.navigate("profiles") },
                    )
                }
                composable("movie/{id}") {
                    MovieDetailScreen(onBack = { nav.popBackStack() }, onPlay = { nav.navigate("player") })
                }
                composable("series/{id}") {
                    SeriesDetailScreen(onBack = { nav.popBackStack() }, onPlay = { nav.navigate("player") })
                }
                composable("player") {
                    PlayerScreen(onBack = { nav.popBackStack() })
                }
                composable("profiles") {
                    ProfilesScreen(
                        mainVm = mainVm,
                        onBack = { nav.popBackStack() },
                        onEdit = { id -> nav.navigate("profile_edit/$id") },
                    )
                }
                composable("profile_edit/{id}") { entry ->
                    val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                    ProfileFormScreen(mainVm = mainVm, profileId = id, first = false, onDone = { nav.popBackStack() })
                }
            }
            ClockOverlay(s)
        }
    }
}

@Composable
private fun HomeScreen(
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
        ProfileFormScreen(mainVm = mainVm, profileId = 0L, first = true, onDone = {})
        return
    }

    val liveVm: LiveViewModel = viewModel()
    val moviesVm: MoviesViewModel = viewModel()
    val seriesVm: SeriesViewModel = viewModel()
    val searchVm: SearchViewModel = viewModel()
    val sync by mainVm.sync.collectAsStateWithLifecycle()
    val epg by mainVm.epg.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val barVisible = remember { mutableStateOf(true) }
    val connection = remember {
        object : NestedScrollConnection {
            private var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                if ((dy > 0f) != (acc > 0f)) acc = 0f
                acc += dy
                if (acc < -40f) barVisible.value = false
                if (acc > 40f) barVisible.value = true
                return Offset.Zero
            }
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad: Dp = BAR_HEIGHT + 28.dp + navBottom

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .nestedScroll(connection),
    ) {
        Column(Modifier.fillMaxSize()) {
            SyncBanner(sync, epg, onRetry = { mainVm.reloadContent() })
            Box(Modifier.weight(1f)) {
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(if (s.animations) 220 else 0),
                    label = "tab",
                ) { t ->
                    when (t) {
                        Tab.LIVE -> LiveScreen(liveVm, mainVm, s, bottomPad, onPlay)
                        Tab.MOVIES -> MoviesScreen(moviesVm, bottomPad, onMovie)
                        Tab.SERIES -> SeriesScreen(seriesVm, bottomPad, onSeries)
                        Tab.SEARCH -> SearchScreen(searchVm, s, bottomPad, onPlay, onMovie, onSeries)
                        Tab.SETTINGS -> SettingsScreen(mainVm, s, bottomPad, onProfiles)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = barVisible.value,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (s.animations) slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn() else EnterTransition.None,
            exit = if (s.animations) slideOutVertically(tween(220)) { it } + fadeOut(tween(180)) else ExitTransition.None,
        ) {
            BottomBar(selected = tab, onSelect = {
                tab = it
                barVisible.value = true
            })
        }
    }
}

/** Schwebende Glas-Navigation mit gleitender Auswahlmarke. */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val s = LocalAppStyle.current
    val tabs = Tab.entries
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(BAR_HEIGHT),
    ) {
        val itemW = maxWidth / tabs.size
        val offset by animateDpAsState(
            targetValue = itemW * selected.ordinal,
            animationSpec = if (s.animations) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow) else tween(0),
            label = "tabOffset",
        )
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(s.backgroundColors[1].copy(alpha = 0.72f), shape)
                .glass(shape, strong = true),
        ) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(itemW)
                    .fillMaxHeight()
                    .padding(5.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(s.accent.copy(alpha = 0.30f))
                    .border(1.dp, s.accent.copy(alpha = 0.65f), RoundedCornerShape(26.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                for (t in tabs) {
                    val isSel = t == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            t.icon,
                            contentDescription = t.label,
                            tint = if (isSel) Color.White else s.onSurfaceDim,
                            modifier = Modifier.height(24.dp).width(24.dp),
                        )
                        Text(
                            t.label,
                            color = if (isSel) Color.White else s.onSurfaceDim,
                            fontSize = 10.5.sp,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
