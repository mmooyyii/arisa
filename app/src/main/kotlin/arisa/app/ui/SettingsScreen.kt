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
import arisa.app.data.LibraryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** true: 播夸克转码后的流 (省流量, 非会员也流畅); false: 播原文件. */
    fun transcoded(context: Context) = prefs(context).getBoolean("transcoded", false)
    fun setTranscoded(context: Context, value: Boolean) = prefs(context).edit().putBoolean("transcoded", value).apply()
}

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val app = ArisaApp.instance
    val scope = rememberCoroutineScope()
    val library by app.library.library.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    var transcoded by remember { mutableStateOf(Settings.transcoded(app)) }
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
                    transcoded = !transcoded
                    Settings.setTranscoded(app, transcoded)
                },
                headlineContent = { Text("播放夸克转码版本") },
                supportingContent = { Text(if (transcoded) "播放转码流, 码率低但更流畅" else "播放原文件, 画质最好 (非会员可能限速)") },
                trailingContent = { Switch(checked = transcoded, onCheckedChange = null) },
            )
            ListItem(
                selected = false,
                onClick = onLogout,
                headlineContent = { Text(if (app.session.demo) "退出演示模式" else "退出登录") },
            )
        }
    }
}
