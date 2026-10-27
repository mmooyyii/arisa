package quarkflix.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import quarkflix.app.QuarkflixApp
import quarkflix.app.data.LibraryRepository
import quarkflix.library.MediaItem
import quarkflix.library.MediaType
import quarkflix.library.tmdbImage

private data class Shelf(val title: String, val items: List<MediaItem>)

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onPlay: (itemId: String, fid: String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = QuarkflixApp.instance
    val library by app.library.library.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    val progress by app.progress.all.collectAsStateWithLifecycle()

    val lib = library
    if (lib == null || lib.items.isEmpty()) {
        EmptyState(status, onSettings = onSettings)
        LaunchedEffect(Unit) { app.library.refresh() }
        return
    }

    val continueWatching = remember(lib, progress) { app.progress.continueWatching(lib, progress) }
    val shelves = remember(lib) { buildShelves(lib.items) }

    // 焦点停留 300ms 后才切背景, 遥控器连按时不频繁加载大图
    var focused by remember { mutableStateOf(continueWatching.firstOrNull()?.item ?: lib.items.first()) }
    var hero by remember { mutableStateOf(focused) }
    LaunchedEffect(focused) {
        delay(300)
        hero = focused
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(hero)

        Column(Modifier.fillMaxSize()) {
            TopBar(onSearch, onSettings, status)
            HeroInfo(hero, Modifier.padding(start = 56.dp, end = 56.dp).height(220.dp))
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue") {
                        Column {
                            SectionTitle("继续观看", Modifier.padding(start = 56.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                items(continueWatching, key = { it.item.id }) { entry ->
                                    val ep = entry.episode
                                    val subtitle = when {
                                        ep != null && entry.progress == null -> "下一集 · 第 ${ep.season} 季 第 ${ep.episode} 集"
                                        ep != null -> "第 ${ep.season} 季 第 ${ep.episode} 集" + (ep.title?.let { " · $it" } ?: "")
                                        else -> entry.progress?.let { "剩余 ${(it.durationMs - it.positionMs) / 60000} 分钟" }
                                    }
                                    LandscapeCard(
                                        imageUrl = tmdbImage(ep?.still ?: entry.item.backdrop, "w780"),
                                        blurHash = entry.item.art.backdropBlurHash,
                                        title = entry.item.title,
                                        subtitle = subtitle,
                                        progress = entry.progress?.let { it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1) },
                                        onClick = { onPlay(entry.item.id, entry.fid) },
                                        fallback = parseColor(entry.item.art.color),
                                        width = 232.dp,
                                        modifier = Modifier
                                            .then(if (entry == continueWatching.first()) Modifier.focusRequester(firstFocus) else Modifier)
                                            .onFocusChanged { if (it.hasFocus) focused = entry.item },
                                    )
                                }
                            }
                        }
                    }
                }
                shelves.forEachIndexed { index, shelf ->
                    item(key = shelf.title) {
                        Column {
                            SectionTitle(shelf.title, Modifier.padding(start = 56.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                items(shelf.items, key = { it.id }) { item ->
                                    val isFirst = continueWatching.isEmpty() && index == 0 && item == shelf.items.first()
                                    PosterCard(
                                        item = item,
                                        onClick = { onOpen(item.id) },
                                        modifier = Modifier
                                            .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier)
                                            .onFocusChanged { if (it.hasFocus) focused = item },
                                    )
                                }
                            }
                        }
                    }
                }
                if (lib.unmatched.isNotEmpty()) {
                    item(key = "unmatched") {
                        Text(
                            "还有 ${lib.unmatched.size} 个文件没识别出来, 在刮削器的 overrides 里修正后重新扫描即可",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onSearch: () -> Unit, onSettings: () -> Unit, status: LibraryRepository.Status) {
    Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Quarkflix", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
        Spacer(Modifier.width(16.dp))
        if (status is LibraryRepository.Status.Loading) {
            Text("同步中…", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        val colors = ButtonDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
        Button(onClick = onSearch, colors = colors, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("搜索", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onSettings, colors = colors, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("设置", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HeroInfo(item: MediaItem, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = item,
        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
        contentKey = { it.id },
        modifier = modifier,
        label = "hero",
    ) { current ->
        Column(Modifier.width(560.dp).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            LogoOrTitle(current, maxHeight = 84.dp, maxWidth = 380.dp)
            Spacer(Modifier.height(14.dp))
            MetaLine(current, current.files + current.seasons.flatMap { s -> s.episodes.flatMap { it.files } }.take(3))
            Spacer(Modifier.height(10.dp))
            current.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyState(status: LibraryRepository.Status, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (title, detail) = when (status) {
                LibraryRepository.Status.Loading, LibraryRepository.Status.Idle -> "正在读取片库…" to null
                LibraryRepository.Status.NoIndex -> "网盘里还没有片库" to "在 mac mini 上运行刮削器 (quarkflix-scraper scan) 后会自动出现"
                is LibraryRepository.Status.Error -> "读取片库失败" to status.message
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSettings) { Text("设置") }
        }
    }
}

private fun buildShelves(items: List<MediaItem>): List<Shelf> = buildList {
    add(Shelf("最近添加", items.sortedByDescending { it.addedAt }.take(20)))
    items.filter { it.type == MediaType.MOVIE }.takeIf { it.isNotEmpty() }?.let { add(Shelf("电影", it.sortedByDescending { m -> m.addedAt })) }
    items.filter { it.type == MediaType.TV }.takeIf { it.isNotEmpty() }?.let { add(Shelf("剧集", it.sortedByDescending { m -> m.addedAt })) }
    // 数量最多的几个类型各一行
    items.flatMap { it.genres }.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.filter { it.value >= 3 }.take(4)
        .forEach { (genre, _) -> add(Shelf(genre, items.filter { genre in it.genres }.sortedByDescending { it.rating ?: 0.0 })) }
    items.filter { (it.rating ?: 0.0) >= 8.0 }.takeIf { it.size >= 3 }?.let { add(Shelf("高分", it.sortedByDescending { m -> m.rating })) }
}
