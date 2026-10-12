package com.jorendorff.wordfeed

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WordfeedTheme {
                App()
            }
        }
    }
}

@Composable
fun WordfeedTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: FeedViewModel = viewModel()) {
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) {
        BackHandler { managing = false }
        SourcesScreen(vm, onBack = { managing = false })
    } else {
        ReaderScreen(vm, onManage = { managing = true })
    }
}

// ---- Reader: one pager page per subscribed source --------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: FeedViewModel, onManage: () -> Unit) {
    val sources = vm.subscribedSources
    val pagerState = rememberPagerState(pageCount = { sources.size })
    val scope = rememberCoroutineScope()
    val current = sources.getOrNull(pagerState.currentPage)
    val settled = sources.getOrNull(pagerState.settledPage)
    val settledContent = settled?.let { vm.states[it.id]?.content }

    // Looking at a source's page marks its newest item as read.
    LaunchedEffect(settled?.id, settledContent) {
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
                        )
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = onManage) {
                            Icon(Icons.Filled.Settings, contentDescription = "Sources")
                        }
                    },
                )
                if (sources.isNotEmpty()) {
                    val selected = pagerState.currentPage.coerceIn(0, sources.size - 1)
                    ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                        sources.forEachIndexed { i, s ->
                            Tab(
                                selected = i == selected,
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                                text = {
                                    TabLabel(
                                        s.shortName,
                                        unread = vm.hasNew(s),
                                        loading = vm.states[s.id]?.loading == true,
                                    )
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
                modifier = Modifier.fillMaxSize().padding(padding),
                key = { sources[it].id },
            ) { page ->
                SourcePage(vm, sources[page])
            }
        }
    }
}

@Composable
fun TabLabel(text: String, unread: Boolean, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, maxLines = 1)
        if (loading) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        } else if (unread) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
fun SourcePage(vm: FeedViewModel, src: Source) {
    when (val content = vm.states[src.id]?.content) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SourceContent.Items -> ItemList(content.items)
        is SourceContent.Page -> WebPage(content.url)
        is SourceContent.Failed -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load ${src.name}.")
                Spacer(Modifier.height(8.dp))
                Text(
                    content.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.reload(src) }) { Text("Try again") }
            }
        }
    }
}

@Composable
fun ItemList(items: List<Item>) {
    val first = items.firstOrNull()?.guid
    var expanded by remember(first) { mutableStateOf(setOfNotNull(first)) }
    val uriHandler = LocalUriHandler.current
    fun open(uri: String) {
        try {
            uriHandler.openUri(uri)
        } catch (e: Exception) {
            // No app to handle it; nothing useful to do.
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.guid }) { item ->
            val isOpen = item.guid in expanded
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (isOpen) expanded - item.guid else expanded + item.guid
                            }
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Serif,
                        )
                        prettyDate(item)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isOpen) {
                        if (item.html.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            HtmlText(item.html, Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.link?.let { link ->
                                TextButton(onClick = { open(link) }) { Text("Open") }
                            }
                            item.audioUrl?.let { audio ->
                                TextButton(onClick = { open(audio) }) { Text("Listen") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun prettyDate(item: Item): String? =
    item.dateMillis?.let { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(it)) }
        ?: item.dateRaw

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val spanned = remember(html) {
        Html.fromHtml(cleanHtml(html), Html.FROM_HTML_MODE_COMPACT).trim()
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 16f
                setLineSpacing(0f, 1.25f)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setLinkTextColor(linkColor)
            tv.text = spanned
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(url: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
    )
}

// ---- Sources: subscribe / unsubscribe / add your own ------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: FeedViewModel, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
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
