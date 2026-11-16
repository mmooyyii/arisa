package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"sort"
	"strconv"
	"strings"
)

// 片库索引 (Library) 的格式, 和 library/src/main/kotlin/arisa/library/Library.kt 一一对应.
// 各版本 TV App 读这个文件, 规则见 CLAUDE.md: 只加带默认值的字段, 不删不改名不改含义.
// TV 用 kotlinx.serialization 读, 缺的字段取默认值, 所以这里默认值 (空列表、null) 一律 omitempty 不写.
// 必填字段 (CompatTest.requiredFieldsFrozen 里那些) 不能 omitempty.

// LibrarySchema 是 Library.SCHEMA.
const LibrarySchema = 1

type Library struct {
	Schema      int               `json:"schema"`
	GeneratedAt int64             `json:"generatedAt"`
	Items       []MediaItem       `json:"items,omitempty"`
	Unmatched   []MediaFile       `json:"unmatched,omitempty"`
	Collections []MediaCollection `json:"collections,omitempty"`
	// Pinyin: 搜索用的拼音, 名字 → 逐字读音, 见 Library.kt.
	Pinyin map[string]string `json:"pinyin,omitempty"`
}

type MediaCollection struct {
	ID    string   `json:"id"`
	Name  string   `json:"name"`
	Cover string   `json:"cover"`
	Items []string `json:"items"`
}

const (
	TypeMovie = "movie"
	TypeTV    = "tv"
)

type MediaItem struct {
	ID string `json:"id"`
	// Type 只能是 movie / tv, 不能加新值 (旧 App 会整个索引读失败).
	Type          string       `json:"type"`
	TmdbID        *int         `json:"tmdbId,omitempty"`
	ImdbID        string       `json:"imdbId,omitempty"`
	BangumiID     *int         `json:"bangumiId,omitempty"`
	Title         string       `json:"title"`
	OriginalTitle string       `json:"originalTitle,omitempty"`
	Aliases       []string     `json:"aliases,omitempty"`
	Year          *int         `json:"year,omitempty"`
	Overview      string       `json:"overview,omitempty"`
	Genres        []string     `json:"genres,omitempty"`
	Countries     []string     `json:"countries,omitempty"`
	Language      string       `json:"language,omitempty"`
	Rating        *float64     `json:"rating,omitempty"`
	Runtime       *int         `json:"runtime,omitempty"`
	Certification string       `json:"certification,omitempty"`
	Poster        string       `json:"poster,omitempty"`
	Backdrop      string       `json:"backdrop,omitempty"`
	Logo          string       `json:"logo,omitempty"`
	Art           Artwork      `json:"art"`
	Cast          []CastMember `json:"cast,omitempty"`
	Directors     []string     `json:"directors,omitempty"`
	Files         []MediaFile  `json:"files,omitempty"`
	Seasons       []Season     `json:"seasons,omitempty"`
	AddedAt       int64        `json:"addedAt,omitempty"`
	Match         MatchInfo    `json:"match"`
}

type Artwork struct {
	Color            string `json:"color,omitempty"`
	PosterBlurHash   string `json:"posterBlurHash,omitempty"`
	BackdropBlurHash string `json:"backdropBlurHash,omitempty"`
}

type CastMember struct {
	Name      string `json:"name"`
	Character string `json:"character,omitempty"`
	Profile   string `json:"profile,omitempty"`
}

type Season struct {
	Number   int       `json:"number"`
	Name     string    `json:"name,omitempty"`
	Overview string    `json:"overview,omitempty"`
	Poster   string    `json:"poster,omitempty"`
	Episodes []Episode `json:"episodes,omitempty"`
}

type Episode struct {
	Season   int         `json:"season"`
	Episode  int         `json:"episode"`
	Title    string      `json:"title,omitempty"`
	Overview string      `json:"overview,omitempty"`
	Still    string      `json:"still,omitempty"`
	AirDate  string      `json:"airDate,omitempty"`
	Runtime  *int        `json:"runtime,omitempty"`
	Files    []MediaFile `json:"files,omitempty"`
}

type MediaFile struct {
	Fid       string         `json:"fid"`
	Name      string         `json:"name"`
	Path      string         `json:"path"`
	Size      int64          `json:"size"`
	UpdatedAt int64          `json:"updatedAt,omitempty"`
	Duration  int            `json:"duration,omitempty"`
	Subtitles []SubtitleFile `json:"subtitles,omitempty"`
}

type SubtitleFile struct {
	Fid      string `json:"fid"`
	Name     string `json:"name"`
	Language string `json:"language,omitempty"`
}

type MatchInfo struct {
	// Source: nfo / rules / ai / manual / none, 多个来源用 + 连起来.
	Source     string  `json:"source"`
	Confidence float64 `json:"confidence"`
}

