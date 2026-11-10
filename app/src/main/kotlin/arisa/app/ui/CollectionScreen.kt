package arisa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import arisa.app.ArisaApp
import arisa.library.WatchState

/** 合集: 同一个顶层目录里的几部作品, 按年份排. */
@Composable
fun CollectionScreen(id: String, onOpen: (String) -> Unit) {
    val app = ArisaApp.instance
    val progress by app.progress.all.collectAsStateWithLifecycle()
    val library by app.library.library.collectAsStateWithLifecycle()
    val collection = remember(library, id) { app.library.collection(id) } ?: return
    val items = remember(library, collection) { collection.items.mapNotNull(app.library::item) }
    val cover = remember(library, collection) { app.library.item(collection.cover) ?: items.firstOrNull() }
    // 从详情页返回时回到进去的那一张 (状态由 MainActivity 的 SaveableStateProvider 保存)
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    // 只读一次, 卡片里读 lastFocus 的话焦点每动一下所有卡片都要重组
    val restoreKey = remember { Snapshot.withoutReadObservation { lastFocus } }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (restoreKey == null || runCatching { restore.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(cover, dim = 1f)
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 36.dp)) {
            Text(collection.name, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("${items.size} 部作品", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(20.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.width(140.dp)) {
                        PosterCard(
                            item = item,
                            onClick = { onOpen(item.id) },
                            width = 140.dp,
                            watch = remember(item, progress) { WatchState.of(item, progress) },
                            modifier = Modifier
                                .then(if (item == items.first()) Modifier.focusRequester(first) else Modifier)
                                .then(if (item.id == restoreKey) Modifier.focusRequester(restore) else Modifier)
                                .onFocusChanged { if (it.hasFocus) lastFocus = item.id },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        item.year?.let { Text("$it", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
