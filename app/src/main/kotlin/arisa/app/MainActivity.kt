package arisa.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import arisa.library.BrowseFilter
import arisa.app.ui.BrowseScreen
import arisa.app.ui.CollectionScreen
import arisa.app.ui.DetailScreen
import arisa.app.ui.HomeScreen
import arisa.app.ui.LoginScreen
import arisa.app.ui.Palette
import arisa.app.ui.ArisaTheme
import arisa.app.ui.SearchScreen
import arisa.app.ui.SettingsScreen

/** 页面栈. */
sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Collection(val id: String) : Screen
    data class Search(val query: String = "") : Screen
    data class Browse(val filter: BrowseFilter = BrowseFilter()) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = ArisaApp.instance
        setContent {
            ArisaTheme {
                Box(Modifier.fillMaxSize().background(Palette.Background)) {
                    val cookie by app.session.cookie.collectAsStateWithLifecycle()
                    var demo by rememberSaveable { mutableStateOf(app.session.demo) }
                    if (cookie == null && !demo) {
                        LoginScreen(
                            onLoggedIn = { },
                            onDemo = {
                                app.session.demo = true
                                // 演示用的观看进度, 让"继续观看"有内容
                                app.progress.save("demo-1396-3", 25 * 60_000L, 47 * 60_000L)
                                app.progress.save("demo-157336", 62 * 60_000L, 169 * 60_000L)
                                demo = true
                            },
                        )
                    } else {
                        Navigation(onLogout = { app.session.logout(); app.library.clear(); demo = false })
                    }
                }
            }
        }
    }

    @Composable
    private fun Navigation(onLogout: () -> Unit) {
        val app = ArisaApp.instance
        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        // 每层页面的滚动位置、焦点等存在这里, 返回时回到进去之前的地方; 出栈的页面清掉
        val saveable = rememberSaveableStateHolder()
        LaunchedEffect(Unit) {
            app.library.loadCached()
            app.library.refresh()
            app.syncProgress(onlyIfDirty = false)
            app.updater.check()
        }
        BackHandler(enabled = stack.size > 1) { saveable.removeState(stack.removeAt(stack.lastIndex).toString()) }

        fun play(itemId: String, fid: String, fromStart: Boolean) {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_ITEM, itemId)
                    .putExtra(PlayerActivity.EXTRA_FID, fid)
                    .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
            )
        }

        val screen = stack.last()
        // 换页时新页面淡入, 同时从侧面滑进来一小段: 往里走从右边来, 返回从左边来. 旧页面直接换掉.
        // 只动这一层的透明度和位置 (都是 GPU 上的变换): 不用缩放, 缩放的话整屏的字每一帧都要重新栅格化;
        // ModulateAlpha 是直接按透明度画, 不先画到一块全屏的离屏缓冲区.
        val depth = stack.size
        val lastDepth = remember { intArrayOf(depth) }
        val enter = remember(screen) { Enter(Animatable(0f), forward = depth >= lastDepth[0]) }
        SideEffect { lastDepth[0] = depth }
        LaunchedEffect(enter) { enter.progress.animateTo(1f, tween(220, easing = LinearOutSlowInEasing)) }
        val slide = with(LocalDensity.current) { 36.dp.toPx() }
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = enter.progress.value
            alpha = p
            translationX = (1f - p) * slide * if (enter.forward) 1 else -1
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { saveable.SaveableStateProvider(screen.toString()) { when (screen) {
            Screen.Home -> HomeScreen(
                onOpen = { stack += Screen.Detail(it) },
                onOpenCollection = { stack += Screen.Collection(it) },
                onPlay = { item, fid -> play(item, fid, false) },
                onBrowse = { stack += Screen.Browse(it) },
                onSearch = { stack += Screen.Search() },
                onSettings = { stack += Screen.Settings },
            )
            is Screen.Detail -> DetailScreen(
                id = screen.id,
                onPlay = { fid, fromStart -> play(screen.id, fid, fromStart) },
                onSearch = { stack += Screen.Search(it) },
            )
            is Screen.Collection -> CollectionScreen(screen.id, onOpen = { stack += Screen.Detail(it) })
            is Screen.Search -> SearchScreen(
                screen.query,
                onOpen = { stack += Screen.Detail(it) },
                onOpenCollection = { stack += Screen.Collection(it) },
                onPlay = { fid -> play("", fid, false) },
            )
            is Screen.Browse -> BrowseScreen(
                screen.filter,
                onOpen = { stack += Screen.Detail(it) },
                onOpenCollection = { stack += Screen.Collection(it) },
            )
            Screen.Settings -> SettingsScreen(onLogout = onLogout)
        } } }
    }

    /** 一次换页的进场动画. */
    private class Enter(val progress: Animatable<Float, AnimationVector1D>, val forward: Boolean)
}
