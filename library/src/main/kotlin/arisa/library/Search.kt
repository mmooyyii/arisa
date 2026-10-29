package arisa.library

/**
 * 片库搜索. 片名、原名、别名、合集名、演员、导演、类型都能用汉字或拼音搜:
 * 全拼 (liulangdiqiu)、首字母 (lldq)、混着打 (liulangdq) 都行; 年份和未识别文件名按文字搜.
 * 拼音是刮削时写进 [Library.pinyin] 的, 这里只做匹配.
 */
class LibrarySearch(library: Library) {
    sealed interface Hit {
        val score: Int
        data class Item(val item: MediaItem, override val score: Int) : Hit
        data class Collection(val collection: MediaCollection, val cover: MediaItem, override val score: Int) : Hit
        data class File(val file: MediaFile, override val score: Int) : Hit
    }

    private class Field(val text: Text, val weight: Int)
    private class Entry(val hit: (Int) -> Hit, val fields: List<Field>, val year: Int?, val addedAt: Long)

    private val entries: List<Entry>

    init {
        fun text(raw: String) = Text(raw, library.pinyin[raw])
        val byId = library.items.associateBy { it.id }
        val items = library.items.map { item ->
            Entry(
                hit = { Hit.Item(item, it) },
                fields = buildList {
                    add(Field(text(item.title), TITLE))
                    item.originalTitle?.let { add(Field(text(it), ORIGINAL)) }
                    item.aliases.forEach { add(Field(text(it), ALIAS)) }
                    item.directors.forEach { add(Field(text(it), PERSON)) }
                    item.cast.forEach { add(Field(text(it.name), PERSON - 5)) }
                    item.genres.forEach { add(Field(text(it), GENRE)) }
                },
                year = item.year,
                addedAt = item.addedAt,
            )
        }
        val collections = library.collections.mapNotNull { c ->
            val members = c.items.mapNotNull(byId::get)
            val cover = byId[c.cover] ?: members.firstOrNull() ?: return@mapNotNull null
            Entry({ Hit.Collection(c, cover, it) }, listOf(Field(text(c.name), TITLE)), null, members.maxOf { it.addedAt })
        }
        val files = library.unmatched.map { f ->
            Entry({ Hit.File(f, it) }, listOf(Field(Text(f.name, null), FILE)), null, f.updatedAt)
        }
        entries = items + collections + files
    }

    /** 按相关度排, 一样的话新加入的在前. 空查询返回空. */
    fun search(query: String): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val year = q.toIntOrNull()?.takeIf { q.length == 4 }
        return entries.mapNotNull { e ->
            val score = maxOf(
                e.fields.maxOfOrNull { f -> f.text.match(q).let { if (it > 0) f.weight + it else 0 } } ?: 0,
                if (year != null && e.year == year) YEAR else 0,
            )
            if (score > 0) e to score else null
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.addedAt })
            .map { (e, score) -> e.hit(score) }
    }

    /** 一段可搜索的文字. [pinyin] 是 [Library.pinyin] 里它的读音, 对不上字数就当没有. */
    internal class Text(raw: String, pinyin: String?) {
        private val plain = normalize(raw)
        private val readings: List<List<String>>? =
            pinyin?.split(' ')?.map { it.split('|') }?.takeIf { it.size == plain.length }

        /** 0 是没命中; 越大越相关: 完全一样 > 开头 > 包含 > 拼音开头 > 拼音包含. */
        fun match(q: String): Int = when {
            plain == q -> 30
            plain.startsWith(q) -> 25
            plain.contains(q) -> 20
            readings != null && q.all { it in 'a'..'z' || it in '0'..'9' } -> pinyinMatch(q)
            else -> 0
        }

        private fun pinyinMatch(q: String): Int {
            val r = readings!!
            // memo[i][p]: 从第 i 个字、查询第 p 个字母开始能不能匹配完; 0 未算 1 能 2 不能
            val memo = Array(r.size + 1) { ByteArray(q.length + 1) }
            fun from(i: Int, p: Int): Boolean {
                if (p == q.length) return true
                if (i == r.size) return false
                if (memo[i][p] != 0.toByte()) return memo[i][p] == 1.toByte()
                // 每个字吃掉它某个读法的前几个字母: 首字母 (l)、声母 (zh)、全拼 (liu) 都行
                val ok = r[i].any { s ->
                    (1..minOf(s.length, q.length - p)).any { k -> q.regionMatches(p, s, 0, k) && from(i + 1, p + k) }
                }
                memo[i][p] = if (ok) 1 else 2
                return ok
            }
            return when {
                from(0, 0) -> 15
                (1 until r.size).any { from(it, 0) } -> 10
                else -> 0
            }
        }
    }

    companion object {
        private const val TITLE = 100
        private const val ORIGINAL = 90
        private const val ALIAS = 85
        private const val PERSON = 60
        private const val GENRE = 50
        private const val YEAR = 70
        private const val FILE = 40

        fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

        /** 要生成拼音的名字: 能搜的名字里含汉字的. 刮削器按这个写 [Library.pinyin]. */
        fun names(library: Library): Set<String> = buildSet {
            for (item in library.items) {
                add(item.title)
                item.originalTitle?.let(::add)
                addAll(item.aliases)
                addAll(item.directors)
                item.cast.mapTo(this) { it.name }
                addAll(item.genres)
            }
            library.collections.mapTo(this) { it.name }
        }.filter { name -> name.any(::isHan) }.toSet()

        /** 小写, 去掉空格和标点 (中英文都算), 「蜘蛛侠：英雄归来」和「蜘蛛侠 英雄归来」一样. */
        fun normalize(s: String) = buildString {
            for (c in s.lowercase()) if (Character.isLetterOrDigit(c)) append(c)
        }
    }
}
