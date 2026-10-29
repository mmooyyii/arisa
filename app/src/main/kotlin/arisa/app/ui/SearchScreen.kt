package arisa.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import arisa.app.ArisaApp
import arisa.library.LibrarySearch
import arisa.library.LibrarySearch.Hit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按片名、原名、合集名、演员、导演、类型、年份搜, 支持拼音和首字母; 也能搜到未识别的文件. */
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onOpen: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlay: (fid: String) -> Unit,
) {
    val app = ArisaApp.instance
    val library by app.library.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var inputFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // 建索引要查拼音表, 放后台
    val index by produceState<LibrarySearch?>(null, library) {
        value = library?.let { withContext(Dispatchers.Default) { LibrarySearch(it) } }
    }
    val items = library?.items.orEmpty()
    val results = remember(query, items, index) {
        if (query.isBlank()) items.sortedByDescending { it.addedAt }.map { Hit.Item(it, 0) }
        else index?.search(query).orEmpty()
    }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 36.dp)) {
        Text("搜索", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .width(520.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .border(2.dp, if (inputFocused) Palette.Focus else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (query.isEmpty()) Text("片名、演员、导演、类型, 可以打拼音首字母", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.focusRequester(focus).onFocusChanged { inputFocused = it.isFocused },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(if (query.isBlank()) "全部 ${results.size}" else "${results.size} 个结果", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(
                results,
                key = { hit ->
                    when (hit) {
                        is Hit.Item -> hit.item.id
                        is Hit.Collection -> hit.collection.id
                        is Hit.File -> "file:${hit.file.fid}"
                    }
                },
                // 文件是横图, 占两格
                span = { if (it is Hit.File) GridItemSpan(2) else GridItemSpan(1) },
            ) { hit ->
                when (hit) {
                    is Hit.Item -> PosterCard(hit.item, onClick = { onOpen(hit.item.id) })
                    is Hit.Collection -> PosterCard(
                        hit.cover.copy(title = hit.collection.name),
                        onClick = { onOpenCollection(hit.collection.id) },
                        badge = "合集 · ${hit.collection.items.size} 部",
                    )
                    is Hit.File -> LandscapeCard(
                        imageUrl = null,
                        blurHash = null,
                        title = hit.file.name,
                        subtitle = "未识别",
                        progress = null,
                        onClick = { onPlay(hit.file.fid) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
