package arisa.app.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import arisa.app.ui.Palette
import arisa.app.ui.Settings
import arisa.app.ui.Tag
import arisa.app.ui.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PanelTab(val title: String) { SUBTITLE("字幕"), AUDIO("音轨"), PICTURE("画面"), EPISODES("剧集"), INFO("信息") }

private val RESIZE_MODES = listOf(
    "适应屏幕" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "填满 (裁切)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "拉伸" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 调了进度不按确定, 过这么久当作取消. */
private const val SCRUB_CANCEL_MS = 10_000L

/** 剩余这么多时间时提示下一集. */
private const val NEXT_PROMPT_MS = 30_000L

/**
 * 遥控器操作:
 * 进度条下面一行按钮: 上一集、下一集 (有的才放)、字幕·音轨·更多. 焦点要么在进度条上, 要么在按钮上.
 *
 * 焦点在进度条上 (默认):
 * - 确定 / 播放键: 暂停、继续 (出现「下一集」提示时是播下一集); 调了进度时是跳过去
 * - 左右: 调进度, 按住越跳越快, 按确定才跳过去
 * - 上: 显示进度条; 下: 到按钮那一行 (进度条藏着时直接显示并选中按钮)
 * 焦点在按钮上: 左右选按钮, 确定执行, 上或返回回到进度条. 进度条藏起来时焦点回到进度条.
 *
 * 菜单键直接打开选项面板. 返回: 先取消调进度 / 回到进度条 / 关面板 / 藏进度条, 再退出.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(session: PlaybackSession, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = session.player
    val scope = rememberCoroutineScope()

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    // 进度条上显示的位置, 只在进度条显示着时更新: 进度条藏着时也一直留在界面上 (只是透明),
    // 用 position 的话藏着也要每秒重组一次
    var osdPosition by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var osd by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    // 要不要提示下一集. 只在进入、离开最后 30 秒时变一次, 不跟着播放位置变
    val showNext by remember {
        derivedStateOf {
            session.nextEpisode != null && duration > 0 && duration - position in 1..NEXT_PROMPT_MS && !panel && session.error == null
        }
    }
    // 进度条或下一集提示 (有倒计时) 显示着的时候一秒看 4 次播放位置, 都没有时一秒一次就够.
    // 进度条一出来就重新开始, 马上拿到最新的位置.
    // position 这几个值只在进度条、下一集提示里面读, 这个函数自己不读: 不然播放时整个页面一秒要重组 4 次
    LaunchedEffect(osd) {
        while (true) {
            position = player.currentPosition
            if (osd) osdPosition = position
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            if (osd) buffered = player.bufferedPosition
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            delay(if (osd || showNext) 250 else 1000)
        }
    }

    // 暂停、缓冲这些状态一变就更新, 不等上面的下一次轮询 (进度条藏着时要等一秒)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var interaction by remember { mutableLongStateOf(0L) }
    // 正在调的进度 (还没按确定), 没在调时是 null
    var scrub by remember { mutableStateOf<Long?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }
    fun touch() {
        osd = true
        interaction = System.currentTimeMillis()
    }
    // 焦点在按钮那一行时选中的是第几个, 在进度条上时是 null
    var buttonFocus by remember { mutableStateOf<Int?>(null) }
    // 播放中没操作就藏起进度条: 焦点在进度条上 4 秒, 在按钮上 10 秒 (要留时间看清选哪个); 正在调进度时不藏.
    // 藏起来后焦点回到进度条
    LaunchedEffect(osd, interaction, playing, panel, scrub == null, buttonFocus == null) {
        if (osd && playing && !panel && scrub == null) {
            delay(if (buttonFocus == null) 4000 else 10_000)
            osd = false
        }
    }
    LaunchedEffect(osd) { if (!osd) buttonFocus = null }

    // 快进快退: 左右键只移动进度条上的位置, 按确定才跳过去, 按下去马上跳, 不等; 按返回取消.
    // 调了位置一直不按确定的话, 过一会儿当作取消.
    fun cancelScrub() {
        scrubJob?.cancel()
        scrub = null
    }
    fun commitScrub() {
        val target = scrub ?: return
        cancelScrub()
        session.seekTo(target, forward = target >= player.currentPosition)
        position = target
        osdPosition = target
        touch()
    }
    fun scrubBy(direction: Int, repeat: Int) {
        val step = when {
            repeat < 6 -> 10_000L
            repeat < 20 -> 30_000L
            else -> 60_000L
        }
        val max = if (duration > 0) duration else Long.MAX_VALUE
        scrub = ((scrub ?: player.currentPosition) + direction * step).coerceIn(0L, max)
        touch()
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(SCRUB_CANCEL_MS)
            scrub = null
        }
    }

    val next = session.nextEpisode

    // 进度条下面的按钮. 换集后按钮个数会变, 选中的下标跟着收回来
    val buttons = listOfNotNull(
        session.previousEpisode?.let { OsdButton("上一集") { session.playPrevious() } },
        next?.let { OsdButton("下一集") { session.playNext() } },
        OsdButton("字幕 · 音轨 · 更多") { panel = true },
    )
    val focusedButton = buttonFocus?.coerceIn(buttons.indices)
    /** 到按钮那一行, 默认选下一集, 没有下一集选第一个. */
    fun focusButtons() {
        cancelScrub()
        buttonFocus = buttons.indexOfFirst { it.title == "下一集" }.coerceAtLeast(0)
        touch()
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panel) { if (!panel) runCatching { rootFocus.requestFocus() } }

    fun back() = when {
        scrub != null -> cancelScrub()
        focusedButton != null -> { buttonFocus = null; touch() }
        panel -> panel = false
        osd && playing -> osd = false
        else -> onExit()
    }
    // 兜底: Compose 里没有焦点时返回键走这里
    BackHandler { back() }

    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var subtitleSize by remember { mutableIntStateOf(Settings.subtitleSize(context)) }
    var subtitleBackground by remember { mutableStateOf(Settings.subtitleBackground(context)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 返回键要抢在前面处理: 有焦点时 Compose 默认拿返回键去清焦点, 面板就关不掉了
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) back()
                true
            }
            // onKeyEvent 要放在 focusable 前面, 才能收到它 (和面板里子项) 的按键
            .onKeyEvent { e ->
                if (panel || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = e.nativeKeyEvent.repeatCount
                val selected = focusedButton
                if (selected != null) {
                    when (e.key) {
                        Key.DirectionLeft -> buttonFocus = (selected - 1).coerceAtLeast(0)
                        Key.DirectionRight -> buttonFocus = (selected + 1).coerceAtMost(buttons.lastIndex)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            if (repeat > 0) return@onKeyEvent true
                            buttonFocus = null
                            buttons[selected].onClick()
                        }
                        Key.DirectionUp -> buttonFocus = null
                        Key.DirectionDown -> {}
                        Key.MediaPlayPause -> if (player.isPlaying) player.pause() else player.play()
                        Key.MediaPlay -> player.play()
                        Key.MediaPause -> player.pause()
                        Key.Menu -> { panel = true; osd = false }
                        Key.MediaNext -> session.playNext()
                        Key.MediaPrevious -> session.playPrevious()
                        else -> return@onKeyEvent false
                    }
                    if (!panel) touch()
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (repeat > 0) return@onKeyEvent true
                        when {
                            scrub != null -> commitScrub()
                            session.error != null -> session.retry()
                            showNext -> session.playNext()
                            else -> {
                                if (player.isPlaying) player.pause() else player.play()
                                touch()
                            }
                        }
                    }
                    Key.MediaPlayPause -> { if (player.isPlaying) player.pause() else player.play(); touch() }
                    Key.MediaPlay -> { player.play(); touch() }
                    Key.MediaPause -> { player.pause(); touch() }
                    Key.DirectionLeft, Key.MediaRewind -> scrubBy(-1, repeat)
                    Key.DirectionRight, Key.MediaFastForward -> scrubBy(1, repeat)
                    Key.DirectionUp -> touch()
                    Key.DirectionDown -> if (repeat == 0) focusButtons()
                    Key.Menu -> { panel = true; osd = false }
                    Key.MediaNext -> session.playNext()
                    Key.MediaPrevious -> session.playPrevious()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                    isFocusable = false
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
                // 进度条显示着时字幕抬到进度条上面, 不然会压在按钮上
                view.subtitleView?.let { styleSubtitles(it, subtitleSize, subtitleBackground, raised = osd && !panel) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if ((buffering || session.loading) && session.error == null) {
            Text(
                "缓冲中…" + session.downloadRate.takeIf { it > 0 }?.let { " %.1f MB/s".format(it / 1048576.0) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(16.dp, 10.dp),
            )
        }

        session.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).width(560.dp).background(Palette.Surface, RoundedCornerShape(12.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("按确定重试 · 按下键换集或打开选项 · 按返回退出", style = MaterialTheme.typography.bodySmall)
            }
        }

        // 进度条一直留着, 显示、隐藏只改透明度: 用 AnimatedVisibility 的话藏起来就整个销毁,
        // 下次出来要重建、所有文字重新排版, 电视上那一帧要 60ms
        val osdAlpha by animateFloatAsState(if (osd && !panel) 1f else 0f, label = "osd")
        Osd(
            session, osdPosition, duration, buffered, scrub, playing, buffering || session.loading, buttons, focusedButton,
            // ModulateAlpha: 透明度直接乘到每个绘制上, 不先画到离屏缓冲再合成, 电视的 GPU 吃不消全屏离屏缓冲
            Modifier.fillMaxSize().graphicsLayer { alpha = osdAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha },
        )

        AnimatedVisibility(
            visible = showNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (osd) 200.dp else 56.dp),
        ) {
            next?.let { ep ->
                Column(Modifier.width(320.dp).background(Palette.Surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(18.dp)) {
                    Text("接下来 · ${((duration - position) / 1000).coerceAtLeast(0)} 秒", style = MaterialTheme.typography.labelSmall, color = Palette.Accent)
                    Spacer(Modifier.height(6.dp))
                    Text("第 ${ep.season} 季 第 ${ep.episode} 集" + (ep.title?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("按确定立即播放", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AnimatedVisibility(
            visible = panel,
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            OptionsPanel(
                session = session,
                resizeMode = resizeMode,
                onResizeMode = { resizeMode = it },
                subtitleSize = subtitleSize,
                onSubtitleSize = { subtitleSize = it; Settings.setSubtitleSize(context, it) },
                subtitleBackground = subtitleBackground,
                onSubtitleBackground = { subtitleBackground = it; Settings.setSubtitleBackground(context, it) },
                onClose = { panel = false },
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun styleSubtitles(view: SubtitleView, sizeIndex: Int, background: Boolean, raised: Boolean) {
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.SUBTITLE_SIZES[sizeIndex].second)
    view.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            if (background) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            if (background) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        )
    )
    // ASS 字幕自带的位置、颜色保留, 字号按上面统一
    view.setApplyEmbeddedStyles(true)
    view.setApplyEmbeddedFontSizes(false)
    view.setBottomPaddingFraction(if (raised) 0.3f else 0.06f)
}

@OptIn(UnstableApi::class)
@Composable
private fun Osd(
    session: PlaybackSession, position: Long, duration: Long, buffered: Long, scrub: Long?, playing: Boolean, buffering: Boolean,
    buttons: List<OsdButton>, focusedButton: Int?, modifier: Modifier,
) {
    val shown = scrub ?: position
    val clock = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    Box(modifier) {
        // 顶部: 片名、集数、时间
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 48.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                session.subtitle?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(clock.format(Date()), style = MaterialTheme.typography.titleMedium)
        }

        if (!playing && !buffering && scrub == null) PauseMark(Modifier.align(Alignment.Center))

        // 底部: 进度条、时间、格式标签
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 56.dp, end = 56.dp, top = 56.dp, bottom = 32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formatTags(session).forEach { Tag(it) }
            }
            Spacer(Modifier.height(14.dp))
            ProgressBar(shown, buffered, duration, scrubbing = scrub != null, focused = focusedButton == null)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(shown), style = MaterialTheme.typography.titleSmall)
                if (scrub != null) {
                    val delta = scrub - session.player.currentPosition
                    Spacer(Modifier.width(12.dp))
                    Text((if (delta >= 0) "+" else "-") + formatClock(kotlin.math.abs(delta)), style = MaterialTheme.typography.labelMedium, color = Palette.Accent)
                }
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    Text("-${formatClock(duration - shown)}", style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buttons.forEachIndexed { i, b -> OsdButtonView(b.title, focused = i == focusedButton) }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        scrub != null -> "确定 跳到这里 · 返回 取消"
                        focusedButton != null -> "← → 选择 · 确定 · ↑ 回到进度条"
                        else -> "← → 调进度 · 确定 跳转 · ↓ 选按钮"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (scrub != null) Palette.Accent else Palette.TextTertiary,
                )
            }
        }
    }
}

private class OsdButton(val title: String, val onClick: () -> Unit)

/** 进度条下面的按钮. 焦点不走 Compose (按键都在根上处理), 只按 [focused] 画选中的样子. */
@Composable
private fun OsdButtonView(title: String, focused: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = if (focused) Color.Black else Palette.Text,
        modifier = Modifier
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun PauseMark(modifier: Modifier) {
    Row(
        modifier.size(88.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(44.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp, 32.dp).background(Color.White, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(10.dp, 32.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}

/** 焦点在进度条上时, 当前位置画一个圆点. */
@Composable
private fun ProgressBar(position: Long, buffered: Long, duration: Long, scrubbing: Boolean, focused: Boolean) {
    // 时长还不知道时只画底条
    val total = if (duration > 0) duration.toFloat() else Float.MAX_VALUE
    val played = (position / total).coerceIn(0f, 1f)
    val barHeight = if (scrubbing) 8.dp else 5.dp
    val color = when {
        scrubbing -> Palette.Accent
        focused -> Color.White
        else -> Color.White.copy(alpha = 0.6f)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((buffered / total).coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.35f)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(color))
        }
        if (focused) {
            Box(Modifier.offset(x = maxWidth * played - 8.dp).size(16.dp).background(color, CircleShape))
        }
    }
}

@OptIn(UnstableApi::class)
private fun formatTags(session: PlaybackSession): List<String> = buildList {
    session.selectedFormat(C.TRACK_TYPE_VIDEO)?.let { v ->
        TrackLabels.resolution(v)?.let(::add)
        TrackLabels.hdr(v)?.let(::add)
    }
    session.selectedFormat(C.TRACK_TYPE_AUDIO)?.let { a ->
        TrackLabels.codec(a)?.let(::add)
        TrackLabels.channels(a.channelCount)?.let(::add)
    }
}

// ---------- 选项面板 ----------

private data class Option(val title: String, val detail: String? = null, val selected: Boolean, val onClick: () -> Unit)

@OptIn(UnstableApi::class)
@Composable
private fun OptionsPanel(
    session: PlaybackSession,
    resizeMode: Int,
    onResizeMode: (Int) -> Unit,
    subtitleSize: Int,
    onSubtitleSize: (Int) -> Unit,
    subtitleBackground: Boolean,
    onSubtitleBackground: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val tabs = PanelTab.entries.filter { it != PanelTab.EPISODES || session.episodes.isNotEmpty() }
    var tab by remember { mutableIntStateOf(0) }
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }

    Column(
        Modifier.fillMaxWidth().height(330.dp)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.95f)))
            .padding(start = 56.dp, end = 56.dp, top = 24.dp),
    ) {
        TabRow(selectedTabIndex = tab, modifier = Modifier.focusRequester(tabFocus)) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onFocus = { tab = i },
                    colors = TabDefaults.pillIndicatorTabColors(
                        contentColor = Palette.TextSecondary,
                        selectedContentColor = Palette.Text,
                        focusedContentColor = Color.Black,
                        focusedSelectedContentColor = Color.Black,
                    ),
                ) {
                    // 主题的字体样式里写死了颜色, 这里要用 Tab 给的内容色
                    Text(t.title, style = MaterialTheme.typography.titleSmall, color = LocalContentColor.current, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tabs[tab]) {
            PanelTab.SUBTITLE -> {
                val off = session.subtitlesOff
                val options = buildList {
                    add(Option("关闭", selected = off) { session.disableSubtitles() })
                    session.groups(C.TRACK_TYPE_TEXT).forEach { g ->
                        for (i in 0 until g.length) {
                            if (!g.isTrackSupported(i)) continue
                            add(Option(TrackLabels.text(g.getTrackFormat(i)), selected = !off && g.isTrackSelected(i)) { session.select(g, i) })
                        }
                    }
                    add(Option("字幕大小", Settings.SUBTITLE_SIZES[subtitleSize].first, selected = false) {
                        onSubtitleSize((subtitleSize + 1) % Settings.SUBTITLE_SIZES.size)
                    })
                    add(Option("字幕样式", if (subtitleBackground) "半透明底" else "黑色描边", selected = false) {
                        onSubtitleBackground(!subtitleBackground)
                    })
                    addAll(delayOptions("字幕", session.subtitleDelayMs, stepMs = 250, session::adjustSubtitleDelay))
                }
                OptionList(options)
            }
            PanelTab.AUDIO -> {
                val options = session.groups(C.TRACK_TYPE_AUDIO).flatMap { g ->
                    (0 until g.length).filter(g::isTrackSupported).map { i ->
                        Option(TrackLabels.audio(g.getTrackFormat(i)), selected = g.isTrackSelected(i)) { session.select(g, i) }
                    }
                }
                if (options.isEmpty()) Text("没有可选的音轨", style = MaterialTheme.typography.bodyMedium)
                else OptionList(options + delayOptions("声音", session.audioDelayMs, stepMs = 50, session::adjustAudioDelay))
            }
            PanelTab.PICTURE -> OptionList(
                RESIZE_MODES.map { (name, mode) -> Option("画面 · $name", selected = resizeMode == mode) { onResizeMode(mode) } } +
                    SPEEDS.map { s -> Option("速度 · ${if (s == 1f) "正常" else "${s}x"}", selected = session.speed == s) { session.setPlaybackSpeed(s) } }
            )
            PanelTab.EPISODES -> {
                val current = session.currentEpisode
                OptionList(
                    session.episodes.map { e ->
                        Option("S${e.season}E${e.episode}" + (e.title?.let { "  $it" } ?: ""), selected = e == current) {
                            if (e != current) session.playEpisode(e)
                            onClose()
                        }
                    },
                )
            }
            PanelTab.INFO -> InfoLines(session)
        }
    }
}

/** 调延迟的三项: 晚一点、早一点、归零. [adjust] 传 0 是归零. */
private fun delayOptions(what: String, delayMs: Long, stepMs: Long, adjust: (Long) -> Unit): List<Option> {
    val now = when {
        delayMs == 0L -> "现在和画面对齐"
        delayMs > 0 -> "现在比画面晚 %.2f 秒".format(delayMs / 1000.0)
        else -> "现在比画面早 %.2f 秒".format(-delayMs / 1000.0)
    }
    val step = "%.2f".format(stepMs / 1000.0)
    return buildList {
        add(Option("${what}晚一点", "$now · 按一次 $step 秒", selected = false) { adjust(stepMs) })
        add(Option("${what}早一点", "$now · 按一次 $step 秒", selected = false) { adjust(-stepMs) })
        if (delayMs != 0L) add(Option("${what}延迟归零", selected = false) { adjust(0) })
    }
}

/** 从标签栏进入列表时, 焦点落在当前选中的那一项. */
@Composable
private fun OptionList(options: List<Option>) {
    val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val selectedFocus = remember { FocusRequester() }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.width(640.dp).focusRestorer(selectedFocus),
    ) {
        itemsIndexed(options) { i, o ->
            ListItem(
                selected = o.selected,
                onClick = o.onClick,
                headlineContent = { Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = o.detail?.let { { Text(it, maxLines = 1) } },
                trailingContent = if (o.selected) ({ Text("✓", color = Palette.Accent) }) else null,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    selectedContainerColor = Color.Transparent,
                    focusedSelectedContainerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Palette.Text,
                    focusedContentColor = Palette.Text,
                    selectedContentColor = Palette.Text,
                    focusedSelectedContentColor = Palette.Text,
                ),
                modifier = Modifier.height(if (o.detail != null) 64.dp else 48.dp)
                    .then(if (i == initial) Modifier.focusRequester(selectedFocus) else Modifier),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InfoLines(session: PlaybackSession) {
    val s = session.stats
    val lines = buildList {
        add("文件" to session.file.name)
        session.selectedFormat(C.TRACK_TYPE_VIDEO)?.let { add("视频" to TrackLabels.video(it)) }
        s.videoDecoder?.let { add("视频解码" to it) }
        session.selectedFormat(C.TRACK_TYPE_AUDIO)?.let { add("音频" to TrackLabels.audio(it)) }
        add("音频输出" to listOfNotNull(s.audioOutput, s.audioDecoder?.let { "解码器 $it" }).joinToString(" · ").ifEmpty { "—" })
        session.selectedFormat(C.TRACK_TYPE_TEXT)?.let { add("字幕" to TrackLabels.text(it)) }
        add("网速" to if (s.bandwidth > 0) "%.1f Mbps".format(s.bandwidth / 1e6) else "—")
        add("丢帧" to s.droppedFrames.toString())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { (k, v) ->
            Row {
                Text(k, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.width(88.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
