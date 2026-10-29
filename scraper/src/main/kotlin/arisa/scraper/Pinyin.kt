package arisa.scraper

import arisa.library.Library
import arisa.library.LibrarySearch
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/** 生成索引里搜索用的拼音 ([Library.pinyin]), TV 上只做匹配. */
object Pinyin {
    private val format = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
        caseType = HanyuPinyinCaseType.LOWERCASE
    }

    /** 给片库里所有含汉字的名字配上拼音. */
    fun annotate(library: Library): Library =
        library.copy(pinyin = LibrarySearch.names(library).associateWith(::encode).toSortedMap())

    /** "长安3" → "chang|zhang an 3": 去掉空格标点后逐字, 多音字列出所有读音, 没拼音的字原样. */
    fun encode(name: String): String = LibrarySearch.normalize(name).map { c ->
        PinyinHelper.toHanyuPinyinStringArray(c, format)?.distinct()?.joinToString("|")?.ifEmpty { null } ?: c.toString()
    }.joinToString(" ")
}
