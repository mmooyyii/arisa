package quarkflix.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import quarkflix.library.MediaFile
import quarkflix.library.MediaItem
import quarkflix.library.MediaType
import quarkflix.library.tmdbImage

/** BlurHash 占位 + 大图淡入. */
@Composable
fun BlurImage(
    url: String?,
    blurHash: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: Color = Palette.Surface,
) {
    Box(modifier.background(fallback)) {
        val placeholder = remember(blurHash) { BlurHashDecoder.decode(blurHash)?.asImageBitmap() }
        if (placeholder != null) {
            Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/**
 * 全屏背景: 背景图 + 左侧、底部压暗, 用主色调做一点色偏.
 * 切换时淡入淡出.
 */
@Composable
fun Backdrop(item: MediaItem?, modifier: Modifier = Modifier, dim: Float = 1f) {
    AnimatedContent(
        targetState = item,
        transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
        contentKey = { it?.id },
        modifier = modifier.fillMaxSize(),
        label = "backdrop",
    ) { current ->
        Box(Modifier.fillMaxSize().background(Palette.Background)) {
            if (current != null) {
                val tint = parseColor(current.art.color, Palette.Background)
                BlurImage(
                    url = tmdbImage(current.backdrop, "w1280"),
                    blurHash = current.art.backdropBlurHash,
                    modifier = Modifier.fillMaxSize(),
                    fallback = tint,
                )
                // 左侧压暗, 留给文字
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Palette.Background.copy(alpha = 0.95f * dim),
                            0.45f to Palette.Background.copy(alpha = 0.55f * dim),
                            0.75f to Color.Transparent,
                        )
                    )
                )
                // 底部压暗, 带一点主色
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to tint.copy(alpha = 0.15f * dim),
                            0.75f to Palette.Background.copy(alpha = 0.92f * dim),
                            1f to Palette.Background,
                        )
                    )
                )
            }
        }
    }
}

/** 有 Logo 图就显示 Logo, 没有就用文字标题. */
@Composable
fun LogoOrTitle(item: MediaItem, maxHeight: Dp = 96.dp, maxWidth: Dp = 420.dp) {
    if (item.logo != null) {
        AsyncImage(
            model = tmdbImage(item.logo, "w500"),
            contentDescription = item.title,
            modifier = Modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
    } else {
        Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "2024 · ★ 8.3 · 2 小时 47 分 · 科幻 / 冒险" */
@Composable
fun MetaLine(item: MediaItem, files: List<MediaFile> = emptyList()) {
    val parts = buildList {
        item.year?.let { add(it.toString()) }
        item.rating?.let { add("★ %.1f".format(it)) }
        if (item.type == MediaType.MOVIE) item.runtime?.let { add(formatRuntime(it)) }
        if (item.type == MediaType.TV) item.seasons.size.takeIf { it > 0 }?.let { add("$it 季") }
        item.genres.take(3).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" / ")) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item.certification?.let { Tag(it) }
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium)
        qualityTags(files).forEach { Tag(it) }
    }
}

@Composable
fun Tag(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.Text)
    }
}

/** 从文件名猜画质标签. */
fun qualityTags(files: List<MediaFile>): List<String> {
    val names = files.joinToString(" ") { it.name }.lowercase()
    return buildList {
        when {
            Regex("2160p|4k|uhd").containsMatchIn(names) -> add("4K")
            Regex("1080p").containsMatchIn(names) -> add("1080P")
        }
        when {
            Regex("\\b(dv|dovi|dolby.?vision)\\b").containsMatchIn(names) -> add("杜比视界")
            Regex("hdr").containsMatchIn(names) -> add("HDR")
        }
        if (Regex("atmos").containsMatchIn(names)) add("ATMOS")
    }
}

fun formatRuntime(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"

@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = RoundedCornerShape(10.dp)),
)

@Composable
private fun focusGlow() = CardDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 16.dp))

/** 竖版海报卡片. */
@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 120.dp) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = focusBorder(),
        glow = focusGlow(),
    ) {
        BlurImage(
            url = tmdbImage(item.poster, "w342"),
            blurHash = item.art.posterBlurHash,
            modifier = Modifier.fillMaxSize(),
            fallback = parseColor(item.art.color),
        )
        if (item.poster == null) {
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomStart) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 3)
            }
        }
    }
}

/** 横版卡片 (继续观看、剧集单集), 底部可带进度条. */
@Composable
fun LandscapeCard(
    imageUrl: String?,
    blurHash: String?,
    title: String,
    subtitle: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 260.dp,
    fallback: Color = Palette.Surface,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = focusBorder(),
            glow = focusGlow(),
        ) {
            Box(Modifier.fillMaxSize()) {
                BlurImage(imageUrl, blurHash, Modifier.fillMaxSize(), fallback = fallback)
                if (progress != null) ProgressBar(progress)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BoxScope.ProgressBar(progress: Float) {
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(Palette.Accent)
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(bottom = 12.dp))
}

/**
 * TV 上 LazyColumn 默认把焦点项滚到固定位置 (pivot), 详情页这种一屏能放下的会被顶上去.
 * 这个版本只在焦点项超出可见区域时才滚动.
 */
@OptIn(ExperimentalFoundationApi::class)
val MinimalBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 || size > containerSize -> offset
            else -> trailing - containerSize
        }
    }
}