// ---------- 读写 /.arisa/library-<时间戳>.json.gz ----------

const (
	ArisaDir      = ".arisa"
	libraryPrefix = "library-"
	librarySuffix = ".json.gz"
)

func LibraryFileName(generatedAt int64) string {
	return libraryPrefix + strconv.FormatInt(generatedAt, 10) + librarySuffix
}

func IsIndexFile(name string) bool {
	return strings.HasPrefix(name, libraryPrefix) && strings.HasSuffix(name, librarySuffix)
}

func indexTime(name string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSuffix(strings.TrimPrefix(name, libraryPrefix), librarySuffix), 10, 64)
	return n
}

// LatestIndex 从文件名里挑出最新的索引, 没有返回 "".
func LatestIndex(names []string) string {
	best, bestT := "", int64(-1)
	for _, n := range names {
		if IsIndexFile(n) && indexTime(n) > bestT {
			best, bestT = n, indexTime(n)
		}
	}
	return best
}

func libraryJSON(lib *Library) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(lib)
	return bytes.TrimRight(buf.Bytes(), "\n")
}

func EncodeLibrary(lib *Library) []byte {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	_, _ = zw.Write(libraryJSON(lib))
	_ = zw.Close()
	return buf.Bytes()
}

func DecodeLibrary(data []byte) (*Library, error) {
	zr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	text, err := io.ReadAll(zr)
	if err != nil {
		return nil, err
	}
	lib := &Library{Schema: LibrarySchema}
	if err := json.Unmarshal(text, lib); err != nil {
		return nil, err
	}
	return lib, nil
}

// sameContent: 除了生成时间以外内容一样.
func sameContent(a, b *Library) bool {
	ca, cb := *a, *b
	ca.GeneratedAt, cb.GeneratedAt = 0, 0
	return bytes.Equal(libraryJSON(&ca), libraryJSON(&cb))
}

// TmdbImage 拼 TMDB 图片地址, 尺寸: w92 w154 w185 w342 w500 w780 w1280 original.
func TmdbImage(path, size string) string {
	if path == "" || strings.HasPrefix(path, "http") {
		return path
	}
	return "https://image.tmdb.org/t/p/" + size + path
}

// SearchNames 是要生成拼音的名字: 能搜的名字里含汉字的 (LibrarySearch.names).
func SearchNames(lib *Library) []string {
	set := map[string]bool{}
	add := func(s string) {
		for _, r := range s {
			if IsHan(r) {
				set[s] = true
				return
			}
		}
	}
	for _, it := range lib.Items {
		add(it.Title)
		add(it.OriginalTitle)
		for _, s := range it.Aliases {
			add(s)
		}
		for _, s := range it.Directors {
			add(s)
		}
		for _, c := range it.Cast {
			add(c.Name)
		}
		for _, s := range it.Genres {
			add(s)
		}
	}
	for _, c := range lib.Collections {
		add(c.Name)
	}
	names := make([]string, 0, len(set))
	for s := range set {
		names = append(names, s)
	}
	sort.Strings(names)
	return names
}

// AnnotatePinyin 给片库里所有含汉字的名字配上拼音.
func AnnotatePinyin(lib *Library) {
	names := SearchNames(lib)
	if len(names) == 0 {
		lib.Pinyin = nil
		return
	}
	lib.Pinyin = make(map[string]string, len(names))
	for _, n := range names {
		lib.Pinyin[n] = PinyinEncode(n)
	}
}

// ---------- 发布清单 latest.json (Release.kt) ----------

type ReleaseManifest struct {
	Version     string        `json:"version"`
	VersionCode int           `json:"versionCode"`
	PublishedAt int64         `json:"publishedAt,omitempty"`
	Notes       string        `json:"notes,omitempty"`
	Scraper     *ReleaseAsset `json:"scraper,omitempty"`
	App         *ReleaseAsset `json:"app,omitempty"`
}

type ReleaseAsset struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Sha256 string `json:"sha256"`
}

const (
	ReleaseFile   = "latest.json"
	ReleaseAppDir = "app"
	ReleaseRepo   = "mmooyyii/arisa"
	GithubLatest  = "https://github.com/" + ReleaseRepo + "/releases/latest/download/" + ReleaseFile
)

func ReleaseAssetURL(version, name string) string {
	return "https://github.com/" + ReleaseRepo + "/releases/download/v" + version + "/" + name
}

// VersionCode: "1.2.3" → 1002003; 不是 x.y.z 的 (开发版) 返回 0.
func VersionCode(v string) int {
	if strings.Contains(v, "-") {
		return 0
	}
	parts := strings.Split(v, ".")
	if len(parts) != 3 {
		return 0
	}
	code := 0
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 || n > 999 {
			return 0
		}
		code = code*1000 + n
	}
	return code
}
