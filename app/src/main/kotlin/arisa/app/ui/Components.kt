package arisa.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.PlatformContext
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.request.transformations
import coil3.size.Size as CoilSize
import coil3.transform.Transformation
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import arisa.library.MediaFile
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.WatchState
import arisa.library.tmdbImage

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
        val painter = url?.let { rememberAsyncImagePainter(it) }
        val loaded = painter?.state?.collectAsState()?.value is AsyncImagePainter.State.Success
        // 大图出来后就不再画占位图, 少一层全屏绘制 (电视 GPU 弱, 层数多了掉帧)
        if (!loaded) {
            val placeholder = remember(blurHash) { BlurHashDecoder.decode(blurHash)?.asImageBitmap() }
            if (placeholder != null) Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (painter != null) Image(painter, null, Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

/**
 * 全屏背景: 背景图 + 左侧、底部压暗, 用主色调做一点色偏. 切换时新图在旧图上面淡入.
 * 压暗在后台线程画进图片里 (随图片缓存), 屏幕上只有一张图: 电视 GPU 弱, 每帧多画几层全屏就掉帧.
 *
 * [item] 一变就开始加载新图 (这时还看不见), 过 [settleMs] 后淡入. 首页传 300ms: 遥控器连按时背景不动,
 * 换背景对电视 GPU 是重活. 这期间 [item] 又变了的话, 还没用上的那张直接丢掉, 它没下完的请求也跟着取消,
 * 不和最后停下来的那张抢网络和 CPU. 图片只由这里加载一次, 不另外预加载 (两边各发一个请求的话会下载、解码两遍).
 */
@Composable
fun Backdrop(item: MediaItem?, modifier: Modifier = Modifier, dim: Float = 1f, settleMs: Long = 0) {
    // 屏幕上的那张, 和正在加载、等着淡入的那张
    var shown by remember { mutableStateOf(item) }
    var incoming by remember { mutableStateOf<MediaItem?>(null) }
    // 开始淡入了. 之前新图那一层只管加载, 占位图不用建
    var revealing by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }
    val target by rememberUpdatedState(item)
    LaunchedEffect(Unit) {
        snapshotFlow { target }.collectLatest { t ->
            if (t == null || t.id == shown?.id) {
                incoming = null
                if (t == null) delay(settleMs)
                shown = t
                return@collectLatest
            }
            revealing = false
            incoming = t
            delay(settleMs)
            // 开始淡入后就做完, 中途焦点又动了也不打断 (打断的话淡到一半的图会突然消失)
            withContext(NonCancellable) {
                revealing = true
                fade.snapTo(0f)
                fade.animateTo(1f, tween(200))
                shown = t
                incoming = null
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        val coming = incoming
        for (current in listOfNotNull(shown, coming)) {
            key(current.id) {
                val fadingIn = current === coming
                BackdropImage(
                    current, dim,
                    placeholder = !fadingIn || revealing,
                    // 淡入只改这一层的透明度. ModulateAlpha: 直接按透明度画, 不先画到一块全屏的离屏缓冲区再合成
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (fadingIn) fade.value else 1f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                )
            }
        }
    }
}

@Composable
private fun BackdropImage(item: MediaItem, dim: Float, placeholder: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier) {
        val tint = parseColor(item.art.color, Palette.Background)
        val painter = rememberAsyncImagePainter(remember(item.id, dim) { backdropRequest(context, item, dim) })
        val loaded = painter.state.collectAsState().value is AsyncImagePainter.State.Success
        if (!loaded && placeholder) {
            // 大图出来前的一小会儿: BlurHash 占位, 压暗现场画
            BlurImage(null, item.art.backdropBlurHash, Modifier.fillMaxSize(), fallback = tint)
            Box(Modifier.fillMaxSize().drawWithCache {
                val (left, bottom) = dimStops(tint, dim)
                val l = Brush.horizontalGradient(*left.toTypedArray())
                val b = Brush.verticalGradient(*bottom.toTypedArray())
                onDrawBehind { drawRect(l); drawRect(b) }
            })
        }
        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** 左侧压暗留给文字, 底部压暗带一点主色. */
private fun dimStops(tint: Color, dim: Float) = listOf(
    0f to Palette.Background.copy(alpha = 0.95f * dim),
    0.45f to Palette.Background.copy(alpha = 0.55f * dim),
    0.75f to Color.Transparent,
) to listOf(
    0f to Color.Transparent,
    0.45f to tint.copy(alpha = 0.15f * dim),
    0.75f to Palette.Background.copy(alpha = 0.92f * dim),
    1f to Palette.Background,
)

/** 把压暗渐变画进背景图. */
private class DimTransformation(private val tint: Color, private val dim: Float) : Transformation() {
    override val cacheKey = "dim-${tint.toArgb()}-$dim"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        val out = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val (left, bottom) = dimStops(tint, dim)
        fun shader(stops: List<Pair<Float, Color>>, x1: Float, y1: Float) = LinearGradient(
            0f, 0f, x1, y1,
            stops.map { it.second.toArgb() }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP,
        )
        val paint = Paint()
        paint.shader = shader(left, w, 0f)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = shader(bottom, 0f, h)
        canvas.drawRect(0f, 0f, w, h, paint)
        // 转成硬件位图, 只在显存里存一份 (普通位图画的时候还要再传一份到 GPU)
        return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }
}

/** 固定尺寸: 不用等画到屏幕上才知道要多大, 新图还看不见的时候就能开始加载. */
private fun backdropRequest(context: PlatformContext, item: MediaItem, dim: Float) =
    ImageRequest.Builder(context)
        .data(tmdbImage(item.backdrop, BACKDROP_SIZE))
        .size(1280, 720)
        .transformations(DimTransformation(parseColor(item.art.color, Palette.Background), dim))
        .build()

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
            "2160p" in names || "4k" in names || "uhd" in names -> add("4K")
            "1080p" in names -> add("1080P")
        }
        when {
            DOLBY_VISION.containsMatchIn(names) -> add("杜比视界")
            "hdr" in names -> add("HDR")
        }
        if ("atmos" in names) add("ATMOS")
    }
}

