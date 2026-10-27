package arisa.app

import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import arisa.app.player.PlaybackSession
import arisa.app.player.PlayerScreen
import arisa.app.ui.ArisaTheme
import arisa.app.ui.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器页面. 播放逻辑在 [PlaybackSession], 界面在 [PlayerScreen].
 * 没有作品 id 时 (未识别的视频) 按 fid 在未识别列表里找.
 */
class PlayerActivity : ComponentActivity() {
    private var session: PlaybackSession? = null
    private var frameRateMatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = ArisaApp.instance
        val item = app.library.item(intent.getStringExtra(EXTRA_ITEM) ?: "")
        val fid = intent.getStringExtra(EXTRA_FID) ?: return finish()
        val allFiles = item?.let { it.files + it.seasons.flatMap { s -> s.episodes.flatMap { e -> e.files } } }.orEmpty()
        val file = allFiles.firstOrNull { it.fid == fid } ?: app.library.unmatchedFile(fid) ?: return finish()

        val s = PlaybackSession(this, lifecycleScope, item, file, onFrameRate = ::matchFrameRate, onFinished = ::finish)
        session = s
        setContent { ArisaTheme { PlayerScreen(s, onExit = ::finish) } }
        s.open(file, fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false))
    }

    /**
     * 把电视切到能整除片源帧率的刷新率 (23.976 → 23.976/47.95Hz, 25 → 50Hz), 分辨率不变.
     * 只在打开设置时做, 每次播放只切一次.
     */
    private fun matchFrameRate(fps: Float) {
        if (frameRateMatched || !Settings.matchFrameRate(this)) return
        frameRateMatched = true
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { mode ->
                val ratio = mode.refreshRate / fps
                val multiple = ratio.roundToInt()
                if (multiple >= 1 && abs(ratio - multiple) < 0.01f) mode to multiple else null
            }
            .minByOrNull { it.second }?.first ?: return
        if (best.modeId == current.modeId) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onPause() {
        super.onPause()
        session?.saveProgress()
        session?.player?.pause()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM = "item"
        const val EXTRA_FID = "fid"
        const val EXTRA_FROM_START = "from_start"
    }
}
