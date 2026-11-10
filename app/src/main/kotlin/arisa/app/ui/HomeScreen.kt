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
import androidx.compose.runtime.snapshots.Snapshot
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
import arisa.app.ArisaApp
import arisa.app.data.AppUpdater
import arisa.app.data.LibraryRepository
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import arisa.library.BrowseFilter
import arisa.library.BrowseSort
import arisa.library.Library
import arisa.library.LibraryBrowser
import arisa.library.LibraryBrowser.Card
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.PlaybackProgress
import arisa.library.WatchState
import arisa.library.tmdbImage

/** 首页的一行. 卡片最多 [SHELF_LIMIT] 张, 更多的话行尾放「全部」卡片, 点进片库页并带上 [filter]. */
private data class Shelf(val title: String, val filter: BrowseFilter, val cards: List<Card>, val total: Int)

private const val SHELF_LIMIT = 20

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlay: (itemId: String, fid: String) -> Unit,
    onBrowse: (BrowseFilter) -> Unit,
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
    val shelves = remember(lib) { shelvesOf(lib, app.library.browser() ?: LibraryBrowser(lib)) }

    // 记住最后聚焦的卡片 ("行:作品"), 从详情页返回时焦点回到那里, 不回第一个
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // 文字跟着焦点立刻变; 背景大图焦点一到就开始加载, 停留 300ms 后才切 (见 Backdrop)
    var focused by remember {
        mutableStateOf(
            Snapshot.withoutReadObservation { lastFocus }?.substringAfter(':')?.let(app.library::item)
                ?: continueWatching.firstOrNull()?.item ?: lib.items.firstOrNull()
        )
    }
    // 焦点只在 HomeBackdrop、HeroInfo 里面读, 不在页面里读: 页面里读的话每按一下方向键整个首页都要重组

    val firstFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    // 进页面时要恢复的那张, 只读一次: 卡片里直接读 lastFocus 的话, 焦点每动一下屏幕上所有卡片都要重组
    val restoreKey = remember { Snapshot.withoutReadObservation { lastFocus } }
    LaunchedEffect(Unit) {
        // 卡片要等列表按保存的位置排好才能聚焦, 等一帧再试
        withFrameNanos { }
        if (restoreKey == null || runCatching { restoreFocus.requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
    }
    fun Modifier.track(key: String, isFirst: Boolean) = this
        .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier)
        .then(if (key == restoreKey) Modifier.focusRequester(restoreFocus) else Modifier)
        .onFocusChanged { if (it.hasFocus) lastFocus = key }

    Box(Modifier.fillMaxSize()) {
        HomeBackdrop { focused }

        Column(Modifier.fillMaxSize()) {
            TopBar(onBrowse = { onBrowse(BrowseFilter()) }, onSearch, onSettings, status)
            HeroInfo({ focused }, Modifier.padding(start = 56.dp, end = 56.dp).height(220.dp))
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
                    item(key = shelf.filter.toString()) {
                        Column {
                            SectionTitle(shelf.title, Modifier.padding(start = 56.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                items(shelf.cards, key = { it.key }) { card ->
                                    val isFirst = continueWatching.isEmpty() && index == 0 && card == shelf.cards.first()
                                    val c = card as? Card.Collection
                                    PosterCard(
                                        item = card.item,
                                        onClick = { if (c != null) onOpenCollection(c.collection.id) else onOpen(card.item.id) },
                                        badge = c?.let { "合集 · ${it.members.size} 部" },
                                        watch = remember(card, progress) { watchOf(card, progress) },
                                        modifier = Modifier
                                            .track("${shelf.title}:${card.key}", isFirst)
                                            .onFocusChanged { if (it.hasFocus) focused = card.item },
                                    )
                                }
                                if (shelf.total > shelf.cards.size) {
                                    item(key = "more") {
                                        MoreCard(
                                            total = shelf.total,
                                            onClick = { onBrowse(shelf.filter) },
                                            modifier = Modifier.track("${shelf.title}:more", isFirst = false),
                                        )
                                    }
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
private fun TopBar(onBrowse: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, status: LibraryRepository.Status) {
    Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is LibraryRepository.Status.Loading) {
            Text("同步中…", style = MaterialTheme.typography.labelSmall)
        }
        if (status is LibraryRepository.Status.NeedsAppUpdate) {
            Text("片库需要新版 App, 到设置里更新", style = MaterialTheme.typography.labelSmall, color = Palette.Accent)
        }
        Spacer(Modifier.weight(1f))
        val colors = ButtonDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
        Button(onClick = onBrowse, colors = colors, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("片库", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onSearch, colors = colors, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("搜索", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        val update by ArisaApp.instance.updater.state.collectAsStateWithLifecycle()
        Button(onClick = onSettings, colors = colors, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("设置", style = MaterialTheme.typography.labelMedium)
            if (update is AppUpdater.State.Available || update is AppUpdater.State.Ready) {
                Text(" · 有新版本", style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
            }
        }
    }
}

/** 背景和焦点信息按 lambda 传, 只有它们自己跟着焦点重组. */
@Composable
private fun HomeBackdrop(item: () -> MediaItem?) = Backdrop(item(), settleMs = 300)

@Composable
private fun HeroInfo(item: () -> MediaItem?, modifier: Modifier = Modifier) {
    val target = item() ?: return
    AnimatedContent(
        targetState = target,
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
                LibraryRepository.Status.NeedsAppUpdate -> "片库需要新版 App" to "到设置里更新 App"
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

/** 卡片上的观看状态: 合集看里面的全部作品. */
fun watchOf(card: Card, progress: Map<String, PlaybackProgress>): WatchState =
    if (card is Card.Collection) WatchState.of(card.members, progress) else WatchState.of(card.item, progress)

/** 行尾的「全部」卡片, 和海报一样大. */
@Composable
private fun MoreCard(total: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.width(120.dp).aspectRatio(2f / 3f),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("全部", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("$total 部", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 首页的分类行按片库缓存: 从别的页面返回时首页是重新组合的, 记在页面里的东西都没了, 不缓存就每次返回都重算一遍.
 * 只在主线程用.
 */
private var shelfCache: Pair<Library, List<Shelf>>? = null

private fun shelvesOf(lib: Library, browser: LibraryBrowser): List<Shelf> =
    shelfCache?.takeIf { it.first === lib }?.second ?: buildShelves(browser).also { shelfCache = lib to it }

/**
 * 首页的分类行. 规则和片库页一样 (同一合集两部以上合成一张), 「全部」点进去看到的就是这一行的完整内容.
 * 和整个片库一样的行 (比如全是电影时的「电影」) 不重复列.
 */
private fun buildShelves(b: LibraryBrowser): List<Shelf> = buildList {
    val all = b.count(BrowseFilter())
    if (all == 0) return@buildList
    fun shelf(title: String, f: BrowseFilter, skipIfAll: Boolean = true) {
        if (skipIfAll && b.count(f) == all) return
        val cards = b.cards(f)
        if (cards.isNotEmpty()) add(Shelf(title, f, cards.take(SHELF_LIMIT), cards.size))
    }
    shelf("最近添加", BrowseFilter(), skipIfAll = false)
    shelf("电影", BrowseFilter(type = MediaType.MOVIE))
    shelf("剧集", BrowseFilter(type = MediaType.TV))
    // 有两个以上地区片子够多时才按地区分
    b.regions(BrowseFilter()).filter { it.second >= 8 }.takeIf { it.size >= 2 }
        ?.forEach { (r, _) -> shelf(r.label, BrowseFilter(region = r)) }
    // 片子最多的三个类型, 按评分排
    b.genres(BrowseFilter()).filter { it.second >= 3 }.take(3)
        .forEach { (g, _) -> shelf(g, BrowseFilter(genre = g, sort = BrowseSort.RATING)) }
    shelf("评分最高", BrowseFilter(sort = BrowseSort.RATING), skipIfAll = false)
}
