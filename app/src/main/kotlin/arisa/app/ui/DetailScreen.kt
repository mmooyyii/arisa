package arisa.app.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import arisa.app.ArisaApp
import arisa.library.Episode
import arisa.library.MediaFile
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.PlaybackProgress
import arisa.library.tmdbImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    id: String,
    onPlay: (fid: String, fromStart: Boolean) -> Unit,
    onSearch: (String) -> Unit,
) {
    val app = ArisaApp.instance
    val library by app.library.library.collectAsStateWithLifecycle()
    val progress by app.progress.all.collectAsStateWithLifecycle()
    val item = library?.items?.firstOrNull { it.id == id } ?: return

    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { runCatching { playFocus.requestFocus() } }

    // 默认播哪个文件: 电影取第一个版本; 剧集取看到一半的那集, 或者看完那集的下一集, 否则第一集
    val episodes = item.seasons.flatMap { it.episodes }
    val resume: Pair<Episode?, PlaybackProgress?> = remember(item, progress) { resumeTarget(item, episodes, progress) }
    val targetFid = resume.first?.files?.firstOrNull()?.fid ?: item.files.firstOrNull()?.fid
    val resumeProgress = resume.second

    Box(Modifier.fillMaxSize()) {
        Backdrop(item, dim = 1f)
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalBringIntoView) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 72.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Column {
                        Column(Modifier.width(600.dp)) {
                            LogoOrTitle(item, maxHeight = 110.dp, maxWidth = 460.dp)
                            if (item.logo != null && item.originalTitle == null) Spacer(Modifier.height(4.dp))
                            item.originalTitle?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
                            }
                            Spacer(Modifier.height(14.dp))
                            MetaLine(item, item.files + episodes.flatMap { it.files }.take(3))
                            Spacer(Modifier.height(14.dp))
                            item.overview?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        // 按钮单独一行, 不受上面 600dp 的限宽 (多版本时放不下会被裁掉); 版本多了可以横向滚.
                        // 焦点回到这一行时滚回顶部, 不然往下再往上时标题还在屏幕外
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } },
                        ) {
                            if (targetFid != null) {
                                val label = when {
                                    resumeProgress != null && !resumeProgress.finished && resumeProgress.positionMs > 60_000 ->
                                        "继续播放" + (resume.first?.let { " S${it.season}E${it.episode}" } ?: "") +
                                            " · ${formatClock(resumeProgress.positionMs)}"
                                    resume.first != null -> "播放 S${resume.first!!.season}E${resume.first!!.episode}"
                                    else -> "播放"
                                }
                                Button(
                                    onClick = { onPlay(targetFid, false) },
                                    modifier = Modifier.focusRequester(playFocus),
                                    colors = ButtonDefaults.colors(
                                        containerColor = Color.White, contentColor = Color.Black,
                                        focusedContainerColor = Palette.Accent, focusedContentColor = Color.Black,
                                    ),
                                ) { Text("▶  $label", style = MaterialTheme.typography.titleSmall, color = Color.Black) }
                                if (resumeProgress != null && resumeProgress.positionMs > 60_000) {
                                    Button(onClick = { onPlay(targetFid, true) }, colors = secondaryButton()) { Text("从头播放") }
                                }
                            }
                            // 电影有多个版本时可以选
                            if (item.type == MediaType.MOVIE && item.files.size > 1) {
                                val labels = versionLabels(item.files)
                                item.files.drop(1).forEachIndexed { i, f ->
                                    Button(onClick = { onPlay(f.fid, false) }, colors = secondaryButton()) {
                                        Text(labels[i + 1])
                                    }
                                }
                            }
                        }
                    }
                }

                if (item.type == MediaType.TV && item.seasons.isNotEmpty()) {
                    item(key = "episodes") { EpisodesSection(item, progress, resume.first, onPlay) }
                }

                if (item.cast.isNotEmpty()) {
                    item(key = "cast") {
                        // 焦点进来时整行滚进屏幕, 只按头像滚的话名字会被底边切掉
                        Column(Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
                        }) {
                            SectionTitle("演职员")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                items(item.cast) { c ->
                                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        // 可聚焦, 遥控器才能滚到这一行; 点击按演员搜索
                                        Surface(
                                            onClick = { onSearch(c.name) },
                                            modifier = Modifier.size(84.dp),
                                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = CircleShape)),
                                            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.Surface),
                                        ) {
                                            AsyncImage(
                                                model = tmdbImage(c.profile, "w185"),
                                                contentDescription = c.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(c.name, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1, textAlign = TextAlign.Center)
                                        c.character?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesSection(
    item: MediaItem,
    progress: Map<String, PlaybackProgress>,
    current: Episode?,
    onPlay: (String, Boolean) -> Unit,
) {
    var seasonIndex by remember(item.id) {
        mutableIntStateOf(item.seasons.indexOfFirst { it.number == current?.season }.coerceAtLeast(0))
    }
    val season = item.seasons[seasonIndex.coerceIn(item.seasons.indices)]
    val rowState = rememberLazyListState()
    LaunchedEffect(season.number) {
        val i = season.episodes.indexOfFirst { it == current }
        if (i > 0) rowState.scrollToItem(i)
    }
    Column {
        if (item.seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                items(item.seasons.size) { i ->
                    val s = item.seasons[i]
                    FilterChip(selected = i == seasonIndex, onClick = { seasonIndex = i }) {
                        Text(s.name ?: "第 ${s.number} 季")
                    }
                }
            }
        } else {
            SectionTitle(season.name ?: "第 ${season.number} 季")
        }
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(season.episodes, key = { "${it.season}-${it.episode}-${it.files.first().fid}" }) { ep ->
                val p = ep.files.firstNotNullOfOrNull { progress[it.fid] }
                LandscapeCard(
                    imageUrl = tmdbImage(ep.still ?: item.backdrop, "w780"),
                    blurHash = if (ep.still == null) item.art.backdropBlurHash else null,
                    title = "${ep.episode}. ${ep.title ?: "第 ${ep.episode} 集"}",
                    subtitle = listOfNotNull(ep.runtime?.let { "$it 分钟" }, ep.airDate, if (p?.finished == true) "已看完" else null)
                        .joinToString("  ·  ").ifEmpty { null },
                    progress = p?.takeIf { !it.finished }?.let { it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1) },
                    onClick = { onPlay(ep.files.first().fid, false) },
                    width = 232.dp,
                    fallback = parseColor(item.art.color),
                )
            }
        }
    }
}

@Composable
private fun secondaryButton() = ButtonDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.12f),
    contentColor = Palette.Text,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
)

private fun resumeTarget(item: MediaItem, episodes: List<Episode>, progress: Map<String, PlaybackProgress>): Pair<Episode?, PlaybackProgress?> {
    if (item.type == MediaType.MOVIE) {
        return null to item.files.firstNotNullOfOrNull { progress[it.fid] }
    }
    val last = episodes.mapIndexedNotNull { i, e -> e.files.firstNotNullOfOrNull { progress[it.fid] }?.let { Triple(i, e, it) } }
        .maxByOrNull { it.third.updatedAt } ?: return episodes.firstOrNull() to null
    val (i, ep, p) = last
    if (p.finished) episodes.getOrNull(i + 1)?.let { return it to null }
    return ep to p
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 版本按钮上的字: 画质标签, 标签相同的版本再带上文件大小区分. */
private fun versionLabels(files: List<MediaFile>): List<String> {
    val tags = files.map { qualityTags(listOf(it)).joinToString(" ").ifEmpty { "其他版本" } }
    return files.mapIndexed { i, f ->
        if (tags.count { it == tags[i] } > 1) "${tags[i]} · %.1f GB".format(f.size / 1e9) else tags[i]
    }
}

