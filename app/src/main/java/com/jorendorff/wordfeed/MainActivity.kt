package com.jorendorff.wordfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FeedViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        vm.refreshIfStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm = this.vm
            val dark = isDark(vm.themeMode)
            // Status/navigation bar icons follow our theme, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    )
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            WordfeedTheme(dark) {
                App(vm, dark)
            }
        }
    }
}

@Composable
fun App(vm: FeedViewModel, dark: Boolean) {
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) {
        BackHandler { managing = false }
        SourcesScreen(vm, onBack = { managing = false })
    } else {
        ReaderScreen(vm, dark, onManage = { managing = true })
    }
}

// ---- Reader: one pager page per subscribed source --------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: FeedViewModel, dark: Boolean, onManage: () -> Unit) {
    val sources = vm.subscribedSources
    val pagerState = rememberPagerState(pageCount = { sources.size })
    val scope = rememberCoroutineScope()
    val current = sources.getOrNull(pagerState.currentPage)
    val settled = sources.getOrNull(pagerState.settledPage)
    val settledKey = settled?.let { vm.newestKey(it) }

    // How much of each tab is on screen (its width in the window, clipped by the
    // scrolling tab bar). Only sources whose tabs you can see get loaded.
    val visibleTabWidth = remember { mutableStateMapOf<String, Float>() }

    // Looking at a source marks its newest entry as read.
    LaunchedEffect(settled?.id, settledKey) {
        if (settled != null) vm.markSeen(settled)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            current?.name ?: "Wordfeed",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onManage) {
                            Icon(Icons.Filled.Settings, contentDescription = "Sources and settings")
                        }
                    },
                )
                if (sources.isNotEmpty()) {
                    val selected = pagerState.currentPage.coerceIn(0, sources.size - 1)
                    ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                        sources.forEachIndexed { i, s ->
                            Tab(
                                modifier = Modifier.onGloballyPositioned {
                                    visibleTabWidth[s.id] = it.boundsInWindow().width
                                },
                                selected = i == selected,
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                                text = {
                                    TabLabel(s.shortName, unread = vm.hasNew(s), ready = vm.isReady(s))
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (sources.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("You're not subscribed to anything yet.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onManage) { Text("Choose sources") }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pickySwipe(pagerState, scope),
                // Swiping is handled by pickySwipe, which leaves vertical scrolls alone.
                userScrollEnabled = false,
                // Keep every page alive, so a page doesn't reload each time you
                // come back to it. Pages only load when their tab is visible.
                beyondViewportPageCount = sources.size,
                key = { sources[it].id },
            ) { page ->
                val source = sources[page]
                val active = page == pagerState.currentPage ||
                    (visibleTabWidth[source.id] ?: 0f) > 1f
                // Only the current page and its neighbors are drawn. A page the
                // pager hasn't laid out on screen yet can otherwise be drawn on
                // top of the current one (seen on an API 34 emulator).
                val near = kotlin.math.abs(page - pagerState.currentPage) <= 1
                ReaderPage(vm, source, dark, active, near)
            }
        }
    }
}

@Composable
fun ReaderPage(vm: FeedViewModel, source: Source, dark: Boolean, active: Boolean, near: Boolean) {
    // Every page's WebView is created up front (the pager composes all pages),
    // because starting up a WebView blanks the other WebViews for a moment,
    // which looked like a flicker mid-swipe. A page doesn't fetch anything
    // until it's first wanted.
    val generation = vm.generation
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> ReaderView(ctx, vm, source) },
        update = { view ->
            view.visibility = if (near) android.view.View.VISIBLE else android.view.View.INVISIBLE
            view.show(generation, dark, active)
        },
    )
}

@Composable
fun TabLabel(text: String, unread: Boolean, ready: Boolean) {
    // Pale until the page has rendered (including before it has started
    // loading); then fades to full strength.
    val alpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0.4f,
        animationSpec = if (ready) tween(durationMillis = 300) else snap(),
        label = "tab alpha",
    )
    Row(Modifier.alpha(alpha), verticalAlignment = Alignment.CenterVertically) {
        Text(text, maxLines = 1)
        if (unread) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

// ---- Sources and settings ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: FeedViewModel, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add feed") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Appearance",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, label) in listOf(
                            ThemeMode.SYSTEM to "System",
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark",
                        )) {
                            FilterChip(
                                selected = vm.themeMode == mode,
                                onClick = { vm.changeTheme(mode) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            items(vm.allSources, key = { it.id }) { s ->
                val on = s.id in vm.subscribedIds
                ListItem(
                    modifier = Modifier.clickable { vm.setSubscribed(s, !on) },
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.blurb) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.custom) {
                                IconButton(onClick = { vm.removeCustom(s) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                                }
                            }
                            Switch(checked = on, onCheckedChange = { vm.setSubscribed(s, it) })
                        }
                    },
                )
            }
        }
    }
    if (adding) {
        AddFeedDialog(
            onDismiss = { adding = false },
            onAdd = { name, url ->
                vm.addCustom(name, url)
                adding = false
            },
        )
    }
}

@Composable
fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an RSS or Atom feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onAdd(name.ifBlank { url.trim() }, url.trim()) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
