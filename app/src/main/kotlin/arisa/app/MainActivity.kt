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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    data class Search(val query: String = "") : Screen
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
        LaunchedEffect(Unit) {
            app.library.loadCached()
            app.library.refresh()
        }
        BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

        fun play(itemId: String, fid: String, fromStart: Boolean) {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_ITEM, itemId)
                    .putExtra(PlayerActivity.EXTRA_FID, fid)
                    .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
            )
        }

        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                onOpen = { stack += Screen.Detail(it) },
                onPlay = { item, fid -> play(item, fid, false) },
                onSearch = { stack += Screen.Search() },
                onSettings = { stack += Screen.Settings },
            )
            is Screen.Detail -> DetailScreen(
                id = screen.id,
                onPlay = { fid, fromStart -> play(screen.id, fid, fromStart) },
                onSearch = { stack += Screen.Search(it) },
            )
            is Screen.Search -> SearchScreen(screen.query, onOpen = { stack += Screen.Detail(it) })
            Screen.Settings -> SettingsScreen(onLogout = onLogout)
        }
    }
}
