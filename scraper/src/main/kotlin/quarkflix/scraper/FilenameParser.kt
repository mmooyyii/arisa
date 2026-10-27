package quarkflix.scraper

/** 规则解析出的结果, [titles] 按可信度排序 (中文名、英文名…). */
data class ParsedName(
    val titles: List<String>,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = episode != null
}

/**
 * 基于规则的文件名解析. 解析不出片名或拿不准时交给 AI.
 */
object FilenameParser {
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "ts", "m2ts", "mts", "mov", "wmv", "flv", "rmvb", "rm", "webm", "m4v", "iso", "mpg", "mpeg",
    )

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /** 季目录, 如 "Season 01" "S2" "第一季". */
    fun seasonOfDir(name: String): Int? {
        SEASON_DIR.matchEntire(name.trim())?.let { m ->
            return m.groupValues[1].ifEmpty { m.groupValues[2] }.let(::parseNumber)
        }
        return null
    }

    /**
     * @param fileName 文件名 (含扩展名)
     * @param parents 从片库根往下的目录名, 最后一个是直接父目录
     */
    fun parse(fileName: String, parents: List<String> = emptyList()): ParsedName {
        val own = parseText(fileName.substringBeforeLast('.'))
        // 文件名里没有片名 (如 "E05.mkv" "01.mkv"), 往上找目录名
        val dirs = parents.asReversed().filter { seasonOfDir(it) == null }
        val dirParsed = dirs.firstOrNull()?.let { parseText(it) }
        val seasonFromDir = parents.asReversed().firstNotNullOfOrNull { seasonOfDir(it) }

        val titles = when {
            own.titles.isNotEmpty() -> own.titles
            dirParsed != null -> dirParsed.titles
            else -> emptyList()
        }
        val episode = own.episode
        val season = own.season ?: seasonFromDir ?: dirParsed?.season ?: if (episode != null) 1 else null
        return ParsedName(
            titles = titles,
            year = own.year ?: dirParsed?.year,
            season = season,
            episode = episode,
        )
    }

    /** 解析一段文本 (文件名去扩展名, 或目录名). */
    fun parseText(raw: String): ParsedName {
        var text = raw
        var season: Int? = null
        var episode: Int? = null
        var year: Int? = null

        // 1. 括号分块: [桜都字幕组][葬送的芙莉莲][12][1080P]
        val titleParts = mutableListOf<String>()
        val bracketTitles = mutableListOf<String>()
        val chunks = splitBrackets(text)
        for ((chunk, inBracket) in chunks) {
            val c = chunk.trim()
            if (c.isEmpty()) continue
            if (inBracket) {
                when {
                    YEAR_ONLY.matches(c) -> year = year ?: c.toInt()
                    EPISODE_ONLY.matches(c) && episode == null -> episode = EPISODE_ONLY.matchEntire(c)!!.groupValues[1].toInt()
                    isTag(c) || isGroup(c) || isAd(c) || CRC.matches(c) -> {}
                    else -> bracketTitles += c
                }
            } else {
                titleParts += c
            }
        }
        // 括号外有正文时括号里多半是标签; 全是括号 (字幕组风格) 时括号里的文字才是片名
        if (titleParts.none { part -> part.any { it.isLetter() } }) titleParts += bracketTitles
        text = titleParts.joinToString(" ")

        // 整段只有数字: "01.mkv"
        BARE_NUMBER.matchEntire(text.trim())?.let { m ->
            return ParsedName(emptyList(), null, null, m.groupValues[1].toInt())
        }

        // 2. 季/集
        SXXEXX.find(text)?.let { m ->
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            text = text.substring(0, m.range.first)
        }
        if (episode == null) {
            X_FORMAT.find(text)?.let { m ->
                season = m.groupValues[1].toInt()
                episode = m.groupValues[2].toInt()
                text = text.substring(0, m.range.first)
            }
        }
        CN_SEASON.find(text)?.let { m ->
            season = season ?: parseNumber(m.groupValues[1])
            text = text.removeRange(m.range)
        }
        if (episode == null) {
            CN_EPISODE.find(text)?.let { m ->
                episode = parseNumber(m.groupValues[1])
                text = text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1)
            }
        }
        if (episode == null) {
            EP_FORMAT.find(text)?.let { m ->
                episode = m.groupValues[1].toInt()
                text = text.substring(0, m.range.first)
            }
        }
        SEASON_WORD.find(text)?.let { m ->
            season = season ?: m.groupValues[1].toInt()
            text = text.removeRange(m.range)
        }

        // 3. 点和下划线当空格, 去掉技术标签
        text = text.replace('.', ' ').replace('_', ' ')
        val words = text.split(WS).filter { it.isNotBlank() }.toMutableList()
        val cut = words.indexOfFirst { isTag(it) }
        if (cut >= 0) words.subList(cut, words.size).clear()

        // 年份: 取最后一个像年份的词, 并截断 (年份之后一般都是标签)
        val yearIdx = words.indexOfLast { YEAR_WORD.matches(it) }
        if (yearIdx > 0) {
            year = year ?: YEAR_WORD.matchEntire(words[yearIdx])!!.groupValues[1].toInt()
            words.subList(yearIdx, words.size).clear()
        }

        // 动画 " - 12" 结尾
        if (episode == null && words.size >= 2) {
            val last = words.last()
            val prev = words[words.size - 2]
            if (prev == "-" && last.matches(Regex("\\d{1,4}(v\\d)?"))) {
                episode = last.takeWhile { it.isDigit() }.toInt()
                words.removeAt(words.size - 1)
                words.removeAt(words.size - 1)
            }
        }

        val title = words.joinToString(" ").trim(' ', '-', '–', '·', ',', '。')
        return ParsedName(splitTitles(title), year, season, episode)
    }

    /** "流浪地球2 The Wandering Earth II" → ["流浪地球2", "The Wandering Earth II"] */
    internal fun splitTitles(title: String): List<String> {
        if (title.isBlank()) return emptyList()
        if (!title.any { isCjk(it) }) return listOf(title)
        val m = CJK_THEN_LATIN.matchEntire(title)
        if (m != null) {
            val cn = m.groupValues[1].trim()
            val en = m.groupValues[2].trim()
            return listOf(cn, en).filter { it.isNotEmpty() }
        }
        return listOf(title)
    }

    private fun splitBrackets(text: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val buf = StringBuilder()
        var depth = 0
        for (ch in text) {
            when {
                ch in OPEN -> {
                    if (depth == 0 && buf.isNotEmpty()) { out += buf.toString() to false; buf.clear() }
                    if (depth > 0) buf.append(ch)
                    depth++
                }
                ch in CLOSE && depth > 0 -> {
                    depth--
                    if (depth == 0) { out += buf.toString() to true; buf.clear() } else buf.append(ch)
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString() to (depth > 0)
        return out
    }

    private fun isTag(word: String): Boolean {
        val w = word.lowercase().trim()
        if (w in TAGS) return true
        return TAG_PATTERNS.any { it.matches(w) } || word.split(Regex("[ ._&+]+")).let { parts ->
            parts.size > 1 && parts.all { p -> p.isEmpty() || p.lowercase() in TAGS || TAG_PATTERNS.any { it.matches(p.lowercase()) } }
        }
    }

    private fun isGroup(s: String) = GROUP_HINTS.any { s.contains(it, ignoreCase = true) }

    private fun isAd(s: String) = AD_HINTS.any { s.contains(it, ignoreCase = true) }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )

    /** 阿拉伯数字或中文数字 (到 99). */
    fun parseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s.isEmpty()) return null
        if ('十' !in s) return if (s.length == 1) digits[s[0]] else null
        val (tens, ones) = s.split('十', limit = 2)
        val t = if (tens.isEmpty()) 1 else digits[tens.single()] ?: return null
        val o = if (ones.isEmpty()) 0 else digits[ones.single()] ?: return null
        return t * 10 + o
    }

    private const val OPEN = "[【(（《"
    private const val CLOSE = "]】)）》"
    private val WS = Regex("\\s+")
    private val CRC = Regex("[0-9A-Fa-f]{8}")
    private val BARE_NUMBER = Regex("(?:EP?)?(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val YEAR_ONLY = Regex("(19\\d{2}|20\\d{2})")
    private val YEAR_WORD = Regex("\\(?((?:19|20)\\d{2})\\)?")
    private val EPISODE_ONLY = Regex("(?:EP?|第)?(\\d{1,4})(?:v\\d|集|话|話)?", RegexOption.IGNORE_CASE)
    private val SXXEXX = Regex("[Ss](\\d{1,2})[ ._-]?[Ee][Pp]?(\\d{1,4})")
    private val X_FORMAT = Regex("(?<![\\dxX])(\\d{1,2})[xX](\\d{2,3})(?!\\d)")
    private val EP_FORMAT = Regex("(?<![A-Za-z])(?:EP|E)[ ._-]?(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val CN_EPISODE = Regex("第\\s*([0-9一二两三四五六七八九十零]+)\\s*[集话話期]")
    private val CN_SEASON = Regex("第\\s*([0-9一二两三四五六七八九十]+)\\s*季")
    private val SEASON_WORD = Regex("(?<![A-Za-z])(?:Season[ ._-]?|S)(\\d{1,2})(?!\\d)(?![EeXx])", RegexOption.IGNORE_CASE)
    private val SEASON_DIR = Regex("(?:Season[ ._-]?|S)(\\d{1,2})|第\\s*([0-9一二两三四五六七八九十]+)\\s*季", RegexOption.IGNORE_CASE)
    private val CJK_THEN_LATIN = Regex("([^A-Za-z]*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}][^A-Za-z]*?)\\s*([A-Za-z].*)")

    private val TAGS = setOf(
        "2160p", "1080p", "1080i", "720p", "480p", "4k", "8k", "uhd", "fhd", "hd", "sd",
        "hdr", "hdr10", "hdr10+", "hdr10plus", "dv", "dovi", "sdr", "hlg", "imax",
        "web-dl", "webdl", "webrip", "web", "bluray", "blu-ray", "bdrip", "brrip", "bd", "remux", "hdtv", "dvdrip", "hdrip",
        "x264", "x265", "h264", "h265", "h.264", "h.265", "hevc", "avc", "av1", "vp9", "10bit", "8bit", "hi10p",
        "aac", "ac3", "dts", "dts-hd", "truehd", "atmos", "ddp", "dd", "flac", "eac3", "ma", "lpcm", "opus",
        "nf", "amzn", "dsnp", "hmax", "atvp", "itunes", "netflix",
        "proper", "repack", "extended", "uncut", "remastered", "complete",
        "国语", "粤语", "国粤", "中字", "中英", "双语", "简繁", "简体", "繁体", "内封", "外挂", "内嵌", "特效",
        "国英", "国英双语", "中英双字", "简繁内封", "简日双语", "高清", "超清", "蓝光", "无损", "全集", "完结",
    )
    private val TAG_PATTERNS = listOf(
        Regex("\\d{3,4}[pi]"),
        Regex("(dd|ddp|dts|aac|ac3|eac3|truehd)[0-9.+]*"),
        Regex("\\d\\.\\d"),
        Regex("\\d+(\\.\\d+)?(gb|mb)"),
        Regex("(x|h)\\.?26[45]"),
        Regex("\\d+fps"),
        Regex("\\d+audios?"),
        Regex("(chs|cht|chi|big5|gb|jpn?|eng)([&_-](chs|cht|chi|big5|gb|jpn?|eng))*"),
    )
    private val GROUP_HINTS = listOf("字幕组", "字幕社", "字幕組", "raws", "-raws", "sub", "压制", "壓制", "发布", "fansub")
    private val AD_HINTS = listOf("下载", "下載", "www.", ".com", ".cn", "电影天堂", "阳光电影", "首发", "高清")
}