/** 「4K DV杜比视界」: DV 后面紧跟汉字, \\b 不算边界, 用前后不是字母判断. 首页焦点每动一下都要用, 只编译一次. */
private val DOLBY_VISION = Regex("杜比视界|dolby.?vision|dovi|(?<![a-z])dv(?![a-z])")

fun formatRuntime(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"

@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = RoundedCornerShape(10.dp)),
)


/** 竖版海报卡片. [badge] 显示在左下角, 合集用来标出有几部. [watch]: 右上角的已看标记、底部的进度条. */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    badge: String? = null,
    watch: WatchState = WatchState.Unwatched,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = focusBorder(),
    ) {
        // Card 的内容是从上到下排的, 角标要叠在海报上面, 所以包一层 Box
        Box(Modifier.fillMaxSize()) {
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
            badge?.let {
                Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomStart) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            WatchMarker(watch)
        }
    }
}

/** 海报上的观看状态: 看完了打勾, 剧集没看完的标还剩几集, 电影看了一半的在底边画进度. 没看过的不标. */
@Composable
private fun BoxScope.WatchMarker(watch: WatchState) {
    val label = when (watch) {
        WatchState.Watched -> "✓"
        is WatchState.Remaining -> "剩 ${watch.remaining} 集"
        else -> null
    }
    if (label != null) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (watch == WatchState.Watched) Palette.Accent else Palette.Text,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    if (watch is WatchState.InProgress) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f)))
            Box(Modifier.fillMaxWidth(watch.fraction.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
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
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = focusBorder(),
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
 * 这个版本只在焦点项超出可见区域时才滚动. [trailingMargin]: 往下滚时焦点项下面多露出的距离, 给卡片下方的文字留位置.
 */
@OptIn(ExperimentalFoundationApi::class)
fun minimalBringIntoView(trailingMargin: Float = 0f) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing + trailingMargin <= containerSize -> 0f
            offset < 0 || size > containerSize -> offset
            else -> minOf(trailing + trailingMargin - containerSize, offset)
        }
    }
}

private const val BACKDROP_SIZE = "w1280"
