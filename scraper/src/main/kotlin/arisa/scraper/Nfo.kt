package arisa.scraper

import arisa.library.Episode
import arisa.library.MediaItem
import arisa.library.tmdbImage

/** 从已有 NFO 里读出的识别信息. */
data class NfoIds(
    /** movie / tvshow / episodedetails */
    val root: String,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String?,
)

/**
 * Kodi 格式的 NFO, Jellyfin/Emby/Infuse 也认.
 * 只读需要的几个字段, 不引入 XML 库.
 */
object Nfo {
    fun read(xml: String): NfoIds? {
        val root = Regex("<(movie|tvshow|episodedetails)[\\s>]").find(xml)?.groupValues?.get(1) ?: return null
        fun tag(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(xml)?.groupValues?.get(1)
        fun uniqueId(type: String) =
            Regex("<uniqueid[^>]*type=\"$type\"[^>]*>\\s*([^<]+?)\\s*</uniqueid>").find(xml)?.groupValues?.get(1)

        val tmdb = (uniqueId("tmdb") ?: tag("tmdbid"))?.toIntOrNull()
        val imdb = (uniqueId("imdb") ?: tag("imdbid") ?: tag("id")?.takeIf { it.startsWith("tt") })
        return NfoIds(root, tmdb, imdb, tag("title")?.let(::unescape))
    }

    fun movie(item: MediaItem): String = document("movie") {
        common(item)
        item.runtime?.let { el("runtime", it.toString()) }
        item.certification?.let { el("mpaa", it) }
    }

    fun tvShow(item: MediaItem): String = document("tvshow") {
        common(item)
        item.seasons.forEach { s ->
            s.poster?.let { raw("  <thumb aspect=\"poster\" type=\"season\" season=\"${s.number}\">${esc(tmdbImage(it, "original")!!)}</thumb>") }
        }
    }

    fun episode(show: MediaItem, ep: Episode): String = document("episodedetails") {
        el("title", ep.title ?: "第 ${ep.episode} 集")
        el("showtitle", show.title)
        el("season", ep.season.toString())
        el("episode", ep.episode.toString())
        ep.overview?.let { el("plot", it) }
        ep.airDate?.let { el("aired", it) }
        ep.runtime?.let { el("runtime", it.toString()) }
        ep.still?.let { el("thumb", tmdbImage(it, "original")!!) }
    }

    private fun Builder.common(item: MediaItem) {
        el("title", item.title)
        item.originalTitle?.let { el("originaltitle", it) }
        item.year?.let { el("year", it.toString()) }
        item.overview?.let { el("plot", it) }
        item.rating?.let {
            raw("  <ratings><rating name=\"themoviedb\" max=\"10\" default=\"true\"><value>$it</value></rating></ratings>")
        }
        item.genres.forEach { el("genre", it) }
        item.tmdbId?.let { raw("  <uniqueid type=\"tmdb\" default=\"true\">$it</uniqueid>") }
        item.imdbId?.let { raw("  <uniqueid type=\"imdb\">${esc(it)}</uniqueid>") }
        item.poster?.let { raw("  <thumb aspect=\"poster\">${esc(tmdbImage(it, "original")!!)}</thumb>") }
        item.logo?.let { raw("  <thumb aspect=\"clearlogo\">${esc(tmdbImage(it, "original")!!)}</thumb>") }
        item.backdrop?.let { raw("  <fanart><thumb>${esc(tmdbImage(it, "original")!!)}</thumb></fanart>") }
        item.cast.forEach { c ->
            raw("  <actor>")
            el("name", c.name, indent = 4)
            c.character?.let { el("role", it, indent = 4) }
            c.profile?.let { el("thumb", tmdbImage(it, "original")!!, indent = 4) }
            raw("  </actor>")
        }
    }

    private class Builder {
        val sb = StringBuilder()
        fun el(name: String, value: String, indent: Int = 2) {
            sb.append(" ".repeat(indent)).append("<$name>").append(esc(value)).append("</$name>\n")
        }
        fun raw(line: String) {
            sb.append(line).append('\n')
        }
    }

    private fun document(root: String, body: Builder.() -> Unit): String {
        val b = Builder().apply(body)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<$root>\n${b.sb}</$root>\n"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
