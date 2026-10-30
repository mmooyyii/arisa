package arisa.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import arisa.app.ArisaApp
import arisa.library.BrowseFilter
import arisa.library.BrowseSort
import arisa.library.LibraryBrowser
import arisa.library.MediaType
import arisa.library.Region

/** 筛选项: 类别、地区、类型、年代、排序. */
private enum class Dimension(val label: String) { TYPE("类别"), REGION("地区"), GENRE("类型"), DECADE("年代"), SORT("排序") }

private class Option(val label: String, val filter: BrowseFilter, val selected: Boolean)

/**
 * 片库: 全部作品的网格, 顶上一排筛选按钮. 按一个按钮在下面展开它的选项, 选完收起.
 * 每个选项带着选了之后有几部, 没有片的不列.
 */
@Composable
fun BrowseScreen(initial: BrowseFilter, onOpen: (String) -> Unit, onOpenCollection: (String) -> Unit) {
    val app = ArisaApp.instance
    val library by app.library.library.collectAsStateWithLifecycle()
    val lib = library ?: return
    val browser = remember(lib) { LibraryBrowser(lib) }

    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(initial) }
    var open by rememberSaveable { mutableStateOf<Dimension?>(null) }
    // 选完选项后焦点回到对应的按钮
    var refocus by remember { mutableStateOf<Dimension?>(null) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }

    val cards = remember(browser, filter) { browser.cards(filter) }
    val total = remember(browser, filter) { browser.count(filter) }
    val buttons = remember { Dimension.entries.associateWith { FocusRequester() } }
    val optionFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    // 只读一次, 卡片里读 lastFocus 的话焦点每动一下所有卡片都要重组
    val restoreKey = remember { Snapshot.withoutReadObservation { lastFocus } }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (restoreKey == null || runCatching { restore.requestFocus() }.isFailure) {
            runCatching { if (cards.isNotEmpty()) firstCard.requestFocus() else buttons.getValue(Dimension.TYPE).requestFocus() }
        }
    }
    LaunchedEffect(open) { if (open != null) { withFrameNanos { }; runCatching { optionFocus.requestFocus() } } }
    LaunchedEffect(refocus) {
        val d = refocus ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { buttons.getValue(d).requestFocus() }
        refocus = null
    }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("片库", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text("$total 部", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dimension.entries.forEach { d ->
                val value = valueLabel(filter, d)
                Chip(
                    text = if (value == null) d.label else "${d.label} · $value",
                    highlighted = value != null || open == d,
                    onClick = { open = if (open == d) null else d },
                    modifier = Modifier.focusRequester(buttons.getValue(d)),
                )
            }
        }
        open?.let { d ->
            val options = remember(browser, filter, d) { options(browser, filter, d) }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 56.dp)) {
                itemsIndexed(options) { i, o ->
                    val focusHere = o.selected || (i == 0 && options.none { it.selected })
                    Chip(
                        text = o.label,
                        highlighted = o.selected,
                        onClick = {
                            filter = o.filter
                            open = null
                            refocus = d
                        },
                        modifier = if (focusHere) Modifier.focusRequester(optionFocus) else Modifier,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LaunchedEffect(filter) { gridState.scrollToItem(0) }
        if (cards.isEmpty()) {
            Text("没有符合条件的作品", style = MaterialTheme.typography.bodyMedium)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            state = gridState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(cards, key = { it.key }) { card ->
                val c = card as? LibraryBrowser.Card.Collection
                PosterCard(
                    item = card.item,
                    onClick = { if (c != null) onOpenCollection(c.collection.id) else onOpen(card.item.id) },
                    badge = c?.let { "合集 · ${it.members.size} 部" },
                    modifier = Modifier
                        .then(if (card == cards.first()) Modifier.focusRequester(firstCard) else Modifier)
                        .then(if (card.key == restoreKey) Modifier.focusRequester(restore) else Modifier)
                        .onFocusChanged { if (it.hasFocus) lastFocus = card.key },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (highlighted) Palette.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (highlighted) Palette.Accent else Palette.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun typeLabel(type: MediaType) = if (type == MediaType.MOVIE) "电影" else "剧集"

/** 按钮上显示的当前值, 不限时是 null. 排序默认「最近添加」也不显示. */
private fun valueLabel(f: BrowseFilter, d: Dimension): String? = when (d) {
    Dimension.TYPE -> f.type?.let(::typeLabel)
    Dimension.REGION -> f.region?.label
    Dimension.GENRE -> f.genre
    Dimension.DECADE -> f.decade?.let(BrowseFilter::decadeLabel)
    Dimension.SORT -> f.sort.takeIf { it != BrowseSort.ADDED }?.label
}

private fun options(browser: LibraryBrowser, f: BrowseFilter, d: Dimension): List<Option> {
    fun all(cleared: BrowseFilter) = Option("全部 ${browser.count(cleared)}", cleared, cleared == f)
    return when (d) {
        Dimension.TYPE -> listOf(all(f.copy(type = null))) +
            browser.types(f).map { (t, n) -> Option("${typeLabel(t)} $n", f.copy(type = t), f.type == t) }
        Dimension.REGION -> listOf(all(f.copy(region = null))) +
            browser.regions(f).map { (r, n) -> Option("${r.label} $n", f.copy(region = r), f.region == r) }
        Dimension.GENRE -> listOf(all(f.copy(genre = null))) +
            browser.genres(f).map { (g, n) -> Option("$g $n", f.copy(genre = g), f.genre == g) }
        Dimension.DECADE -> listOf(all(f.copy(decade = null))) +
            browser.decades(f).map { (y, n) -> Option("${BrowseFilter.decadeLabel(y)} $n", f.copy(decade = y), f.decade == y) }
        Dimension.SORT -> BrowseSort.entries.map { Option(it.label, f.copy(sort = it), f.sort == it) }
    }
}

/** 存成一个字符串, 片库页返回时保留筛选条件. */
private val FilterSaver = Saver<BrowseFilter, String>(
    save = { f -> listOf(f.type?.name, f.region?.name, f.genre, f.decade?.toString(), f.sort.name).joinToString("\u0001") { it ?: "" } },
    restore = { s ->
        val p = s.split('\u0001')
        BrowseFilter(
            type = p[0].takeIf(String::isNotEmpty)?.let(MediaType::valueOf),
            region = p[1].takeIf(String::isNotEmpty)?.let(Region::valueOf),
            genre = p[2].takeIf(String::isNotEmpty),
            decade = p[3].toIntOrNull(),
            sort = BrowseSort.valueOf(p[4]),
        )
    },
)
