package arisa.scraper

import arisa.library.SubtitleFile
import arisa.quark.QuarkFile

/** 找视频旁边的外挂字幕, 从文件名猜语言. */
object Subtitles {
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    fun isSubtitle(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * 同目录下文件名以视频名 (去掉扩展名) 开头的字幕; 目录里只有这一个视频时, 所有字幕都算它的.
     */
    fun of(video: QuarkFile, siblings: List<QuarkFile>): List<SubtitleFile> {
        val subs = siblings.filter { !it.isDir && isSubtitle(it.name) }
        if (subs.isEmpty()) return emptyList()
        val base = video.name.substringBeforeLast('.')
        val onlyVideo = siblings.count { !it.isDir && FilenameParser.isVideo(it.name) } <= 1
        return subs.filter { onlyVideo || it.name.startsWith(base, ignoreCase = true) }
            .map { SubtitleFile(it.fid, it.name, language(it.name.removePrefix(base))) }
            .sortedBy { it.name }
    }

    /** 按文件名里的标记猜语言, 简繁双语算简体. */
    fun language(name: String): String? {
        val n = name.lowercase().substringBeforeLast('.')
        val tokens = n.split(Regex("[.\\[\\]()_\\s-]+")).filter { it.isNotEmpty() }.toSet()
        fun has(vararg words: String) = words.any { it in tokens || (it.any { c -> c.code > 0x2e80 } && it in n) }
        return when {
            has("chs", "sc", "gb", "hans", "zh-hans", "zh-cn", "简体", "简中", "简日", "简英", "简繁", "chs&cht", "chs_cht") -> "zh-Hans"
            has("cht", "tc", "big5", "hant", "zh-hant", "zh-tw", "zh-hk", "繁体", "繁體", "繁中", "繁日") -> "zh-Hant"
            has("zh", "chi", "zho", "chn", "chinese", "中文", "中字") -> "zh"
            has("en", "eng", "english", "英文") -> "en"
            has("ja", "jp", "jpn", "japanese", "日文", "日语") -> "ja"
            has("ko", "kor", "korean", "韩文") -> "ko"
            else -> null
        }
    }
}
