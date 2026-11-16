package main

import (
	"fmt"
	"strconv"
	"strings"
)

// Kodi 格式的 NFO, Jellyfin/Emby/Infuse 也认. 读的部分在 nfo.go.

type nfoBuilder struct{ sb strings.Builder }

func (b *nfoBuilder) el(name, value string, indent int) {
	b.sb.WriteString(strings.Repeat(" ", indent) + "<" + name + ">" + xmlEsc(value) + "</" + name + ">\n")
}

func (b *nfoBuilder) raw(line string) { b.sb.WriteString(line + "\n") }

func nfoDocument(root string, body func(b *nfoBuilder)) string {
	b := &nfoBuilder{}
	body(b)
	return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<" + root + ">\n" + b.sb.String() + "</" + root + ">\n"
}

func xmlEsc(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "\"", "&quot;").Replace(s)
}

// kotlinDouble 按 Kotlin 的 Double.toString 输出评分 (8.0 写成 "8.0").
func kotlinDouble(v float64) string {
	s := strconv.FormatFloat(v, 'f', -1, 64)
	if !strings.ContainsAny(s, ".eE") {
		s += ".0"
	}
	return s
}

func nfoCommon(b *nfoBuilder, item *MediaItem) {
	b.el("title", item.Title, 2)
	if item.OriginalTitle != "" {
		b.el("originaltitle", item.OriginalTitle, 2)
	}
	if item.Year != nil {
		b.el("year", strconv.Itoa(*item.Year), 2)
	}
	if item.Overview != "" {
		b.el("plot", item.Overview, 2)
	}
	if item.Rating != nil {
		b.raw(`  <ratings><rating name="themoviedb" max="10" default="true"><value>` + kotlinDouble(*item.Rating) + `</value></rating></ratings>`)
	}
	for _, g := range item.Genres {
		b.el("genre", g, 2)
	}
	if item.TmdbID != nil {
		b.raw(fmt.Sprintf(`  <uniqueid type="tmdb" default="true">%d</uniqueid>`, *item.TmdbID))
	}
	if item.ImdbID != "" {
		b.raw(`  <uniqueid type="imdb">` + xmlEsc(item.ImdbID) + `</uniqueid>`)
	}
	if item.Poster != "" {
		b.raw(`  <thumb aspect="poster">` + xmlEsc(TmdbImage(item.Poster, "original")) + `</thumb>`)
	}
	if item.Logo != "" {
		b.raw(`  <thumb aspect="clearlogo">` + xmlEsc(TmdbImage(item.Logo, "original")) + `</thumb>`)
	}
	if item.Backdrop != "" {
		b.raw(`  <fanart><thumb>` + xmlEsc(TmdbImage(item.Backdrop, "original")) + `</thumb></fanart>`)
	}
	for _, c := range item.Cast {
		b.raw("  <actor>")
		b.el("name", c.Name, 4)
		if c.Character != "" {
			b.el("role", c.Character, 4)
		}
		if c.Profile != "" {
			b.el("thumb", TmdbImage(c.Profile, "original"), 4)
		}
		b.raw("  </actor>")
	}
}

func NfoMovie(item *MediaItem) string {
	return nfoDocument("movie", func(b *nfoBuilder) {
		nfoCommon(b, item)
		if item.Runtime != nil {
			b.el("runtime", strconv.Itoa(*item.Runtime), 2)
		}
		if item.Certification != "" {
			b.el("mpaa", item.Certification, 2)
		}
	})
}

func NfoTvShow(item *MediaItem) string {
	return nfoDocument("tvshow", func(b *nfoBuilder) {
		nfoCommon(b, item)
		for _, s := range item.Seasons {
			if s.Poster != "" {
				b.raw(fmt.Sprintf(`  <thumb aspect="poster" type="season" season="%d">%s</thumb>`, s.Number, xmlEsc(TmdbImage(s.Poster, "original"))))
			}
		}
	})
}

func NfoEpisode(show *MediaItem, ep *Episode) string {
	return nfoDocument("episodedetails", func(b *nfoBuilder) {
		title := ep.Title
		if title == "" {
			title = fmt.Sprintf("第 %d 集", ep.Episode)
		}
		b.el("title", title, 2)
		b.el("showtitle", show.Title, 2)
		b.el("season", strconv.Itoa(ep.Season), 2)
		b.el("episode", strconv.Itoa(ep.Episode), 2)
		if ep.Overview != "" {
			b.el("plot", ep.Overview, 2)
		}
		if ep.AirDate != "" {
			b.el("aired", ep.AirDate, 2)
		}
		if ep.Runtime != nil {
			b.el("runtime", strconv.Itoa(*ep.Runtime), 2)
		}
		if ep.Still != "" {
			b.el("thumb", TmdbImage(ep.Still, "original"), 2)
		}
	})
}
