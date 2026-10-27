package quarkflix.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import quarkflix.app.ui.Settings
import quarkflix.library.Episode
import quarkflix.library.MediaFile
import quarkflix.library.MediaType
import quarkflix.library.MediaItem as LibraryItem

/**
 * 播放器: 每次播放前现取直链 (会过期), 带上夸克要求的 header 直连 CDN.
 * 每 10 秒和退出时保存进度; 剧集播完自动下一集.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var item: LibraryItem? = null
    private var currentFile: MediaFile? = null
    private var saveJob: Job? = null
    private var startJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 4000
        }
        setContentView(playerView)

        val app = QuarkflixApp.instance
        item = app.library.item(intent.getStringExtra(EXTRA_ITEM) ?: "")
        val fid = intent.getStringExtra(EXTRA_FID) ?: return finish()
        val file = allFiles().firstOrNull { it.fid == fid } ?: return finish()
        play(file, fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false))
    }

    private fun allFiles(): List<MediaFile> {
        val it = item ?: return emptyList()
        return it.files + it.seasons.flatMap { s -> s.episodes.flatMap { e -> e.files } }
    }

    private fun episodeOf(file: MediaFile): Episode? =
        item?.seasons?.flatMap { it.episodes }?.firstOrNull { e -> e.files.any { it.fid == file.fid } }

    private fun play(file: MediaFile, fromStart: Boolean) {
        val app = QuarkflixApp.instance
        saveProgress()
        currentFile = file
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            val link = try {
                app.library.playLink(file, Settings.transcoded(this@PlayerActivity))
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "获取播放地址失败: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val dataSource = OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(link.headers)
            val exo = player ?: ExoPlayer.Builder(this@PlayerActivity)
                .setRenderersFactory(
                    // 设备解不了的音频 (DTS/TrueHD 等) 优先交给扩展解码器, 没有就走系统
                    DefaultRenderersFactory(this@PlayerActivity).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                )
                .build().also { p ->
                    player = p
                    playerView.player = p
                    p.addListener(listener)
                }
            exo.setMediaSource(
                DefaultMediaSourceFactory(dataSource).createMediaSource(
                    MediaItem.Builder()
                        .setUri(link.url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title(file)).build())
                        .build()
                )
            )
            val resumeAt = if (fromStart) 0L else app.progress.get(file.fid)?.takeIf { !it.finished }?.positionMs ?: 0L
            if (resumeAt > 0) exo.seekTo(resumeAt)
            exo.prepare()
            exo.playWhenReady = true
            startSaving()
        }
    }

    private fun title(file: MediaFile): String {
        val i = item ?: return file.name
        val ep = episodeOf(file) ?: return i.title
        return "${i.title}  S${ep.season}E${ep.episode}" + (ep.title?.let { "  $it" } ?: "")
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveProgress()
                playNext() || run { finish(); true }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "播放出错: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
        }
    }

    /** 剧集自动播下一集. */
    private fun playNext(): Boolean {
        val i = item ?: return false
        if (i.type != MediaType.TV) return false
        val file = currentFile ?: return false
        val episodes = i.seasons.flatMap { it.episodes }
        val index = episodes.indexOfFirst { e -> e.files.any { it.fid == file.fid } }
        val next = episodes.getOrNull(index + 1)?.files?.firstOrNull() ?: return false
        play(next, fromStart = true)
        return true
    }

    private fun startSaving() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val file = currentFile ?: return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (p.playbackState == Player.STATE_ENDED) duration else p.currentPosition
        QuarkflixApp.instance.progress.save(file.fid, position, duration)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player
        // 控制栏没出来时, 左右键直接快退/快进 10 秒
        if (p != null && !playerView.isControllerFullyVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekTo(p.currentPosition + 10_000); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { p.playWhenReady = !p.playWhenReady; playerView.showController(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        saveJob?.cancel()
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "item"
        const val EXTRA_FID = "fid"
        const val EXTRA_FROM_START = "from_start"
    }
}
