package arisa.scraper

import arisa.library.Artwork
import arisa.library.CastMember
import arisa.library.Episode
import arisa.library.Library
import arisa.library.LibraryCodec
import arisa.library.MatchInfo
import arisa.library.MediaCollection
import arisa.library.MediaFile
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.Season
import arisa.quark.QuarkFile
import kotlinx.serialization.Serializable

/** 扫描到的一个视频文件. */
data class ScannedFile(
    val file: QuarkFile,
    /** 网盘完整路径. */
    val path: String,
    val library: LibraryConfig,
    /** 从片库根往下的目录, 不含片库根本身. */
    val dirs: List<QuarkFile>,
    /** 同目录的所有文件, 用来找 NFO. */
    val siblings: List<QuarkFile>,
) {
    val parentNames get() = dirs.map { it.name }
    val mediaFile get() = MediaFile(file.fid, file.name, path, file.size, file.updatedAt, file.duration, Subtitles.of(file, siblings))
}

/** 一次扫描的结果. */
class Scan(val files: List<ScannedFile>, val libraryRoots: Map<String, String>)

/** 规则猜出来的一部作品: 一部电影的一个文件, 或者一个剧集目录下的所有文件. */
private class Work(
    val kind: MediaType,
    val files: List<ScannedFile>,
    /** 剧集的根目录, 电影为 null. */
    val showDir: QuarkFile?,
    val parsed: ParsedName,
) {
    val first get() = files.first()
    /** 做映射时用的 key: 剧集用目录, 电影用文件. */
    val key get() = showDir?.fid ?: first.file.fid
    val displayPath get() = showDir?.let { first.path.substringBefore("/${it.name}/") + "/${it.name}" } ?: first.path
}

/** 待识别的作品, 给 skill 看的. */
@Serializable
data class PendingWork(
    /** 用这个 fid 做 decide; 也可以用 dirs 里任意一级目录的 fid. */
    val key: String,
    /** 规则猜的类型: movie / tv */
    val guess: String,
    val path: String,
    val fileCount: Int,
    val samples: List<String>,
    /** 每个示例文件的时长 (分钟), 和 samples 一一对应, 0 表示不知道. 电影通常 80 分钟以上, 剧集单集一般不到 60 分钟. */
    val minutes: List<Int> = emptyList(),
    /** 从片库往下的各级目录 (名字和 fid), 想把整个目录映射过去时用. */
    val dirs: List<PendingDir>,
    val rules: PendingParse,
    /** 视频旁已有 NFO 里的 ID. */
    val nfo: String? = null,
    /** 规则在 TMDB 上搜到的候选, 按得分排序. */
    val candidates: List<PendingCandidate> = emptyList(),
    /** 其中一个视频的 fid, 看缩略图用. */
    val sampleFid: String? = null,
)

@Serializable
data class PendingDir(val fid: String, val name: String)

@Serializable
data class PendingParse(val titles: List<String>, val year: Int? = null, val season: Int? = null, val episode: Int? = null)

@Serializable
data class PendingCandidate(
    val target: String,
    val title: String,
    val original: String? = null,
    val year: Int? = null,
    val score: Double,
    val overview: String? = null,
)

data class BuildReport(
    val items: Int,
    /** 没有映射的视频 (待识别). */
    val pending: Int,
    val nfoWritten: Int,
    /** 索引没变化时不上传, 为 null. */
    val indexFile: String?,
    val renames: List<RenamePlan>,
)

/**
 * 改名建议: 把作品目录改成 "片名 (年份)". [skipped] 不为 null 时只是说明为什么不改.
 * 落到计划文件里, 确认后按 fid 执行, 不会改到计划之外的东西.
 */
data class RenamePlan(
    val fid: String,
    val parentFid: String,
    val parentPath: String,
    val from: String,
    val to: String,
    val tmdb: String,
    val skipped: String? = null,
) {
    fun toLine() = listOf(fid, parentFid, parentPath, from, to, tmdb, skipped ?: "").joinToString("\t")

    companion object {
        fun parse(line: String): RenamePlan? {
            val p = line.split('\t')
            if (p.size < 7 || line.startsWith("#")) return null
            return RenamePlan(p[0], p[1], p[2], p[3], p[4], p[5], p[6].ifEmpty { null })
        }
    }
}

/**
 * 刮削器的确定性部分: 扫描网盘、按映射表生成索引/NFO、改名.
 * "这是哪部片" 的判断交给 skill (Claude Code), 结论写进映射表.
 */
