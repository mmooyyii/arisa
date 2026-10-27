package arisa.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
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
import arisa.app.ArisaApp
import arisa.app.data.LibraryRepository
import arisa.library.Library
import arisa.library.MediaCollection
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.tmdbImage

/**
 * 首页的一张海报: 单部作品, 或者一个合集.
 * 合集用封面作品的图和信息, 片名换成合集名, 加入时间取最近加入的那部.
 */
private data class Entry(val item: MediaItem, val collection: MediaCollection? = null) {
    val key get() = collection?.id ?: item.id
}

private data class Shelf(val title: String, val entries: List<Entry>)

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlay: (itemId: String, fid: String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = ArisaApp.instance
    val library by app.library.library.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    val progress by app.progress.all.collectAsStateWithLifecycle()

    val lib = library
    if (lib == null || (lib.items.isEmpty() && lib.unmatched.isEmpty())) {
        EmptyState(status, onSettings = onSettings)
        LaunchedEffect(Unit) { app.library.refresh() }
        return
    }

    val continueWatching = remember(lib, progress) { app.progress.continueWatching(lib, progress) }
    val shelves = remember(lib) { buildShelves(entries(lib)) }

    // 记住最后聚焦的卡片 ("行:作品"), 从详情页返回时焦点回到那里, 不回第一个
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // 文字跟着焦点立刻变; 背景大图焦点一到就开始加载, 停留 300ms 后才切:
    // 遥控器连按时背景不动, 换背景对电视 GPU 是重活
    var focused by remember {
        mutableStateOf(
            lastFocus?.substringAfter(':')?.let { id -> lib.items.firstOrNull { it.id == id } }
                ?: continueWatching.firstOrNull()?.item ?: lib.items.firstOrNull()
        )
    }
    var hero by remember { mutableStateOf(focused) }
    val context = LocalContext.current
    LaunchedEffect(focused) {
        focused?.let { prefetchBackdrop(context, it) }
        delay(300)
        hero = focused
    }

    val firstFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 卡片要等列表按保存的位置排好才能聚焦, 等一帧再试
        withFrameNanos { }
        if (lastFocus == null || runCatching { restoreFocus.requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
    }
    fun Modifier.track(key: String, isFirst: Boolean) = this
        .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier)
        .then(if (key == lastFocus) Modifier.focusRequester(restoreFocus) else Modifier)
        .onFocusChanged { if (it.hasFocus) lastFocus = key }

    Box(Modifier.fillMaxSize()) {
        Backdrop(hero)

        Column(Modifier.fillMaxSize()) {
            TopBar(onSearch, onSettings, status)
            focused?.let { HeroInfo(it, Modifier.padding(start = 56.dp, end = 56.dp).height(220.dp)) }
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = rememberLazyListState(),
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
                                            .track("continue:${entry.item.id}", isFirst = entry == continueWatching.first())
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
                                items(shelf.entries, key = { it.key }) { entry ->
                                    val isFirst = continueWatching.isEmpty() && index == 0 && entry == shelf.entries.first()
                                    val c = entry.collection
                                    PosterCard(
                                        item = entry.item,
                                        onClick = { if (c != null) onOpenCollection(c.id) else onOpen(entry.item.id) },
                                        badge = c?.let { "合集 · ${it.items.size} 部" },
                                        modifier = Modifier
                                            .track("${shelf.title}:${entry.key}", isFirst)
                                            .onFocusChanged { if (it.hasFocus) focused = entry.item },
                                    )
                                }
                            }
                        }
                    }
                }
                // 没识别出来的视频按文件名列出来, 照样能播
                if (lib.unmatched.isNotEmpty()) {
                    item(key = "unmatched") {
                        Column {
                            SectionTitle("未识别 (${lib.unmatched.size})", Modifier.padding(start = 56.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                items(lib.unmatched, key = { it.fid }) { file ->
                                    val isFirst = continueWatching.isEmpty() && shelves.isEmpty() && file == lib.unmatched.first()
                                    LandscapeCard(
                                        imageUrl = null,
                                        blurHash = null,
                                        title = file.name,
                                        subtitle = listOfNotNull(
                                            file.path.substringBeforeLast('/').substringAfterLast('/').ifEmpty { null },
                                            file.duration.takeIf { it > 0 }?.let { formatRuntime((it + 30) / 60) },
                                        ).joinToString("  ·  ").ifEmpty { null },
                                        progress = progress[file.fid]?.let { it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1) },
                                        onClick = { onPlay("", file.fid) },
                                        width = 232.dp,
                                        modifier = Modifier.track("unmatched:${file.fid}", isFirst),
                                    )
                                }
                            }
                        }
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
                LibraryRepository.Status.NoIndex -> "网盘里还没有片库" to "在 mac mini 上运行刮削器 (arisa-scraper scan) 后会自动出现"
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

/** 合集里的作品收成一张合集海报, 其余作品各一张. */
private fun entries(lib: Library): List<Entry> {
    val byId = lib.items.associateBy { it.id }
    val inCollection = HashSet<String>()
    val collections = lib.collections.mapNotNull { c ->
        val members = c.items.mapNotNull(byId::get)
        val cover = byId[c.cover] ?: members.firstOrNull() ?: return@mapNotNull null
        members.mapTo(inCollection) { it.id }
        Entry(cover.copy(title = c.name, addedAt = members.maxOf { it.addedAt }), c)
    }
    return lib.items.filter { it.id !in inCollection }.map { Entry(it) } + collections
}

private fun buildShelves(entries: List<Entry>): List<Shelf> = buildList {
    if (entries.isEmpty()) return@buildList
    add(Shelf("最近添加", entries.sortedByDescending { it.item.addedAt }.take(20)))
    entries.filter { it.item.type == MediaType.MOVIE }.takeIf { it.isNotEmpty() }?.let { add(Shelf("电影", it.sortedByDescending { m -> m.item.addedAt })) }
    entries.filter { it.item.type == MediaType.TV }.takeIf { it.isNotEmpty() }?.let { add(Shelf("剧集", it.sortedByDescending { m -> m.item.addedAt })) }
    // 数量最多的几个类型各一行
    entries.flatMap { it.item.genres }.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.filter { it.value >= 3 }.take(4)
        .forEach { (genre, _) -> add(Shelf(genre, entries.filter { genre in it.item.genres }.sortedByDescending { it.item.rating ?: 0.0 })) }
    entries.filter { (it.item.rating ?: 0.0) >= 8.0 }.takeIf { it.size >= 3 }?.let { add(Shelf("高分", it.sortedByDescending { m -> m.item.rating })) }
}
