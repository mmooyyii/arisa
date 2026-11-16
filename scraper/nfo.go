package main

// 移植自 src/main/kotlin/arisa/scraper/Nfo.kt 的 Nfo.read (只读的部分).

import (
	"regexp"
	"strings"
)

// NfoIDs 从已有 NFO 里读出的识别信息. 原文可为 null 的字符串字段这里用 "" 表示没有.
type NfoIDs struct {
	// Root 是 movie / tvshow / episodedetails
	Root   string
	TmdbID *int
	ImdbID string
	Title  string
}

// Java 的 \s 是 [ \t\n\x0B\f\r], RE2 的 \s 不含 \x0B, 所以写全.
const nfoWS = `[ \t\n\x0B\f\r]`

var (
	nfoRoot = regexp.MustCompile(`<(movie|tvshow|episodedetails)[ \t\n\x0B\f\r>]`)
	nfoTags = map[string]*regexp.Regexp{}
)

func nfoTagRe(name string) *regexp.Regexp {
	if re, ok := nfoTags[name]; ok {
		return re
	}
	re := regexp.MustCompile(`<` + name + `>` + nfoWS + `*([^<]+?)` + nfoWS + `*</` + name + `>`)
	nfoTags[name] = re
	return re
}

func init() {
	// 预先编译好, 运行时只读 map, 并发调用安全
	for _, n := range []string{"tmdbid", "imdbid", "id", "title"} {
		nfoTagRe(n)
	}
	for _, t := range []string{"tmdb", "imdb"} {
		nfoTags["uniqueid:"+t] = regexp.MustCompile(`<uniqueid[^>]*type="` + t + `"[^>]*>` + nfoWS + `*([^<]+?)` + nfoWS + `*</uniqueid>`)
	}
}

// ReadNfo 对应 Nfo.read: 不是 Kodi NFO 时返回 nil. 只读需要的几个字段, 不引入 XML 库.
func ReadNfo(xml string) *NfoIDs {
	m := nfoRoot.FindStringSubmatch(xml)
	if m == nil {
		return nil
	}
	root := m[1]
	find := func(re *regexp.Regexp) string {
		if g := re.FindStringSubmatch(xml); g != nil {
			return g[1]
		}
		return ""
	}
	tag := func(name string) string { return find(nfoTags[name]) }
	uniqueID := func(t string) string { return find(nfoTags["uniqueid:"+t]) }

	ids := &NfoIDs{Root: root}
	tmdb := uniqueID("tmdb")
	if tmdb == "" {
		tmdb = tag("tmdbid")
	}
	if v, ok := ktToIntOrNull(tmdb); ok {
		ids.TmdbID = &v
	}
	imdb := uniqueID("imdb")
	if imdb == "" {
		imdb = tag("imdbid")
	}
	if imdb == "" {
		if id := tag("id"); strings.HasPrefix(id, "tt") {
			imdb = id
		}
	}
	ids.ImdbID = imdb
	if t := tag("title"); t != "" {
		ids.Title = nfoUnescape(t)
	}
	return ids
}

func nfoUnescape(s string) string {
	s = strings.ReplaceAll(s, "&lt;", "<")
	s = strings.ReplaceAll(s, "&gt;", ">")
	s = strings.ReplaceAll(s, "&quot;", "\"")
	return strings.ReplaceAll(s, "&amp;", "&")
}
