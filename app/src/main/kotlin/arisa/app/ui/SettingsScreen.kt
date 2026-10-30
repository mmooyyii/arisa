package arisa.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import arisa.app.ArisaApp
import arisa.app.BuildConfig
import arisa.app.data.AppUpdater
import arisa.app.data.LibraryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** true: 播夸克转码后的流 (省流量, 非会员也流畅); false: 播原文件. */

    /** 默认打开中文字幕; false 时默认不开字幕 (每部作品选过的以选过的为准). */
    fun subtitlesOn(context: Context) = prefs(context).getBoolean("subtitles_on", true)
    fun setSubtitlesOn(context: Context, value: Boolean) = prefs(context).edit().putBoolean("subtitles_on", value).apply()

    val SUBTITLE_SIZES = listOf("小" to 0.8f, "中" to 1.0f, "大" to 1.25f, "特大" to 1.5f)
    fun subtitleSize(context: Context) = prefs(context).getInt("subtitle_size", 1).coerceIn(SUBTITLE_SIZES.indices)
    fun setSubtitleSize(context: Context, value: Int) = prefs(context).edit().putInt("subtitle_size", value).apply()

    /** 字幕样式: false 白字黑边, true 白字半透明黑底. */
    fun subtitleBackground(context: Context) = prefs(context).getBoolean("subtitle_background", false)
    fun setSubtitleBackground(context: Context, value: Boolean) = prefs(context).edit().putBoolean("subtitle_background", value).apply()

    /** 播放时把电视刷新率切到和片源帧率一致 (24p 片源切 24Hz), 避免画面抖动. */
    fun matchFrameRate(context: Context) = prefs(context).getBoolean("match_frame_rate", false)
    fun setMatchFrameRate(context: Context, value: Boolean) = prefs(context).edit().putBoolean("match_frame_rate", value).apply()
}

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val app = ArisaApp.instance
    val scope = rememberCoroutineScope()
    val library by app.library.library.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    var subtitlesOn by remember { mutableStateOf(Settings.subtitlesOn(app)) }
    var subtitleSize by remember { mutableStateOf(Settings.subtitleSize(app)) }
    var matchFrameRate by remember { mutableStateOf(Settings.matchFrameRate(app)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lib = library
            val syncInfo = when (val s = status) {
                LibraryRepository.Status.Loading -> "同步中…"
                is LibraryRepository.Status.Error -> "失败: ${s.message}"
                LibraryRepository.Status.NoIndex -> "网盘里还没有片库"
                LibraryRepository.Status.NeedsAppUpdate -> "片库是新版刮削器生成的, 需要先更新 App"
                LibraryRepository.Status.Idle -> lib?.let {
                    "${it.items.size} 部 · 刮削于 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it.generatedAt))
                } ?: "—"
            }
            ListItem(
                selected = false,
                onClick = { scope.launch { app.library.refresh() } },
                headlineContent = { Text("同步片库") },
                supportingContent = { Text(syncInfo) },
                modifier = Modifier.focusRequester(first),
            )
            ListItem(
                selected = false,
                onClick = {
                    subtitlesOn = !subtitlesOn
                    Settings.setSubtitlesOn(app, subtitlesOn)
                },
                headlineContent = { Text("默认打开字幕") },
                supportingContent = { Text(if (subtitlesOn) "优先中文字幕; 每部作品会记住你选过的音轨和字幕" else "默认不开字幕") },
                trailingContent = { Switch(checked = subtitlesOn, onCheckedChange = null) },
            )
            ListItem(
                selected = false,
                onClick = {
                    subtitleSize = (subtitleSize + 1) % Settings.SUBTITLE_SIZES.size
                    Settings.setSubtitleSize(app, subtitleSize)
                },
                headlineContent = { Text("字幕大小") },
                trailingContent = { Text(Settings.SUBTITLE_SIZES[subtitleSize].first) },
            )
            ListItem(
                selected = false,
                onClick = {
                    matchFrameRate = !matchFrameRate
                    Settings.setMatchFrameRate(app, matchFrameRate)
                },
                headlineContent = { Text("自动切换刷新率") },
                supportingContent = { Text("播放时把电视切到和片源一样的帧率 (如 24Hz), 切换时会黑屏一两秒") },
                trailingContent = { Switch(checked = matchFrameRate, onCheckedChange = null) },
            )
            val update by app.updater.state.collectAsStateWithLifecycle()
            ListItem(
                selected = false,
                onClick = {
                    when (update) {
                        is AppUpdater.State.Available -> scope.launch { app.updater.download() }
                        is AppUpdater.State.Ready -> app.updater.install()
                        is AppUpdater.State.Downloading, AppUpdater.State.Checking -> {}
                        else -> scope.launch { app.updater.check() }
                    }
                },
                headlineContent = { Text(updateTitle(update)) },
                supportingContent = { Text(updateDetail(update), maxLines = 4) },
            )
            ListItem(
                selected = false,
                onClick = onLogout,
                headlineContent = { Text(if (app.session.demo) "退出演示模式" else "退出登录") },
            )
        }
    }
}

private fun updateTitle(state: AppUpdater.State) = when (state) {
    is AppUpdater.State.Available -> "下载新版本 ${state.manifest.version}"
    is AppUpdater.State.Downloading -> "正在下载 ${state.manifest.version}… ${(state.progress * 100).toInt()}%"
    is AppUpdater.State.Ready -> "安装新版本 ${state.manifest.version}"
    else -> "检查更新"
}

private fun updateDetail(state: AppUpdater.State): String {
    val current = "当前版本 ${BuildConfig.VERSION_NAME}"
    return when (state) {
        AppUpdater.State.Idle -> current
        AppUpdater.State.Checking -> "$current · 检查中…"
        AppUpdater.State.UpToDate -> "$current · 已经是最新版"
        AppUpdater.State.NotFound -> "$current · 网盘里还没有安装包 (刮削器会把新版本转存过来)"
        is AppUpdater.State.Error -> "$current · ${state.message}"
        is AppUpdater.State.Available -> "$current\n${state.manifest.notes.orEmpty()}".trim()
        is AppUpdater.State.Downloading -> current
        is AppUpdater.State.Ready -> "$current · 已下载, 点一下打开系统安装界面"
    }
}
