package arisa.scraper

import arisa.library.Artwork
import arisa.library.CastMember
import arisa.library.Episode
import arisa.library.Library
import arisa.library.LibraryCodec
import arisa.library.MatchInfo
import arisa.library.MediaFile
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.Season
import arisa.quark.QuarkFile

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
    val mediaFile get() = MediaFile(file.fid, file.name, path, file.size, file.updatedAt)
}

/** 一个待识别的单位: 一部电影的一个文件, 或者一部剧的所有文件. */
private data class Work(
    val kind: MediaType,
    val files: List<ScannedFile>,
    /** 剧集的根目录, 电影为 null. */
    val showDir: QuarkFile?,
    val showSiblings: List<QuarkFile>,
    val parsed: ParsedName,
) {
    val first get() = files.first()
    val displayPath get() = showDir?.let { first.path.substringBefore("/${it.name}/") + "/${it.name}" } ?: first.path
}

private data class Match(val tmdbId: Int, val source: String, val confidence: Double)

data class ScanReport(
    val matched: Int,
    val unmatched: List<String>,
    val lowConfidence: List<String>,
    val nfoWritten: Int,
    val indexFile: String?,
    val renames: List<RenamePlan> = emptyList(),
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

class Scraper(
    private val config: Config,
    private val drive: Drive,
    private val tmdb: TmdbApi,
    private val ai: Ai?,
    private val images: Images?,
    private val log: (String) -> Unit = ::println,
) {
    private val lowConfidence = mutableListOf<String>()

    /**
     * @param write false 时只生成索引不写网盘 (dry run)
     */
    fun run(write: Boolean = true): Pair<Library, ScanReport> {
        val previous = runCatching { loadLatestIndex() }.getOrNull()
        val previousMatch = previous?.let(::indexByFid) ?: emptyMap()
        log("上次索引: ${previous?.items?.size ?: 0} 部")

        val units = mutableListOf<Work>()
        for (lib in config.libraries) {
            val root = drive.resolve(lib.path)
            if (root == null || !root.isDir) {
                log("片库目录不存在: ${lib.path}")
                continue
            }
            val files = mutableListOf<ScannedFile>()
            walk(lib, root, lib.path.trimEnd('/'), emptyList(), files)
            log("${lib.path}: ${files.size} 个视频")
            units += group(lib, files)
        }

        // 规则识别不了的批量交给 AI 解析文件名
        val effective = units.associateWith { config.effective(it.first.library) }
        val needAiParse = units.filter { u ->
            val eff = effective.getValue(u)
            ai?.enabled == true && eff.recognize != Recognize.RULES &&
                (eff.recognize == Recognize.AI || u.parsed.titles.isEmpty()) &&
                previousMatch[u.first.file.fid] == null
        }
        val aiParsed = aiParse(needAiParse)

        val matches = mutableMapOf<Work, Match?>()
        for (u in units) {
            val eff = effective.getValue(u)
            matches[u] = try {
                match(u, eff, previousMatch, aiParsed[u])
            } catch (e: TmdbAuthException) {
                throw e
            } catch (e: Exception) {
                log("识别失败 ${u.displayPath}: ${e.message}")
                null
            }
        }

        val (items, unmatched) = build(units, matches, effective)
        val library = Library(generatedAt = System.currentTimeMillis(), items = items, unmatched = unmatched)
        log("识别 ${items.size} 部, 未识别 ${unmatched.size} 个文件")

        val renames = planRenames(units, matches, items, effective)

        var nfoWritten = 0
        var indexName: String? = null
        if (write) {
            nfoWritten = writeNfos(units, matches, items, effective)
            indexName = writeIndex(library)
        }
        return library to ScanReport(items.size, unmatched.map { it.path }, lowConfidence.toList(), nfoWritten, indexName, renames)
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

    // ---------- 扫描 ----------

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

    private fun group(lib: LibraryConfig, files: List<ScannedFile>): List<Work> {
        val units = mutableListOf<Work>()
        val tvGroups = LinkedHashMap<String, MutableList<Pair<ScannedFile, ParsedName>>>()
        val showDirs = mutableMapOf<String, Pair<QuarkFile?, List<QuarkFile>>>()

        for (f in files) {
            val p = FilenameParser.parse(f.file.name, f.parentNames)
            val isTv = when (lib.type) {
                LibraryType.TV -> true
                LibraryType.MOVIE -> false
                LibraryType.AUTO -> p.isEpisode
            }
            if (!isTv) {
                units += Work(MediaType.MOVIE, listOf(f), null, emptyList(), p)
                continue
            }
            // 剧集根目录: tv 片库取第一层目录; auto 取最近的非季、非画质/版本目录
            val showDir = when (lib.type) {
                LibraryType.TV -> f.dirs.firstOrNull()
                else -> f.dirs.lastOrNull { FilenameParser.seasonOfDir(it.name) == null && !FilenameParser.isNoiseDir(it.name) }
            }
            val key = showDir?.fid ?: "title:${p.titles.firstOrNull()?.lowercase() ?: f.file.fid}"
            tvGroups.getOrPut(key) { mutableListOf() } += f to p
            if (key !in showDirs) {
                val siblings = showDir?.let { sd ->
                    // 剧集根目录下的文件 (tvshow.nfo 在这里); 根目录就是视频所在目录时直接复用
                    if (f.dirs.lastOrNull()?.fid == sd.fid) f.siblings else drive.list(sd.fid)
                } ?: emptyList()
                showDirs[key] = showDir to siblings
            }
        }
        for ((key, list) in tvGroups) {
            val (showDir, siblings) = showDirs.getValue(key)
            // 剧名以目录名为准, 没有目录时用文件名里最常见的片名
            val dirParsed = showDir?.let { FilenameParser.parseText(it.name) }
            val titles = dirParsed?.titles?.takeIf { it.isNotEmpty() }
                ?: list.groupingBy { it.second.titles }.eachCount().maxByOrNull { it.value }?.key
                ?: emptyList()
            val year = dirParsed?.year ?: list.firstNotNullOfOrNull { it.second.year }
            units += Work(MediaType.TV, list.map { it.first }, showDir, siblings, ParsedName(titles, year))
        }
        return units
    }

    // ---------- 识别 ----------

    private fun aiParse(units: List<Work>): Map<Work, ParsedName> {
        val ai = ai ?: return emptyMap()
        if (units.isEmpty()) return emptyMap()
        log("AI 解析 ${units.size} 个文件名")
        val result = mutableMapOf<Work, ParsedName>()
        units.chunked(40).forEach { chunk ->
            val parsed = runCatching { ai.parse(chunk.map { it.displayPath }) }
                .onFailure { log("AI 解析失败: ${it.message}") }
                .getOrDefault(emptyList())
            for (p in parsed) {
                val u = chunk.getOrNull(p.index) ?: continue
                val titles = listOfNotNull(p.titleZh, p.titleEn).filter { it.isNotBlank() }.distinct()
                if (titles.isNotEmpty()) result[u] = ParsedName(titles, p.year ?: u.parsed.year)
            }
        }
        return result
    }

    private fun match(u: Work, eff: Config.Effective, previous: Map<String, Pair<MediaType, Int>>, aiParsed: ParsedName?): Match? {
        // 1. 人工修正
        overrideFor(u)?.let { return it }

        // 2. 已有 NFO
        if (eff.existingNfo == ExistingNfo.TRUST || eff.existingNfo == ExistingNfo.MERGE) {
            nfoMatch(u)?.let { return it }
        }

        // 3. 上次已识别过的文件, 沿用
        u.files.firstNotNullOfOrNull { previous[it.file.fid] }?.let { (type, id) ->
            if (type == u.kind) return Match(id, "previous", 1.0)
        }

        // 4. 规则 / AI 解析出片名后搜 TMDB
        val parsed = aiParsed ?: u.parsed
        if (parsed.titles.isEmpty()) return null
        val candidates = search(u.kind, parsed, eff.language)
        if (candidates.isEmpty()) return null

        val ruleChoice = ruleChoose(parsed, candidates)
        if (ruleChoice != null && ruleChoice.confidence >= eff.confidenceThreshold) return ruleChoice

        val ai = ai
        if (ai != null && ai.enabled && eff.recognize != Recognize.RULES) {
            val choice = runCatching {
                ai.choose(u.displayPath, if (u.kind == MediaType.TV) "剧集" else "电影", candidates.take(8).map {
                    Candidate(it.id, it.displayTitle, it.displayOriginal, it.year, it.overview)
                })
            }.onFailure { log("AI 选择失败: ${it.message}") }.getOrNull()
            if (choice?.tmdbId != null) {
                if (choice.confidence < eff.confidenceThreshold) lowConfidence += "${u.displayPath} → ${choice.tmdbId} (${choice.confidence})"
                return Match(choice.tmdbId, "ai", choice.confidence)
            }
        }
        if (ruleChoice != null) {
            lowConfidence += "${u.displayPath} → ${ruleChoice.tmdbId} (${"%.2f".format(ruleChoice.confidence)})"
            return ruleChoice
        }
        return null
    }

    private fun overrideFor(u: Work): Match? {
        val path = u.displayPath
        val o = config.overrides
            .filter { path == it.path.trimEnd('/') || path.startsWith(it.path.trimEnd('/') + "/") }
            .maxByOrNull { it.path.length } ?: return null
        val type = if (o.type == LibraryType.TV) MediaType.TV else MediaType.MOVIE
        return if (type == u.kind) Match(o.tmdbId, "manual", 1.0) else null
    }

    private fun nfoMatch(u: Work): Match? {
        val nfoFile = when (u.kind) {
            MediaType.TV -> u.showSiblings.firstOrNull { it.name.equals("tvshow.nfo", true) }
            MediaType.MOVIE -> {
                val base = u.first.file.name.substringBeforeLast('.')
                u.first.siblings.firstOrNull { it.name.equals("$base.nfo", true) }
                    ?: u.first.siblings.firstOrNull { it.name.equals("movie.nfo", true) }
            }
        } ?: return null
        val ids = runCatching { Nfo.read(drive.download(nfoFile.fid).decodeToString()) }.getOrNull() ?: return null
        ids.tmdbId?.let { return Match(it, "nfo", 1.0) }
        ids.imdbId?.let { imdb ->
            val found = runCatching { tmdb.findByImdb(imdb) }.getOrNull() ?: return null
            val kind = if (found.first == Tmdb.MediaKind.TV) MediaType.TV else MediaType.MOVIE
            if (kind == u.kind) return Match(found.second, "nfo", 1.0)
        }
        return null
    }

    private fun search(kind: MediaType, parsed: ParsedName, language: String): List<Tmdb.SearchResult> {
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
        return seen.values.toList()
    }

    /** 规则判断: 片名完全一致且年份对得上才算高置信度. */
    private fun ruleChoose(parsed: ParsedName, candidates: List<Tmdb.SearchResult>): Match? {
        val queries = parsed.titles.map(::normalize).toSet()
        val year = parsed.year
        val scored = candidates.map { c ->
            val cYear = c.year
            val names = listOfNotNull(c.displayTitle, c.displayOriginal).map(::normalize)
            val exact = names.any { it in queries }
            val yearOk = year == null || cYear == null || cYear == year
            val yearNear = year != null && cYear != null && kotlin.math.abs(cYear - year) <= 1
            val score = when {
                exact && year != null && cYear == year -> 0.97
                exact && yearNear -> 0.9
                exact && yearOk -> 0.8
                exact -> 0.5
                yearOk -> 0.4
                else -> 0.2
            }
            c to score
        }
        val ranked = scored.sortedWith(compareByDescending<Pair<Tmdb.SearchResult, Double>> { it.second }.thenByDescending { it.first.popularity })
        val (best, score) = ranked.first()
        // 同分的不止一个 (同名作品) 时降低置信度
        val tie = ranked.count { it.second == score } > 1
        val confidence = if (tie && year == null) score - 0.2 else score
        return Match(best.id, "rules", confidence)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·：:，,。！!？?'’\"“”]"), "")

    // ---------- 生成条目 ----------

    private fun build(
        units: List<Work>,
        matches: Map<Work, Match?>,
        effective: Map<Work, Config.Effective>,
    ): Pair<List<MediaItem>, List<MediaFile>> {
        val unmatched = mutableListOf<MediaFile>()
        // 同一部作品可能分散在多个单位 (多个版本、多个季目录), 按 TMDB ID 合并
        val byId = LinkedHashMap<Pair<MediaType, Int>, MutableList<Work>>()
        for (u in units) {
            val m = matches[u]
            if (m == null) {
                u.files.mapTo(unmatched) { it.mediaFile }
            } else {
                byId.getOrPut(u.kind to m.tmdbId) { mutableListOf() } += u
            }
        }
        val items = mutableListOf<MediaItem>()
        for ((key, group) in byId) {
            val (kind, id) = key
            val eff = effective.getValue(group.first())
            val match = group.mapNotNull { matches[it] }.minBy { it.confidence }
            try {
                items += when (kind) {
                    MediaType.MOVIE -> buildMovie(id, group, eff, match)
                    MediaType.TV -> buildTv(id, group, eff, match)
                }
            } catch (e: Exception) {
                log("获取详情失败 $kind $id: ${e.message}")
                group.flatMap { it.files }.mapTo(unmatched) { it.mediaFile }
            }
        }
        items.sortByDescending { it.addedAt }
        return items to unmatched
    }

    private fun details(kind: MediaType, id: Int, eff: Config.Effective): Tmdb.Details {
        val d = if (kind == MediaType.MOVIE) tmdb.movie(id, eff.language) else tmdb.tv(id, eff.language)
        if (!d.overview.isNullOrBlank() || eff.fallbackLanguage == eff.language) return d
        // 主语言没有简介时用回退语言补
        val fb = if (kind == MediaType.MOVIE) tmdb.movie(id, eff.fallbackLanguage) else tmdb.tv(id, eff.fallbackLanguage)
        return d.copy(overview = fb.overview)
    }

    private fun base(kind: MediaType, d: Tmdb.Details, eff: Config.Effective, match: Match, files: List<ScannedFile>): MediaItem {
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
            match = MatchInfo(match.source, match.confidence),
        )
    }

    private fun buildMovie(id: Int, group: List<Work>, eff: Config.Effective, match: Match): MediaItem {
        val files = group.flatMap { it.files }
        val d = details(MediaType.MOVIE, id, eff)
        return base(MediaType.MOVIE, d, eff, match, files).copy(files = files.map { it.mediaFile })
    }

    private fun buildTv(id: Int, group: List<Work>, eff: Config.Effective, match: Match): MediaItem {
        val files = group.flatMap { it.files }
        val d = details(MediaType.TV, id, eff)
        // 每个文件的季/集
        val located = files.map { f ->
            val p = FilenameParser.parse(f.file.name, f.parentNames)
            Triple(f, p.season ?: 1, p.episode)
        }
        // 有的资源按绝对集数编号 (第二季目录里是 26~50 集), 换算成季内集数
        val seasonCounts = d.seasons.filter { it.seasonNumber > 0 }.associate { it.seasonNumber to it.episodeCount }
        fun relative(season: Int, ep: Int?): Int? {
            val count = seasonCounts[season] ?: return ep
            if (ep == null || ep <= count) return ep
            val offset = (1 until season).sumOf { seasonCounts[it] ?: 0 }
            return if (ep - offset in 1..count) ep - offset else ep
        }
        val seasons = located.map { (f, s, e) -> Triple(f, s, relative(s, e)) }.groupBy { it.second }.toSortedMap().map { (number, list) ->
            val sd = runCatching { tmdb.season(id, number, eff.language) }.getOrNull()
            val known = sd?.episodes.orEmpty().associateBy { it.episodeNumber }
            val episodes = list.groupBy { it.third }.map { (ep, fs) ->
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
        return base(MediaType.TV, d, eff, match, files).copy(seasons = seasons)
    }

    // ---------- 改名 ----------

    /**
     * 只改作品所在的顶层目录; 顶层目录里有多部作品时当合集, 改下一层每部的目录.
     * 置信度不够、目录里有没识别的文件、改完会重名的, 都只记录不改.
     */
    private fun planRenames(
        units: List<Work>,
        matches: Map<Work, Match?>,
        items: List<MediaItem>,
        effective: Map<Work, Config.Effective>,
    ): List<RenamePlan> {
        val itemById = items.associateBy { it.id }
        // 每个文件对应的作品, 没识别或置信度不够的为 null
        val entries = units.flatMap { u ->
            val m = matches[u]
            val id = m?.takeIf { it.confidence >= effective.getValue(u).confidenceThreshold }
                ?.let { "${if (u.kind == MediaType.MOVIE) "movie" else "tv"}:${it.tmdbId}" }
            u.files.map { it to id }
        }.filter { (f, _) -> f.dirs.isNotEmpty() }

        val plans = mutableListOf<RenamePlan>()
        val rootFids = mutableMapOf<String, String>()
        fun plan(dir: QuarkFile, parentFid: String, parentPath: String, ids: Set<String?>) {
            val item = ids.singleOrNull()?.let { itemById[it] }
            val skip = when {
                null in ids && ids.size > 1 -> "部分文件没识别出来"
                null in ids -> "没识别出来"
                item == null -> "没识别出来"
                else -> null
            }
            val to = item?.let { targetName(it) } ?: ""
            if (skip == null && to == dir.name) return
            plans += RenamePlan(dir.fid, parentFid, parentPath, dir.name, to, item?.let { "${it.id} ${it.title}" } ?: "", skip)
        }

        entries.groupBy { (f, _) -> f.library.path.trimEnd('/') to f.dirs[0] }.forEach { (key, list) ->
            val (libPath, top) = key
            val rootFid = rootFids.getOrPut(libPath) { drive.resolve(libPath)!!.fid }
            val ids = list.map { it.second }.toSet()
            val known = ids.filterNotNull()
            if (known.size <= 1) {
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

    /** "片名 (年份)", 去掉网盘不允许的字符. */
    private fun targetName(item: MediaItem): String {
        val title = item.title.replace('/', '／').replace('\\', '＼').replace(':', '：').replace(Regex("[*?\"<>|]"), "").trim()
        return if (item.year != null) "$title (${item.year})" else title
    }

    // ---------- 写网盘 ----------

    private fun writeNfos(
        units: List<Work>,
        matches: Map<Work, Match?>,
        items: List<MediaItem>,
        effective: Map<Work, Config.Effective>,
    ): Int {
        val itemById = items.associateBy { it.id }
        var written = 0
        for (u in units) {
            val eff = effective.getValue(u)
            if (eff.output == OutputMode.INDEX) continue
            val m = matches[u] ?: continue
            // NFO 本来就是读来的, 除非要求覆盖, 不再回写
            if (m.source == "nfo" && eff.existingNfo != ExistingNfo.OVERWRITE) continue
            val item = itemById["${if (u.kind == MediaType.MOVIE) "movie" else "tv"}:${m.tmdbId}"] ?: continue
            val full = eff.output == OutputMode.INDEX_FULL
            val overwrite = eff.existingNfo == ExistingNfo.OVERWRITE || m.source == "manual"
            try {
                when (u.kind) {
                    MediaType.MOVIE -> {
                        val f = u.first
                        val base = f.file.name.substringBeforeLast('.')
                        val parent = f.dirs.lastOrNull()?.fid ?: libraryRootFid(f.library)
                        if (put(parent, f.siblings, "$base.nfo", Nfo.movie(item).toByteArray(), overwrite)) written++
                        if (full) {
                            putImage(parent, f.siblings, "$base-poster.jpg", item.poster)
                            putImage(parent, f.siblings, "$base-fanart.jpg", item.backdrop)
                            putImage(parent, f.siblings, "$base-clearlogo.png", item.logo)
                        }
                    }
                    MediaType.TV -> {
                        val dir = u.showDir ?: continue
                        if (put(dir.fid, u.showSiblings, "tvshow.nfo", Nfo.tvShow(item).toByteArray(), overwrite)) written++
                        if (full) {
                            putImage(dir.fid, u.showSiblings, "poster.jpg", item.poster)
                            putImage(dir.fid, u.showSiblings, "fanart.jpg", item.backdrop)
                            putImage(dir.fid, u.showSiblings, "clearlogo.png", item.logo)
                            item.seasons.forEach { s ->
                                putImage(dir.fid, u.showSiblings, "season%02d-poster.jpg".format(s.number), s.poster)
                            }
                            written += writeEpisodeNfos(u, item, overwrite)
                        }
                    }
                }
            } catch (e: Exception) {
                log("写 NFO 失败 ${u.displayPath}: ${e.message}")
            }
        }
        return written
    }

    private fun writeEpisodeNfos(u: Work, item: MediaItem, overwrite: Boolean): Int {
        var written = 0
        val byFid = item.seasons.flatMap { s -> s.episodes.flatMap { e -> e.files.map { it.fid to e } } }.toMap()
        for (f in u.files) {
            val ep = byFid[f.file.fid] ?: continue
            val parent = f.dirs.lastOrNull()?.fid ?: continue
            val base = f.file.name.substringBeforeLast('.')
            if (put(parent, f.siblings, "$base.nfo", Nfo.episode(item, ep).toByteArray(), overwrite)) written++
            putImage(parent, f.siblings, "$base-thumb.jpg", ep.still)
        }
        return written
    }

    private fun libraryRootFid(lib: LibraryConfig) = drive.resolve(lib.path)!!.fid

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

    private fun indexByFid(lib: Library): Map<String, Pair<MediaType, Int>> {
        val map = mutableMapOf<String, Pair<MediaType, Int>>()
        for (item in lib.items) {
            val id = item.tmdbId ?: continue
            if (item.match.source == "manual") continue
            item.files.forEach { map[it.fid] = item.type to id }
            item.seasons.forEach { s -> s.episodes.forEach { e -> e.files.forEach { map[it.fid] = item.type to id } } }
        }
        return map
    }
}