class Scraper(
    private val config: Config,
    private val drive: Drive,
    private val tmdb: TmdbApi?,
    private val images: Images?,
    private val bangumi: BangumiApi? = null,
    private val log: (String) -> Unit = ::println,
) {
    // ---------- 扫描 ----------

    fun scan(): Scan {
        val files = mutableListOf<ScannedFile>()
        val roots = mutableMapOf<String, String>()
        for (lib in config.libraries) {
            val root = drive.resolve(lib.path)
            if (root == null || !root.isDir) {
                log("片库目录不存在: ${lib.path}")
                continue
            }
            roots[lib.path] = root.fid
            val before = files.size
            walk(lib, root, lib.path.trimEnd('/'), emptyList(), files)
            log("${lib.path}: ${files.size - before} 个视频")
        }
        return Scan(files, roots)
    }

    private fun walk(lib: LibraryConfig, dir: QuarkFile, path: String, dirs: List<QuarkFile>, out: MutableList<ScannedFile>) {
        val children = drive.list(dir.fid)
        for (c in children) {
            if (c.isDir) {
                if (c.name.startsWith(".")) continue
                walk(lib, c, "$path/${c.name}", dirs + c, out)
            } else if (FilenameParser.isVideo(c.name) && !FilenameParser.isExtra(c.name, dirs.map { it.name })) {
                out += ScannedFile(c, "$path/${c.name}", lib, dirs, children)
            }
        }
    }

    // ---------- 待识别 ----------

    /**
     * 没有映射的视频, 按规则分成作品. [withCandidates] 时顺带查 TMDB 给出候选.
     * [only] 不为 null 时只返回这些 key 的作品.
     */
    fun pending(scan: Scan, mapping: Mapping, withCandidates: Boolean, only: Set<String>? = null): List<PendingWork> {
        val unresolved = scan.files.filter { mapping.resolve(it) == null }
        val works = unresolved.groupBy { it.library }.flatMap { (lib, files) -> group(lib, files) }
        return works.filter { only == null || it.key in only }.map { w ->
            val candidates = if (withCandidates && tmdb != null && w.parsed.titles.isNotEmpty()) {
                runCatching { candidates(w.kind, w.parsed, config.defaults.language) }
                    .onFailure { if (it is TmdbAuthException) throw it }
                    .getOrDefault(emptyList())
            } else emptyList()
            val samples = w.files
                .map { it.path.removePrefix(w.displayPath).trimStart('/').ifEmpty { it.file.name } to (it.file.duration + 30) / 60 }
                .distinctBy { it.first }.take(8)
            PendingWork(
                key = w.key,
                guess = if (w.kind == MediaType.MOVIE) "movie" else "tv",
                path = w.displayPath,
                fileCount = w.files.size,
                samples = samples.map { it.first },
                minutes = samples.map { it.second },
                dirs = w.first.dirs.map { PendingDir(it.fid, it.name) },
                rules = PendingParse(w.parsed.titles, w.parsed.year, w.parsed.season, w.parsed.episode),
                nfo = nfoTarget(w),
                candidates = candidates,
                sampleFid = w.first.file.fid,
            )
        }
    }

    /**
     * 只自动接受确定的: 视频旁已有 NFO 的, 以及 TMDB 上只有一个候选、片名年份完全对上的.
     * 其余留给 skill. 返回新增的映射数.
     */
    fun autoDecide(scan: Scan, store: MappingStore): Int {
        val mapping = store.load()
        val added = mutableMapOf<String, MappingEntry>()
        val unresolved = scan.files.filter { mapping.resolve(it) == null }
        for ((lib, files) in unresolved.groupBy { it.library }) {
            for (w in group(lib, files)) {
                val eff = config.effective(lib)
                val nfo = if (eff.existingNfo == ExistingNfo.TRUST || eff.existingNfo == ExistingNfo.MERGE) nfoTarget(w) else null
                if (nfo != null) {
                    added[w.key] = MappingEntry(nfo, w.displayPath, by = "nfo")
                    continue
                }
                if (tmdb == null || w.parsed.titles.isEmpty()) continue
                val c = runCatching { candidates(w.kind, w.parsed, eff.language) }
                    .onFailure { if (it is TmdbAuthException) throw it }
                    .getOrDefault(emptyList())
                if (c.size == 1 && c[0].score >= eff.confidenceThreshold.coerceAtLeast(0.97)) {
                    added[w.key] = MappingEntry(c[0].target, w.displayPath, by = "rules")
                }
            }
        }
        if (added.isEmpty()) return 0
        var count = 0
        // 查 TMDB 期间别人可能已经改过映射表, 只补上还没有的
        store.update { m ->
            added.filterKeys { it !in m.entries }.also { count = it.size }
                .entries.fold(m) { acc, (fid, e) -> acc.with(fid, e) }
        }
        return count
    }

    private fun group(lib: LibraryConfig, files: List<ScannedFile>): List<Work> {
        val works = mutableListOf<Work>()
        val tvGroups = LinkedHashMap<String, MutableList<Pair<ScannedFile, ParsedName>>>()
        val showDirs = mutableMapOf<String, QuarkFile?>()

        for (f in files) {
            val p = FilenameParser.parse(f.file.name, f.parentNames)
            val isTv = when (lib.type) {
                LibraryType.TV -> true
                LibraryType.MOVIE -> false
                LibraryType.AUTO -> p.isEpisode
            }
            if (!isTv) {
                works += Work(MediaType.MOVIE, listOf(f), null, p)
                continue
            }
            // 剧集根目录: tv 片库取第一层目录; auto 取最近的非季、非画质/版本目录
            val showDir = when (lib.type) {
                LibraryType.TV -> f.dirs.firstOrNull()
                else -> f.dirs.lastOrNull { FilenameParser.seasonOfDir(it.name) == null && !FilenameParser.isNoiseDir(it.name) }
            }
            val key = showDir?.fid ?: "title:${p.titles.firstOrNull()?.lowercase() ?: f.file.fid}"
            tvGroups.getOrPut(key) { mutableListOf() } += f to p
            showDirs.putIfAbsent(key, showDir)
        }
        for ((key, list) in tvGroups) {
            val showDir = showDirs[key]
            // 剧名以目录名为准, 没有目录时用文件名里最常见的片名
            val dirParsed = showDir?.let { FilenameParser.parseText(it.name) }
            val titles = dirParsed?.titles?.takeIf { it.isNotEmpty() }
                ?: list.groupingBy { it.second.titles }.eachCount().maxByOrNull { it.value }?.key
                ?: emptyList()
            val year = dirParsed?.year ?: list.firstNotNullOfOrNull { it.second.year }
            works += Work(MediaType.TV, list.map { it.first }, showDir, ParsedName(titles, year))
        }
        return works
    }

    /** 视频旁已有的 NFO 里记录的作品, 如 "movie:438631". */
    private fun nfoTarget(w: Work): String? {
        val nfoFile = when (w.kind) {
            MediaType.TV -> {
                val dir = w.showDir ?: return null
                val siblings = if (w.first.dirs.lastOrNull()?.fid == dir.fid) w.first.siblings else drive.list(dir.fid)
                siblings.firstOrNull { it.name.equals("tvshow.nfo", true) }
            }
            MediaType.MOVIE -> {
                val base = w.first.file.name.substringBeforeLast('.')
                w.first.siblings.firstOrNull { it.name.equals("$base.nfo", true) }
                    ?: w.first.siblings.firstOrNull { it.name.equals("movie.nfo", true) }
            }
        } ?: return null
        val ids = runCatching { Nfo.read(drive.download(nfoFile.fid).decodeToString()) }.getOrNull() ?: return null
        val kind = if (ids.root == "movie") "movie" else "tv"
        ids.tmdbId?.let { return "$kind:$it" }
        val imdb = ids.imdbId ?: return null
        val found = runCatching { tmdb?.findByImdb(imdb) }.getOrNull() ?: return null
        return "${if (found.first == Tmdb.MediaKind.TV) "tv" else "movie"}:${found.second}"
    }

    /** 用规则解析出的片名搜 TMDB, 打分排序. */
    private fun candidates(kind: MediaType, parsed: ParsedName, language: String): List<PendingCandidate> {
        val tmdb = tmdb ?: return emptyList()
        val seen = LinkedHashMap<Int, Tmdb.SearchResult>()
        for (title in parsed.titles) {
            val years = if (parsed.year != null) listOf(parsed.year, null) else listOf(null)
            for (year in years) {
                val results = when (kind) {
                    MediaType.MOVIE -> tmdb.searchMovie(title, year, language)
                    MediaType.TV -> tmdb.searchTv(title, year, language)
                }
                results.forEach { seen.putIfAbsent(it.id, it) }
                if (results.isNotEmpty()) break
            }
        }
        val queries = parsed.titles.map(::normalize).toSet()
        val year = parsed.year
        val prefix = if (kind == MediaType.MOVIE) "movie" else "tv"
        return seen.values.map { c ->
            val cYear = c.year
            val exact = listOfNotNull(c.displayTitle, c.displayOriginal).map(::normalize).any { it in queries }
            val score = when {
                exact && year != null && cYear == year -> 0.97
                exact && year != null && cYear != null && kotlin.math.abs(cYear - year) <= 1 -> 0.9
                exact && (year == null || cYear == null) -> 0.8
                exact -> 0.5
                year == null || cYear == null || cYear == year -> 0.4
                else -> 0.2
            }
            PendingCandidate("$prefix:${c.id}", c.displayTitle, c.displayOriginal?.takeIf { it != c.displayTitle }, cYear, score, c.overview?.take(80))
        }.sortedByDescending { it.score }.take(6)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·：:，,。！!？?'’\"“”]"), "")

    // ---------- 生成索引 ----------

    /**
     * 按映射表生成索引: 每个文件找到它的映射, 同一作品的文件合并成一个条目.
     * TMDB 和图片都有磁盘缓存, 只有新作品才真正发请求; 索引内容没变就不上传.
     */
    fun build(scan: Scan, mapping: Mapping, write: Boolean = true): Pair<Library, BuildReport> {
        val tmdb = tmdb ?: error("生成索引需要 TMDB")
        val byTarget = LinkedHashMap<String, MutableList<Pair<ScannedFile, MappingEntry>>>()
        val unmatched = mutableListOf<MediaFile>()
        for (f in scan.files) {
            val (_, entry) = mapping.resolve(f) ?: run { unmatched += f.mediaFile; null } ?: continue
            if (entry.isSkip) continue
            byTarget.getOrPut(entry.target) { mutableListOf() } += f to entry
        }

        val items = mutableListOf<MediaItem>()
        val filesOf = HashMap<String, List<ScannedFile>>()
        for ((target, list) in byTarget) {
            val (kind, id) = list.first().second.tmdb()!!
            val eff = config.effective(list.first().first.library)
            val source = list.map { it.second.by }.distinct().joinToString("+")
            try {
                val item = if (kind == "movie") buildMovie(tmdb, id, list.map { it.first }, eff, source)
                else buildTv(tmdb, id, list, eff, source)
                items += withBangumi(tmdb, item, list.firstNotNullOfOrNull { it.second.bangumi }, eff)
                filesOf[item.id] = list.map { it.first }
            } catch (e: TmdbAuthException) {
                throw e
            } catch (e: Exception) {
                log("获取详情失败 $target: ${e.message}")
                list.mapTo(unmatched) { it.first.mediaFile }
            }
        }
        items.sortByDescending { it.addedAt }

        val previous = runCatching { loadLatestIndex() }.getOrNull()
        val collections = collections(items, filesOf)
        val library = Library(generatedAt = System.currentTimeMillis(), items = items, unmatched = unmatched, collections = collections)
        log("${items.size} 部作品, ${collections.size} 个合集, ${unmatched.size} 个视频待识别")

        val renames = planRenames(scan, mapping, items)
        var nfoWritten = 0
        var indexName: String? = null
        if (write) {
            nfoWritten = writeNfos(scan, mapping, items)
            // 内容没变 (只有生成时间不同) 就不传新索引, 避免每次定时任务都写网盘
            if (previous == null || previous.copy(generatedAt = 0) != library.copy(generatedAt = 0)) {
                indexName = writeIndex(library)
            } else {
                log("索引没有变化")
            }
        }
        return library to BuildReport(items.size, unmatched.size, nfoWritten, indexName, renames)
    }

    /**
     * 同一个顶层目录里有多部作品的, 合成一个合集 (和改名计划里的「合集」是同一个判断).
     * 名字和封面用合集里集数最多的剧; 全是电影时名字用目录名, 封面用最早的那部.
     */
    private fun collections(items: List<MediaItem>, filesOf: Map<String, List<ScannedFile>>): List<MediaCollection> {
        // 作品的文件分散在几个顶层目录时, 算在文件最多的那个里
        val topOf = items.mapNotNull { item ->
            val top = filesOf[item.id].orEmpty().mapNotNull { it.dirs.firstOrNull() }
                .groupingBy { it.fid }.eachCount().maxByOrNull { it.value }?.key ?: return@mapNotNull null
            item to filesOf.getValue(item.id).first { it.dirs.firstOrNull()?.fid == top }.dirs[0]
        }
        return topOf.groupBy({ it.second.fid }, { it.first }).values.filter { it.size >= 2 }.map { members ->
            val dir = topOf.first { it.first == members[0] }.second
            val sorted = members.sortedWith(compareBy<MediaItem, Int?>(nullsLast()) { it.year }.thenBy { it.title })
            val mainTv = members.filter { it.type == MediaType.TV }.maxByOrNull { it.seasons.sumOf { s -> s.episodes.size } }
            MediaCollection(
                id = "dir:${dir.fid}",
                name = mainTv?.title ?: dir.name,
                cover = (mainTv ?: sorted.first()).id,
                items = sorted.map { it.id },
            )
        }.sortedBy { it.id }
    }

    /**
     * 日本动画的片名用 Bangumi 的中文名 (TMDB 的中文名常是机翻或台译).
     * [override] 是映射里指定的条目; 没指定时按原名和年份自动匹配, 匹配不上就保留 TMDB 的.
     */
    private fun withBangumi(tmdb: TmdbApi, item: MediaItem, override: Int?, eff: Config.Effective): MediaItem {
        val bangumi = bangumi ?: return item
        val subject = try {
            if (override != null) {
                bangumi.subject(override)
            } else {
                val movie = item.type == MediaType.MOVIE
                val d = details(tmdb, if (movie) "movie" else "tv", item.tmdbId ?: return item, eff)
                val anime = d.genres.any { it.id == ANIMATION } && (d.originalLanguage == "ja" || "JP" in d.originCountry)
                if (!anime) return item
                Bangumi.match(bangumi, d.displayOriginal ?: return item, d.year, movie)
            }
        } catch (e: Exception) {
            log("Bangumi 查询失败 ${item.title}: ${e.message}")
            null
        } ?: return item
        val title = subject.displayName
        return item.copy(
            title = title,
            originalTitle = (item.originalTitle ?: subject.name).takeIf { it != title },
            bangumiId = subject.id,
        )
    }

    private fun details(tmdb: TmdbApi, kind: String, id: Int, eff: Config.Effective): Tmdb.Details {
        val d = if (kind == "movie") tmdb.movie(id, eff.language) else tmdb.tv(id, eff.language)
        if (!d.overview.isNullOrBlank() || eff.fallbackLanguage == eff.language) return d
        // 主语言没有简介时用回退语言补
        val fb = if (kind == "movie") tmdb.movie(id, eff.fallbackLanguage) else tmdb.tv(id, eff.fallbackLanguage)
        return d.copy(overview = fb.overview)
    }

    private fun base(kind: MediaType, d: Tmdb.Details, eff: Config.Effective, source: String, files: List<ScannedFile>): MediaItem {
        val lang = eff.language.substringBefore('-')
        val logo = d.images?.logos.orEmpty().let { logos ->
            logos.filter { it.language == lang }.maxByOrNull { it.voteAverage }
                ?: logos.filter { it.language == "en" }.maxByOrNull { it.voteAverage }
                ?: logos.maxByOrNull { it.voteAverage }
        }?.filePath
        // 背景图优先无文字的
        val backdrop = d.images?.backdrops.orEmpty().filter { it.language == null }.maxByOrNull { it.voteAverage }?.filePath
            ?: d.backdropPath
        val poster = d.posterPath
        val posterInfo = images?.analyze(poster)
        val backdropInfo = images?.analyze(backdrop, "w300")
        val certification = if (kind == MediaType.MOVIE) {
            val countries = d.releaseDates?.results.orEmpty()
            listOf("CN", "US").firstNotNullOfOrNull { c ->
                countries.firstOrNull { it.country == c }?.releaseDates?.firstOrNull { it.certification.isNotBlank() }?.certification
            }
        } else {
            listOf("CN", "US").firstNotNullOfOrNull { c -> d.contentRatings?.results?.firstOrNull { it.country == c && it.rating.isNotBlank() }?.rating }
        }
        return MediaItem(
            id = "${if (kind == MediaType.MOVIE) "movie" else "tv"}:${d.id}",
            type = kind,
            tmdbId = d.id,
            imdbId = d.imdbId ?: d.externalIds?.imdbId,
            title = d.displayTitle,
            originalTitle = d.displayOriginal?.takeIf { it != d.displayTitle },
            year = d.year,
            overview = d.overview?.takeIf { it.isNotBlank() },
            genres = d.genres.map { GENRE_ZH[it.name] ?: it.name },
            rating = d.voteAverage?.takeIf { it > 0 }?.let { Math.round(it * 10) / 10.0 },
            runtime = d.runtime ?: d.episodeRunTime.firstOrNull(),
            certification = certification,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            art = Artwork(
                color = backdropInfo?.color ?: posterInfo?.color,
                posterBlurHash = posterInfo?.blurHash,
                backdropBlurHash = backdropInfo?.blurHash,
            ),
            cast = d.credits?.cast.orEmpty().take(15).map { CastMember(it.name, it.character?.takeIf(String::isNotBlank), it.profilePath) },
            addedAt = files.minOf { it.file.updatedAt },
            match = MatchInfo(source, 1.0),
        )
    }

    private fun buildMovie(tmdb: TmdbApi, id: Int, files: List<ScannedFile>, eff: Config.Effective, source: String): MediaItem {
        val d = details(tmdb, "movie", id, eff)
        return base(MediaType.MOVIE, d, eff, source, files).copy(files = files.map { it.mediaFile })
    }

    private fun buildTv(tmdb: TmdbApi, id: Int, list: List<Pair<ScannedFile, MappingEntry>>, eff: Config.Effective, source: String): MediaItem {
        val d = details(tmdb, "tv", id, eff)
        // 每个文件的季/集; 映射里指定了季号的以映射为准
        val located = list.map { (f, entry) ->
            val p = FilenameParser.parse(f.file.name, f.parentNames)
            Triple(f, entry.season ?: p.season ?: 1, p.episode?.let { it + (entry.episodeOffset ?: 0) })
        }
        // 有的资源按绝对集数编号 (第二季目录里是 26~50 集), 换算成季内集数
        val seasonCounts = d.seasons.filter { it.seasonNumber > 0 }.associate { it.seasonNumber to it.episodeCount }
        fun relative(season: Int, ep: Int?): Int? {
            val count = seasonCounts[season] ?: return ep
            if (ep == null || ep <= count) return ep
            val offset = (1 until season).sumOf { seasonCounts[it] ?: 0 }
            return if (ep - offset in 1..count) ep - offset else ep
        }
        val seasons = located.map { (f, s, e) -> Triple(f, s, relative(s, e)) }.groupBy { it.second }.toSortedMap().map { (number, files) ->
            val sd = runCatching { tmdb.season(id, number, eff.language) }.getOrNull()
            val known = sd?.episodes.orEmpty().associateBy { it.episodeNumber }
            val episodes = files.groupBy { it.third }.map { (ep, fs) ->
                val e = ep?.let { known[it] }
                Episode(
                    season = number,
                    episode = ep ?: 0,
                    title = e?.name,
                    overview = e?.overview?.takeIf { it.isNotBlank() },
                    still = e?.stillPath,
                    airDate = e?.airDate,
                    runtime = e?.runtime,
                    files = fs.map { it.first.mediaFile },
                )
            }.sortedBy { it.episode }
            val summary = d.seasons.firstOrNull { it.seasonNumber == number }
            Season(number, sd?.name ?: summary?.name, sd?.overview?.takeIf { it.isNotBlank() }, sd?.posterPath ?: summary?.posterPath, episodes)
        }
        return base(MediaType.TV, d, eff, source, list.map { it.first }).copy(seasons = seasons)
    }

    // ---------- 改名 ----------

    /**
     * 只改作品所在的顶层目录; 顶层目录里有多部作品时当合集, 改下一层每部的目录.
     * 目录里有还没识别的文件、改完会重名的, 都只记录不改.
     */
    private fun planRenames(scan: Scan, mapping: Mapping, items: List<MediaItem>): List<RenamePlan> {
        val itemById = items.associateBy { it.id }
        // 每个文件对应的作品, 没识别的为 null, skip 的不参与
        val entries = scan.files.mapNotNull { f ->
            val entry = mapping.resolve(f)?.second
            when {
                f.dirs.isEmpty() -> null
                entry == null -> f to null
                entry.isSkip -> null
                else -> f to entry.target
            }
        }

        val plans = mutableListOf<RenamePlan>()
        fun plan(dir: QuarkFile, parentFid: String, parentPath: String, ids: Set<String?>) {
            val item = ids.singleOrNull()?.let { itemById[it] }
            val skip = when {
                null in ids && ids.size > 1 -> "部分文件还没识别"
                item == null -> "还没识别"
                else -> null
            }
            val to = item?.let { targetName(it) } ?: ""
            if (skip == null && to == dir.name) return
            plans += RenamePlan(dir.fid, parentFid, parentPath, dir.name, to, item?.let { "${it.id} ${it.title}" } ?: "", skip)
        }

        entries.groupBy { (f, _) -> f.library.path.trimEnd('/') to f.dirs[0] }.forEach { (key, list) ->
            val (libPath, top) = key
            val rootFid = scan.libraryRoots[libPath] ?: scan.libraryRoots.getValue("$libPath/")
            val ids = list.map { it.second }.toSet()
            if (ids.filterNotNull().size <= 1) {
                plan(top, rootFid, libPath, ids)
            } else {
                // 合集: 保留合集目录, 改下一层
                list.filter { (f, _) -> f.dirs.size >= 2 }.groupBy { (f, _) -> f.dirs[1] }.forEach { (child, cl) ->
                    val childIds = cl.map { it.second }.toSet()
                    // 下一层里还是多部作品的 (如 "剧场版" 目录), 不再往下改
                    if (childIds.filterNotNull().size <= 1) plan(child, top.fid, "$libPath/${top.name}", childIds)
                }
            }
        }

        // 改完会和同目录其他目录重名的不改
        return plans.groupBy { it.parentFid }.flatMap { (parentFid, group) ->
            val siblings = drive.list(parentFid)
            group.map { p ->
                if (p.skipped != null) return@map p
                val clash = siblings.any { it.fid != p.fid && it.name == p.to } ||
                    group.count { it.skipped == null && it.to == p.to } > 1
                if (clash) p.copy(skipped = "改完会重名") else p
            }
        }
    }

    /** 按计划文件改名. 执行前核对 fid 和原名, 网盘里已经变了的跳过. 返回 (成功, 跳过的原因). */
    fun applyRenames(plans: List<RenamePlan>): Pair<Int, List<String>> {
        var done = 0
        val skipped = mutableListOf<String>()
        for ((parentFid, group) in plans.filter { it.skipped == null }.groupBy { it.parentFid }) {
            val siblings = drive.list(parentFid)
            for (p in group) {
                val current = siblings.firstOrNull { it.fid == p.fid }
                when {
                    current == null -> skipped += "${p.parentPath}/${p.from}: 已不存在"
                    current.name == p.to -> {}
                    current.name != p.from -> skipped += "${p.parentPath}/${p.from}: 名字已变成 ${current.name}"
                    siblings.any { it.fid != p.fid && it.name == p.to } -> skipped += "${p.parentPath}/${p.from}: 已有同名 ${p.to}"
                    else -> {
                        drive.rename(p.fid, p.to)
                        log("改名 ${p.parentPath}/${p.from} → ${p.to}")
                        done++
                    }
                }
            }
        }
        return done to skipped
    }

    /** "片名 (年份)", 去掉网盘不允许的字符. */
    private fun targetName(item: MediaItem): String {
        val title = item.title.replace('/', '／').replace('\\', '＼').replace(':', '：').replace(Regex("[*?\"<>|]"), "").trim()
        return if (item.year != null) "$title (${item.year})" else title
    }

    // ---------- 写网盘 ----------

    /**
     * NFO 写在映射所在的位置: 电影写在每个视频旁边; 剧集的 tvshow.nfo 写在映射的目录里.
     * 已经存在的不覆盖 (除非 existing_nfo: overwrite), 所以增量运行时只会写新作品的.
     */
    private fun writeNfos(scan: Scan, mapping: Mapping, items: List<MediaItem>): Int {
        val itemById = items.associateBy { it.id }
        var written = 0
        val doneDirs = mutableSetOf<String>()
        for (f in scan.files) {
            val (keyFid, entry) = mapping.resolve(f) ?: continue
            if (entry.isSkip || entry.by == "nfo") continue
            val eff = config.effective(f.library)
            if (eff.output == OutputMode.INDEX) continue
            val item = itemById[entry.target] ?: continue
            val overwrite = eff.existingNfo == ExistingNfo.OVERWRITE
            val full = eff.output == OutputMode.INDEX_FULL
            try {
                when (item.type) {
                    MediaType.MOVIE -> {
                        val base = f.file.name.substringBeforeLast('.')
                        val parent = f.dirs.lastOrNull()?.fid ?: scan.libraryRoots.getValue(f.library.path)
                        if (put(parent, f.siblings, "$base.nfo", Nfo.movie(item).toByteArray(), overwrite)) written++
                        if (full) {
                            putImage(parent, f.siblings, "$base-poster.jpg", item.poster)
                            putImage(parent, f.siblings, "$base-fanart.jpg", item.backdrop)
                            putImage(parent, f.siblings, "$base-clearlogo.png", item.logo)
                        }
                    }
                    MediaType.TV -> {
                        // 映射在目录上时, tvshow.nfo 写在那个目录
                        val showDir = f.dirs.firstOrNull { it.fid == keyFid }
                        if (showDir != null && doneDirs.add(showDir.fid)) {
                            val siblings = drive.list(showDir.fid)
                            if (put(showDir.fid, siblings, "tvshow.nfo", Nfo.tvShow(item).toByteArray(), overwrite)) written++
                            if (full) {
                                putImage(showDir.fid, siblings, "poster.jpg", item.poster)
                                putImage(showDir.fid, siblings, "fanart.jpg", item.backdrop)
                                putImage(showDir.fid, siblings, "clearlogo.png", item.logo)
                            }
                        }
                        if (full) {
                            val ep = item.seasons.flatMap { it.episodes }.firstOrNull { e -> e.files.any { it.fid == f.file.fid } }
                            val parent = f.dirs.lastOrNull()?.fid
                            if (ep != null && parent != null) {
                                val base = f.file.name.substringBeforeLast('.')
                                if (put(parent, f.siblings, "$base.nfo", Nfo.episode(item, ep).toByteArray(), overwrite)) written++
                                putImage(parent, f.siblings, "$base-thumb.jpg", ep.still)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("写 NFO 失败 ${f.path}: ${e.message}")
            }
        }
        return written
    }

    /** 写文件; 已存在时默认跳过, overwrite 时先删再传 (夸克不覆盖同名文件). */
    private fun put(parentFid: String, siblings: List<QuarkFile>, name: String, bytes: ByteArray, overwrite: Boolean): Boolean {
        val existing = siblings.filter { it.name == name }
        if (existing.isNotEmpty() && !overwrite) return false
        drive.delete(existing.map { it.fid })
        drive.upload(parentFid, name, bytes)
        return true
    }

    private fun putImage(parentFid: String, siblings: List<QuarkFile>, name: String, tmdbPath: String?) {
        if (tmdbPath == null || siblings.any { it.name == name } || images == null) return
        val bytes = images.download(arisa.library.tmdbImage(tmdbPath, "original")!!) ?: return
        drive.upload(parentFid, name, bytes)
    }

    private fun indexDir(): QuarkFile? = drive.list(drive.rootFid).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }

    private fun loadLatestIndex(): Library? {
        val dir = indexDir() ?: return null
        val files = drive.list(dir.fid)
        val latest = LibraryCodec.latest(files.map { it.name }) ?: return null
        return LibraryCodec.decode(drive.download(files.first { it.name == latest }.fid))
    }

    private fun writeIndex(library: Library): String {
        val dirFid = indexDir()?.fid ?: drive.mkdir(drive.rootFid, LibraryCodec.DIR)
        val name = LibraryCodec.fileName(library.generatedAt)
        drive.upload(dirFid, name, LibraryCodec.encode(library))
        // 只保留最近几个版本
        val old = drive.list(dirFid).filter { LibraryCodec.isIndexFile(it.name) }
            .sortedByDescending { it.name.filter(Char::isDigit).toLongOrNull() ?: 0 }
            .drop(config.scan.keepIndexes)
        drive.delete(old.map { it.fid })
        log("索引已写入 /${LibraryCodec.DIR}/$name")
        return name
    }

    private companion object {
        /** TMDB 的「动画」类型. */
        const val ANIMATION = 16

        /** TMDB 剧集类型有几个没有中文翻译. */
        val GENRE_ZH = mapOf(
            "Sci-Fi & Fantasy" to "科幻奇幻",
            "Action & Adventure" to "动作冒险",
            "War & Politics" to "战争政治",
            "Kids" to "儿童",
            "News" to "新闻",
            "Reality" to "真人秀",
            "Soap" to "肥皂剧",
            "Talk" to "脱口秀",
        )
    }
}
