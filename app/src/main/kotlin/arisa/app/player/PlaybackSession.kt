package arisa.app.player

import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import arisa.app.ArisaApp
import arisa.app.ui.Settings
import arisa.library.Episode
import arisa.library.MediaFile
import arisa.library.MediaType
import arisa.library.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import arisa.library.MediaItem as LibraryItem

/**
 * 一次播放: 管 ExoPlayer、直链 (会过期, 出错时换新链接从原位置接着播)、外挂字幕、轨道记忆、进度、下一集.
 * UI 只读这里的状态和调方法.
 */
@OptIn(UnstableApi::class)
class PlaybackSession(
    private val context: Context,
    private val scope: CoroutineScope,
    val item: LibraryItem?,
    first: MediaFile,
    /** 视频帧率确定后回调, 用来切电视刷新率. */
    private val onFrameRate: (Float) -> Unit,
    /** 播完且没有下一集. */
    private val onFinished: () -> Unit,
) {
    data class Stats(
        val videoDecoder: String? = null,
        val audioDecoder: String? = null,
        val audioOutput: String? = null,
        val droppedFrames: Int = 0,
        val bandwidth: Long = 0,
    )

    private val app = ArisaApp.instance
    private val memory = TrackMemory(context)

    /** 每秒从网络收到的字节数, 缓冲时显示. */
    var downloadRate by mutableStateOf(0L)
        private set
    private val received = AtomicLong()
    private val transfers = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) received.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            // 系统解码器 (和音频直通) 优先, 设备解不了的再交给 FFmpeg
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        )
        .setLoadControl(
            // 原盘码率高, 按字节限制缓冲: 缓冲在 Java 堆里, 电视的堆上限一般只有 192MB,
            // 取四成 (分段下载还要占 40MB 左右), 写死 200MB 会在网速快时把堆撑爆
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                .setTargetBufferBytes((Runtime.getRuntime().maxMemory() * 0.4).toInt())
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(20_000, true)
                .build()
        )
        .build()

    var file by mutableStateOf(first)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var stats by mutableStateOf(Stats())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var speed by mutableStateOf(1f)
        private set

    /** 记忆轨道用的 key: 同一部剧的所有集共用. */
    val memoryKey get() = item?.id ?: "fid:${file.fid}"

    val episodes: List<Episode> = item?.takeIf { it.type == MediaType.TV }
        ?.seasons?.flatMap { it.episodes }?.filter { it.files.isNotEmpty() }.orEmpty()

    val currentEpisode get() = episodes.firstOrNull { e -> e.files.any { it.fid == file.fid } }

    val nextEpisode get() = currentEpisode?.let { episodes.getOrNull(episodes.indexOf(it) + 1) }

    val title get() = item?.title ?: file.name

    val subtitle: String?
        get() {
            val ep = currentEpisode ?: return null
            return "第 ${ep.season} 季 第 ${ep.episode} 集" + (ep.title?.let { " · $it" } ?: "")
        }

    private var startJob: Job? = null
    private var restored = false
    private var retries = 0
    private var droppedFrames = 0

    init {
        player.addListener(listener())
        player.addAnalyticsListener(analytics())
        val on = Settings.subtitlesOn(context)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("zh")
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .build()
        scope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                downloadRate = received.getAndSet(0)
            }
        }
    }

    fun open(f: MediaFile, fromStart: Boolean) {
        saveProgress()
        file = f
        restored = false
        retries = 0
        droppedFrames = 0
        stats = Stats()
        val resumeAt = if (fromStart) 0L else app.progress.get(f.fid)?.takeIf { !it.finished }?.positionMs ?: 0L
        start(resumeAt)
    }

    /** 一集有多个版本 (不同压制组) 时, 选和当前文件在同一目录的那个, 换集不换版本. */
    private fun fileOf(ep: Episode): MediaFile {
        val dir = file.path.substringBeforeLast('/')
        return ep.files.firstOrNull { it.path.substringBeforeLast('/') == dir } ?: ep.files.first()
    }

    fun playEpisode(ep: Episode) = open(fileOf(ep), fromStart = false)

    fun playNext(): Boolean {
        val next = nextEpisode ?: return false
        open(fileOf(next), fromStart = true)
        return true
    }

    fun retry() = start(player.currentPosition)

    fun setPlaybackSpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
    }

    private fun start(positionMs: Long) {
        startJob?.cancel()
        loading = true
        error = null
        val f = file
        startJob = scope.launch {
            val link = try {
                app.library.playLink(f)
            } catch (e: Exception) {
                error = "获取播放地址失败: ${e.message}"
                loading = false
                return@launch
            }
            val subtitles = f.subtitles.map { s -> async { runCatching { sideload(s) }.getOrNull() } }.awaitAll().filterNotNull()
            val http = OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(link.headers).setTransferListener(transfers)
            val media = MediaItem.Builder()
                .setUri(link.url)
                .setMediaId(f.fid)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .setSubtitleConfigurations(subtitles)
                .build()
            // DefaultDataSource 包一层, 外挂字幕是本地文件; 网络部分分段并发读 (夸克对不分段的请求限速)
            player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, ParallelRangeDataSource.Factory(http, link.size))).createMediaSource(media), positionMs)
            player.prepare()
            player.playWhenReady = true
            loading = false
        }
    }

    private suspend fun sideload(s: SubtitleFile): MediaItem.SubtitleConfiguration? {
        if (app.session.demo) return null
        val mime = when (s.name.substringAfterLast('.').lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(app.library.subtitle(s)))
            .setMimeType(mime)
            .setLanguage(s.language)
            .setLabel("外挂 " + s.name.substringBeforeLast('.').takeLast(24))
            .build()
    }

    // ---------- 轨道 ----------

    fun groups(type: Int) = tracks.groups.filter { it.type == type && it.isSupported }

    fun select(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        val f = group.getTrackFormat(index)
        val choice = TrackChoice(f.language, f.label)
        when (group.type) {
            C.TRACK_TYPE_AUDIO -> memory.update(memoryKey) { it.copy(audio = choice) }
            C.TRACK_TYPE_TEXT -> memory.update(memoryKey) { it.copy(text = choice, textOff = false) }
        }
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        memory.update(memoryKey) { it.copy(text = null, textOff = true) }
    }

    val subtitlesOff get() = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes

    /** 每个文件第一次拿到轨道时, 套用这部作品上次的选择. */
    private fun restore(tracks: Tracks) {
        val prefs = memory.get(memoryKey) ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        fun find(type: Int, choice: TrackChoice): Pair<Tracks.Group, Int>? {
            val candidates = tracks.groups.filter { it.type == type && it.isSupported }
                .flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g to it } }
            return candidates.firstOrNull { (g, i) -> g.getTrackFormat(i).let { it.language == choice.language && it.label == choice.label } }
                ?: candidates.firstOrNull { (g, i) -> choice.language != null && g.getTrackFormat(i).language == choice.language }
        }
        prefs.audio?.let { find(C.TRACK_TYPE_AUDIO, it) }?.let { (g, i) ->
            builder.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        when {
            prefs.textOff -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            prefs.text != null -> find(C.TRACK_TYPE_TEXT, prefs.text)?.let { (g, i) ->
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    fun selectedFormat(type: Int): Format? = tracks.groups.firstOrNull { it.type == type && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    // ---------- 监听 ----------

    private fun listener() = object : Player.Listener {
        override fun onTracksChanged(t: Tracks) {
            tracks = t
            if (!restored && t.groups.isNotEmpty()) {
                restored = true
                restore(t)
            }
            selectedFormat(C.TRACK_TYPE_VIDEO)?.frameRate?.takeIf { it > 0 }?.let(onFrameRate)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> retries = 0
                Player.STATE_ENDED -> {
                    saveProgress()
                    if (!playNext()) onFinished()
                }
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val position = player.currentPosition
            val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.HttpDataSourceException>().any()
            // 直链过期 (403/410) 或网络断了: 换个新链接从原位置接着播
            if (http && retries < 3) {
                retries++
                scope.launch {
                    delay(1000L * retries)
                    start(position)
                }
                return
            }
            error = when {
                e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    "这台设备解不了这个视频 (${e.errorCodeName}), 可以在选项里切到「夸克转码」"
                http -> "网络出错, 重试了 3 次还是不行"
                else -> "播放出错: ${e.errorCodeName}"
            }
        }
    }

    private fun analytics() = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            stats = stats.copy(videoDecoder = decoderName)
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            stats = stats.copy(audioDecoder = decoderName)
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            // 直通时不会初始化解码器, 清掉上一个音轨的解码器名
            stats = stats.copy(audioDecoder = null)
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            stats = stats.copy(audioOutput = TrackLabels.audioOutput(audioTrackConfig.encoding))
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
            stats = stats.copy(droppedFrames = droppedFrames)
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            stats = stats.copy(bandwidth = bitrateEstimate)
        }
    }

    fun saveProgress() {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        app.progress.save(file.fid, position, duration)
    }

    fun release() {
        saveProgress()
        player.release()
    }
}
