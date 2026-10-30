package arisa.library

/** 地区. 按原始语言分 (英语片不管哪国拍的都算欧美), 没有语言时看出品国家. */
enum class Region(val label: String) {
    CHINESE("华语"), WESTERN("欧美"), JAPAN("日本"), KOREA("韩国"), OTHER("其他");

    companion object {
        private val WESTERN_LANGUAGES = setOf(
            "en", "fr", "de", "es", "it", "pt", "ru", "nl", "sv", "da", "no", "nb", "fi", "is", "pl", "cs", "el", "hu", "ro", "uk", "ga",
        )
        private val WESTERN_COUNTRIES = setOf(
            "US", "GB", "CA", "AU", "NZ", "IE", "FR", "DE", "ES", "IT", "PT", "RU", "NL", "BE", "SE", "DK", "NO", "FI", "IS",
            "PL", "CZ", "GR", "HU", "RO", "UA", "AT", "CH",
        )

        /** 不知道的返回 null, 只在「全部」里出现. */
        fun of(item: MediaItem): Region? {
            item.language?.let { lang ->
                return when (lang) {
                    // TMDB 的粤语是 "cn"
                    "zh", "cn" -> CHINESE
                    "ja" -> JAPAN
                    "ko" -> KOREA
                    in WESTERN_LANGUAGES -> WESTERN
                    else -> OTHER
                }
            }
            val c = item.countries.firstOrNull() ?: return null
            return when (c) {
                "CN", "HK", "TW", "MO" -> CHINESE
                "JP" -> JAPAN
                "KR" -> KOREA
                in WESTERN_COUNTRIES -> WESTERN
                else -> OTHER
            }
        }
    }
}

enum class BrowseSort(val label: String) { ADDED("最近添加"), RATING("评分"), YEAR("年份"), TITLE("片名") }

/** 片库筛选条件, null 是不限. [decade] 是年代的第一年 (2010), [EARLIER] 表示 1990 年以前. */
data class BrowseFilter(
    val type: MediaType? = null,
    val region: Region? = null,
    val genre: String? = null,
    val decade: Int? = null,
    val sort: BrowseSort = BrowseSort.ADDED,
) {
    companion object {
        const val EARLIER = 0

        fun decadeOf(year: Int) = if (year >= 1990) year / 10 * 10 else EARLIER
        fun decadeLabel(decade: Int) = if (decade == EARLIER) "更早" else "${decade}年代"
    }
}

/**
 * 按条件筛选和排序片库, 首页的分类行和片库页共用.
 * 同一合集里有两部以上符合条件时合成一张合集卡片, 只有一部时单独显示那一部.
 */
class LibraryBrowser(private val library: Library) {
    sealed interface Card {
        val key: String
        /** 卡片上显示的作品 (合集用封面那部). */
        val item: MediaItem

        data class Single(override val item: MediaItem) : Card {
            override val key get() = item.id
        }

        data class Collection(val collection: MediaCollection, override val item: MediaItem, val members: List<MediaItem>) : Card {
            override val key get() = collection.id
        }
    }

    private val collectionOf: Map<String, MediaCollection> =
        library.collections.flatMap { c -> c.items.map { it to c } }.toMap()

    /** 片名排序用拼音, 没有拼音的按原文. */
    private fun titleKey(name: String): String =
        library.pinyin[name]?.split(' ')?.joinToString("") { it.substringBefore('|') } ?: LibrarySearch.normalize(name)

    fun matches(item: MediaItem, f: BrowseFilter): Boolean =
        (f.type == null || item.type == f.type) &&
            (f.region == null || Region.of(item) == f.region) &&
            (f.genre == null || f.genre in item.genres) &&
            (f.decade == null || item.year?.let(BrowseFilter::decadeOf) == f.decade)

    /** 符合条件的作品数 (合集按里面的作品算). */
    fun count(f: BrowseFilter) = library.items.count { matches(it, f) }

    fun cards(f: BrowseFilter): List<Card> {
        val matched = library.items.filter { matches(it, f) }
        val byCollection = matched.groupBy { collectionOf[it.id] }
        val cards = matched.mapNotNull { item ->
            val c = collectionOf[item.id]
            val members = byCollection[c].orEmpty()
            when {
                c == null || members.size < 2 -> Card.Single(item)
                // 合集卡片放在第一部出现的位置, 其余成员跳过
                members.first() != item -> null
                else -> {
                    val cover = members.firstOrNull { it.id == c.cover } ?: members.first()
                    Card.Collection(c, cover.copy(title = c.name, addedAt = members.maxOf { it.addedAt }), members)
                }
            }
        }
        fun members(card: Card) = if (card is Card.Collection) card.members else listOf(card.item)
        return when (f.sort) {
            BrowseSort.ADDED -> cards.sortedByDescending { card -> members(card).maxOf { it.addedAt } }
            BrowseSort.RATING -> cards.sortedByDescending { card -> members(card).maxOf { it.rating ?: 0.0 } }
            BrowseSort.YEAR -> cards.sortedByDescending { card -> members(card).maxOf { it.year ?: 0 } }
            BrowseSort.TITLE -> cards.sortedBy { titleKey(if (it is Card.Collection) it.collection.name else it.item.title) }
        }
    }

    /** 某一项可选的值和选了之后的作品数, 没有作品的不列. 其他几项保持 [f] 的条件. */
    fun types(f: BrowseFilter) = options(MediaType.entries) { f.copy(type = it) }
    fun regions(f: BrowseFilter) = options(Region.entries) { f.copy(region = it) }
    fun genres(f: BrowseFilter) =
        options(library.items.flatMap { it.genres }.distinct()) { f.copy(genre = it) }.sortedByDescending { it.second }
    fun decades(f: BrowseFilter) =
        options(library.items.mapNotNull { it.year?.let(BrowseFilter::decadeOf) }.distinct().sortedDescending()) { f.copy(decade = it) }

    private fun <T> options(values: List<T>, with: (T) -> BrowseFilter): List<Pair<T, Int>> =
        values.map { it to count(with(it)) }.filter { it.second > 0 }
}
