package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

var nonWord = regexp.MustCompile(`[^\p{L}\p{N}|]`)

func norm(s string) string { return nonWord.ReplaceAllString(strings.ToLower(s), "") }

type fakeTmdb struct {
	movies, shows map[int]*TmdbDetails
}

func detail(id int, title, original, date string, series *TmdbCollectionRef, tv bool) *TmdbDetails {
	d := &TmdbDetails{ID: id, Overview: "…", BelongsToCollection: series}
	if tv {
		d.Name, d.OriginalName, d.FirstAirDate = title, original, date
	} else {
		d.Title, d.OriginalTitle, d.ReleaseDate = title, original, date
	}
	return d
}

func newFakeTmdb() *fakeTmdb {
	series1 := &TmdbCollectionRef{1, "流浪地球（系列）"}
	series2 := &TmdbCollectionRef{2, "独行月球（系列）"}
	dune2 := detail(693134, "沙丘2", "Dune: Part Two", "2024-02-27", nil, false)
	dune2.PosterPath, dune2.Runtime = "/p1.jpg", intp(167)
	return &fakeTmdb{
		movies: map[int]*TmdbDetails{
			693134: dune2,
			438631: detail(438631, "沙丘", "Dune", "2021-09-15", nil, false),
			// 同一个系列的两部
			535167: detail(535167, "流浪地球", "", "2019-02-05", series1, false),
			842675: detail(842675, "流浪地球2", "", "2023-01-22", series1, false),
			// 属于某个系列, 但片库里只有这一部
			9001: detail(9001, "独行月球", "", "2022-07-29", series2, false),
			9002: detail(9002, "疯狂的石头", "", "2006-06-30", nil, false),
		},
		shows: map[int]*TmdbDetails{
			209867: detail(209867, "葬送的芙莉莲", "葬送のフリーレン", "2023-09-29", nil, true),
			46298:  detail(46298, "全职猎人", "HUNTER×HUNTER", "2011-10-02", nil, true),
			2002:   detail(2002, "全职猎人", "HUNTER×HUNTER", "1999-10-16", nil, true),
			200001: detail(200001, "长相思", "", "2023-07-24", nil, true),
			221851: detail(221851, "漫长的季节", "", "2023-04-22", nil, true),
		},
	}
}

func sortedIDs(m map[int]*TmdbDetails) []int {
	var ids []int
	for id := range m {
		ids = append(ids, id)
	}
	for i := range ids {
		for j := i + 1; j < len(ids); j++ {
			if ids[j] < ids[i] {
				ids[i], ids[j] = ids[j], ids[i]
			}
		}
	}
	return ids
}

func (f *fakeTmdb) SearchMovie(q string, year *int, _ string) ([]TmdbSearchResult, error) {
	var out []TmdbSearchResult
	for _, id := range sortedIDs(f.movies) {
		d := f.movies[id]
		if !strings.Contains(norm(d.Title+"|"+d.OriginalTitle), norm(q)) || year != nil && (d.Year() == nil || *d.Year() != *year) {
			continue
		}
		out = append(out, TmdbSearchResult{ID: d.ID, Title: d.Title, OriginalTitle: d.OriginalTitle, ReleaseDate: d.ReleaseDate})
	}
	return out, nil
}

func (f *fakeTmdb) SearchTv(q string, year *int, _ string) ([]TmdbSearchResult, error) {
	var out []TmdbSearchResult
	for _, id := range sortedIDs(f.shows) {
		d := f.shows[id]
		if d.Name != q && d.OriginalName != q || year != nil && (d.Year() == nil || *d.Year() != *year) {
			continue
		}
		out = append(out, TmdbSearchResult{ID: d.ID, Name: d.Name, OriginalName: d.OriginalName, FirstAirDate: d.FirstAirDate})
	}
	return out, nil
}

func (f *fakeTmdb) Movie(id int, _ string) (*TmdbDetails, error) { return f.movies[id], nil }
func (f *fakeTmdb) Tv(id int, _ string) (*TmdbDetails, error)    { return f.shows[id], nil }
func (f *fakeTmdb) Season(_, number int, _ string) (*TmdbSeasonDetails, error) {
	s := &TmdbSeasonDetails{SeasonNumber: number, Name: "第 " + itoa(number) + " 季"}
	for i := 1; i <= 12; i++ {
		s.Episodes = append(s.Episodes, struct {
			EpisodeNumber int    `json:"episode_number"`
			SeasonNumber  int    `json:"season_number"`
			Name          string `json:"name"`
			Overview      string `json:"overview"`
			StillPath     string `json:"still_path"`
			AirDate       string `json:"air_date"`
			Runtime       *int   `json:"runtime"`
		}{EpisodeNumber: i, SeasonNumber: number, Name: "第" + itoa(i) + "集标题"})
	}
	return s, nil
}
func (f *fakeTmdb) FindByImdb(imdb string) (string, int, error) {
	if imdb == "tt0000001" {
		return TypeMovie, 438631, nil
	}
	return "", 0, nil
}

func itoa(n int) string { return strconv.Itoa(n) }

func touch(t *testing.T, root, path string, content ...string) {
	t.Helper()
	p := filepath.Join(root, path)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	c := "x"
	if len(content) > 0 {
		c = content[0]
	}
	if err := os.WriteFile(p, []byte(c), 0o644); err != nil {
		t.Fatal(err)
	}
}

type env struct {
	t       *testing.T
	root    string
	drive   *LocalDrive
	store   *MappingStore
	scraper *Scraper
}

func lib(path, typ string) LibraryConfig { return LibraryConfig{Path: path, Type: typ} }

func newEnv(t *testing.T, libs ...LibraryConfig) *env {
	root := t.TempDir()
	c := DefaultConfig()
	c.Libraries = libs
	d := &LocalDrive{root}
	return &env{t, root, d, &MappingStore{filepath.Join(t.TempDir(), "mapping.json"), d}, NewScraper(c, d, newFakeTmdb(), nil, nil, nil)}
}

func (e *env) scan() *Scan {
	s, err := e.scraper.Scan()
	if err != nil {
		e.t.Fatal(err)
	}
	return s
}

func (e *env) mapping() Mapping {
	m, err := e.store.Load()
	if err != nil {
		e.t.Fatal(err)
	}
	return m
}

// decide: LocalDrive 的 fid 就是相对路径.
func (e *env) decide(path, target string, season *int) {
	m := e.mapping()
	m.Entries[path] = MappingEntry{Target: target, Path: path, Season: season, By: "skill"}
	if err := e.store.Save(m); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) build(write bool) (*Library, *BuildReport) {
	lib, report, err := e.scraper.Build(e.scan(), e.mapping(), write)
	if err != nil {
		e.t.Fatal(err)
	}
	return lib, report
}

func (e *env) indexFiles() []string {
	entries, _ := os.ReadDir(filepath.Join(e.root, ".arisa"))
	var out []string
	for _, x := range entries {
		if IsIndexFile(x.Name()) {
			out = append(out, x.Name())
		}
	}
	return out
}

func eq[T comparable](t *testing.T, want, got T, msg ...string) {
	t.Helper()
	if want != got {
		t.Fatalf("%v: want %v, got %v", msg, want, got)
	}
}

func eqStr(t *testing.T, want, got []string, msg ...string) {
	t.Helper()
	if strings.Join(want, "\x00") != strings.Join(got, "\x00") {
		t.Fatalf("%v: want %q, got %q", msg, want, got)
	}
}

func episodesOf(it MediaItem) []int {
	var out []int
	for _, s := range it.Seasons {
		for _, e := range s.Episodes {
			out = append(out, e.Episode)
		}
	}
	return out
}

func eqInts(t *testing.T, want, got []int) {
	t.Helper()
	if len(want) != len(got) {
		t.Fatalf("want %v, got %v", want, got)
	}
	for i := range want {
		if want[i] != got[i] {
			t.Fatalf("want %v, got %v", want, got)
		}
	}
}

func byID(lib *Library) map[string]MediaItem {
	m := map[string]MediaItem{}
	for _, it := range lib.Items {
		m[it.ID] = it
	}
	return m
}

func TestDecideAndBuild(t *testing.T) {
	out := OutputIndex
	l3 := lib("/动漫", LibAuto)
	l3.Output = &out
	e := newEnv(t, lib("/电影", LibMovie), lib("/剧集", LibTV), l3)
	touch(t, e.root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv")
	touch(t, e.root, "电影/沙丘 (2021)/Dune.2021.mkv")
	touch(t, e.root, "电影/沙丘 (2021)/movie.nfo", `<movie><uniqueid type="imdb">tt0000001</uniqueid></movie>`)
	touch(t, e.root, "电影/乱七八糟/xyz123.mkv")
	touch(t, e.root, "剧集/漫长的季节 (2023)/Season 01/E01.mkv")
	touch(t, e.root, "剧集/漫长的季节 (2023)/Season 01/E02.mkv")
	touch(t, e.root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][12][1080P].mkv")
	touch(t, e.root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][13][1080P].mkv")

	// auto: 已有 NFO 的, 以及 TMDB 唯一且名字年份完全对上的
	n, err := e.scraper.AutoDecide(e.scan(), e.store)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, 3, n)
	auto := e.mapping().Entries
	eq(t, "movie:438631", auto["电影/沙丘 (2021)/Dune.2021.mkv"].Target)
	eq(t, "nfo", auto["电影/沙丘 (2021)/Dune.2021.mkv"].By)
	eq(t, "movie:693134", auto["电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv"].Target)
	eq(t, "tv:221851", auto["剧集/漫长的季节 (2023)"].Target)

	// 剩下的交给 AI: 芙莉莲没有年份, 规则不敢自动定
	pending, err := e.scraper.Pending(e.scan(), e.mapping(), true, nil)
	if err != nil {
		t.Fatal(err)
	}
	keys := map[string]PendingWork{}
	for _, w := range pending {
		keys[w.Key] = w
	}
	eq(t, 2, len(keys))
	frieren, ok := keys["动漫/葬送的芙莉莲"]
	if !ok || keys["电影/乱七八糟/xyz123.mkv"].Key == "" {
		t.Fatalf("pending: %v", keys)
	}
	eq(t, "tv", frieren.Guess)
	eq(t, "tv:209867", frieren.Candidates[0].Target)
	e.decide("动漫/葬送的芙莉莲", "tv:209867", nil)
	e.decide("电影/乱七八糟/xyz123.mkv", Skip, nil)

	library, report := e.build(true)
	items := byID(library)
	eq(t, 4, len(items))
	for _, id := range []string{"movie:693134", "movie:438631", "tv:221851", "tv:209867"} {
		if _, ok := items[id]; !ok {
			t.Fatalf("缺 %s", id)
		}
	}
	eqInts(t, []int{1, 2}, episodesOf(items["tv:221851"]))
	eqInts(t, []int{12, 13}, episodesOf(items["tv:209867"]))
	eq(t, TypeTV, items["tv:209867"].Type)
	// skip 的不入库, 也不算待识别
	eq(t, 0, len(library.Unmatched))

	// index+nfo: 读来的 NFO 不回写, 只写索引的目录不写
	nfo, _ := os.ReadFile(filepath.Join(e.root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.nfo"))
	if !strings.Contains(string(nfo), `<uniqueid type="tmdb" default="true">693134</uniqueid>`) {
		t.Fatal(string(nfo))
	}
	if _, err := os.Stat(filepath.Join(e.root, "电影/沙丘 (2021)/Dune.2021.nfo")); err == nil {
		t.Fatal("不该回写读来的 NFO")
	}
	if _, err := os.Stat(filepath.Join(e.root, "剧集/漫长的季节 (2023)/tvshow.nfo")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(e.root, "动漫/葬送的芙莉莲/tvshow.nfo")); err == nil {
		t.Fatal("只写索引的片库不写 NFO")
	}
	eq(t, 2, report.NfoWritten)

	files := e.indexFiles()
	eq(t, 1, len(files))
	data, _ := os.ReadFile(filepath.Join(e.root, ".arisa", files[0]))
	decoded, err := DecodeLibrary(data)
	if err != nil {
		t.Fatal(err)
	}
	if string(libraryJSON(decoded)) != string(libraryJSON(library)) {
		t.Fatal("索引读回来不一样")
	}

	// 没有变化时不写新索引
	time.Sleep(5 * time.Millisecond)
	_, again := e.build(true)
	eq(t, "", again.IndexFile)
	eq(t, 0, again.NfoWritten)
	eq(t, 1, len(e.indexFiles()))
}

func TestIncremental(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv")
	e.decide("没看/漫长的季节 (2023)", "tv:221851", nil)
	e.build(true)

	// 已识别的剧集目录里新加一集: 不需要识别, 直接并入
	touch(t, e.root, "没看/漫长的季节 (2023)/E02.mkv")
	// 新的作品: 出现在待识别里
	touch(t, e.root, "没看/沙丘2 (2024)/Dune.Part.Two.2024.mkv")
	pending, _ := e.scraper.Pending(e.scan(), e.mapping(), false, nil)
	eq(t, 1, len(pending))
	eq(t, "没看/沙丘2 (2024)/Dune.Part.Two.2024.mkv", pending[0].Key)

	time.Sleep(5 * time.Millisecond)
	library, report := e.build(true)
	eqInts(t, []int{1, 2}, episodesOf(byID(library)["tv:221851"]))
	eq(t, 1, report.Pending)
	if report.IndexFile == "" {
		t.Fatal("应该写新索引")
	}
}

func TestSeasonOverride(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	// 目录名看不出第几季, 映射时指定
	touch(t, e.root, "没看/漫长的季节 番外/01.mkv")
	e.decide("没看/漫长的季节 番外", "tv:221851", intp(0))
	library, _ := e.build(false)
	eq(t, 0, library.Items[0].Seasons[0].Number)
}

func TestRenamePlan(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	// 单部剧, 季目录里带画质子目录
	touch(t, e.root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/4K/01 4K.mp4")
	touch(t, e.root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/1080P/02.mp4")
	// 合集: 两部同名剧, 改下一层; "剧场版" 里没识别的不动
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 1999/日语版/01.mp4")
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/01.mp4")
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 剧场版/xyz.mkv")
	// 已经是规范名字的不出现在计划里
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv")
	// 没识别
	touch(t, e.root, "没看/H 欢迎.L 1080P/1080p.mkv")
	e.decide("没看/【国产剧】长相思.全2季.国语中字.4K", "tv:200001", nil)
	e.decide("没看/Q 全职猎人 4K 合集/全职猎人 1999", "tv:2002", nil)
	e.decide("没看/Q 全职猎人 4K 合集/全职猎人 2011", "tv:46298", nil)
	e.decide("没看/漫长的季节 (2023)", "tv:221851", nil)

	_, report := e.build(false)
	byFrom := map[string]RenamePlan{}
	for _, r := range report.Renames {
		byFrom[r.From] = r
	}
	eq(t, "长相思 (2023)", byFrom["【国产剧】长相思.全2季.国语中字.4K"].To)
	eq(t, "全职猎人 (1999)", byFrom["全职猎人 1999"].To)
	eq(t, "全职猎人 (2011)", byFrom["全职猎人 2011"].To)
	eq(t, "还没识别", byFrom["全职猎人 剧场版"].Skipped)
	eq(t, "还没识别", byFrom["H 欢迎.L 1080P"].Skipped)
	if _, ok := byFrom["Q 全职猎人 4K 合集"]; ok {
		t.Fatal("合集目录不改")
	}
	if _, ok := byFrom["漫长的季节 (2023)"]; ok {
		t.Fatal("规范名字不改")
	}

	// 写成计划文件再读回来执行
	var plans []RenamePlan
	for _, r := range report.Renames {
		plans = append(plans, *ParseRenamePlan(r.Line()))
	}
	done, skipped, err := e.scraper.ApplyRenames(plans)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, 3, done, strings.Join(skipped, ";"))
	for _, p := range []string{"没看/长相思 (2023)/长相思S01.2023/4K/01 4K.mp4", "没看/Q 全职猎人 4K 合集/全职猎人 (1999)/日语版/01.mp4", "没看/H 欢迎.L 1080P"} {
		if _, err := os.Stat(filepath.Join(e.root, p)); err != nil {
			t.Fatal(err)
		}
	}
}

func collectionsByName(lib *Library) map[string]MediaCollection {
	m := map[string]MediaCollection{}
	for _, c := range lib.Collections {
		m[c.Name] = c
	}
	return m
}

func TestCollections(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	// 剧 + 剧: 名字和封面用集数最多的那部
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 1999/01.mp4")
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/01.mp4")
	touch(t, e.root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/02.mp4")
	// 全是电影: 名字用目录名, 封面用最早的
	touch(t, e.root, "没看/沙丘系列/沙丘/Dune.2021.mkv")
	touch(t, e.root, "没看/沙丘系列/沙丘2/Dune.Part.Two.2024.mkv")
	// 顶层目录里只有一部的不算合集
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv")
	e.decide("没看/Q 全职猎人 4K 合集/全职猎人 1999", "tv:2002", nil)
	e.decide("没看/Q 全职猎人 4K 合集/全职猎人 2011", "tv:46298", nil)
	e.decide("没看/沙丘系列/沙丘", "movie:438631", nil)
	e.decide("没看/沙丘系列/沙丘2", "movie:693134", nil)
	e.decide("没看/漫长的季节 (2023)", "tv:221851", nil)

	library, _ := e.build(false)
	byName := collectionsByName(library)
	eq(t, 2, len(byName))
	eq(t, "tv:46298", byName["全职猎人"].Cover)
	eqStr(t, []string{"tv:2002", "tv:46298"}, byName["全职猎人"].Items)
	eq(t, "movie:438631", byName["沙丘系列"].Cover)
	eqStr(t, []string{"movie:438631", "movie:693134"}, byName["沙丘系列"].Items)
}

func TestCollectionsFromTmdbSeries(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	// 同一个系列的两部电影放在不同的顶层目录里: 按 TMDB 的系列归到一起
	touch(t, e.root, "没看/流浪地球 (2019)/a.mkv")
	touch(t, e.root, "没看/流浪地球2 (2023)/b.mkv")
	// 系列里只有一部在片库里: 不成合集, 还可以按目录归组
	touch(t, e.root, "没看/杂/独行月球/c.mkv")
	touch(t, e.root, "没看/杂/疯狂的石头/d.mkv")
	e.decide("没看/流浪地球 (2019)", "movie:535167", nil)
	e.decide("没看/流浪地球2 (2023)", "movie:842675", nil)
	e.decide("没看/杂/独行月球", "movie:9001", nil)
	e.decide("没看/杂/疯狂的石头", "movie:9002", nil)

	library, _ := e.build(false)
	byName := collectionsByName(library)
	eq(t, 2, len(byName))
	series := byName["流浪地球（系列）"]
	eq(t, "tmdb:1", series.ID)
	eq(t, "movie:535167", series.Cover)
	eqStr(t, []string{"movie:535167", "movie:842675"}, series.Items)
	eqStr(t, []string{"movie:9002", "movie:9001"}, byName["杂"].Items)
	// 系列名也能用拼音搜
	if _, ok := library.Pinyin["流浪地球（系列）"]; !ok {
		t.Fatal("系列名没有拼音")
	}
}

func TestMappingBackup(t *testing.T) {
	e := newEnv(t, lib("/没看", LibAuto))
	_ = os.MkdirAll(filepath.Join(e.root, "没看"), 0o755)
	e.decide("a", "movie:1", nil)
	if err := e.store.Backup(); err != nil {
		t.Fatal(err)
	}
	e.decide("b", "movie:2", nil)
	if err := e.store.Backup(); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(filepath.Join(e.root, ".arisa"))
	var remote []string
	for _, x := range entries {
		if strings.HasPrefix(x.Name(), "mapping") {
			remote = append(remote, x.Name())
		}
	}
	eqStr(t, []string{MappingName}, remote)

	// 本地丢了从网盘恢复
	_ = os.Remove(e.store.file)
	m := e.mapping()
	eqStr(t, []string{"a", "b"}, sortedKeys(m.Entries))
}

// 旧版 (Kotlin) 写的映射表: 默认值不写, by 缺省是 skill.
func TestOldMappingReadable(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "mapping.json")
	_ = os.WriteFile(file, []byte(`{"entries":{"x":{"target":"tv:1","path":"/a","season":0,"at":5},"y":{"target":"skip","path":"/b","by":"user","at":6}}}`), 0o644)
	m, err := (&MappingStore{file: file}).Load()
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "skill", m.Entries["x"].By)
	eq(t, 0, *m.Entries["x"].Season)
	eq(t, "user", m.Entries["y"].By)
	if !m.Entries["y"].IsSkip() {
		t.Fatal("skip")
	}
}

// TestWriteCompatSample: ARISA_WRITE_COMPAT=<文件> 时把 Go 版写出的片库索引存成样本,
// 放进 library/src/test/resources/compat/ 给 Kotlin 的 CompatTest 读 (TV App 就是用那套代码读索引的).
func TestWriteCompatSample(t *testing.T) {
	out := os.Getenv("ARISA_WRITE_COMPAT")
	if out == "" {
		t.Skip()
	}
	e := newEnv(t, lib("/没看", LibAuto))
	touch(t, e.root, "没看/漫长的季节 (2023)/Season 01/E01.mkv")
	touch(t, e.root, "没看/漫长的季节 (2023)/Season 01/E01.chs.ass")
	touch(t, e.root, "没看/漫长的季节 (2023)/Season 01/E02.mkv")
	touch(t, e.root, "没看/流浪地球 (2019)/a.mkv")
	touch(t, e.root, "没看/流浪地球2 (2023)/b.mkv")
	touch(t, e.root, "没看/沙丘系列/沙丘/Dune.2021.mkv")
	touch(t, e.root, "没看/沙丘系列/沙丘2/Dune.Part.Two.2024.mkv")
	touch(t, e.root, "没看/乱码/xx.mkv")
	e.decide("没看/漫长的季节 (2023)", "tv:221851", nil)
	e.decide("没看/漫长的季节 (2023)/Season 01/E02.mkv", "tv:221851", intp(0))
	e.decide("没看/流浪地球 (2019)", "movie:535167", nil)
	e.decide("没看/流浪地球2 (2023)", "movie:842675", nil)
	e.decide("没看/沙丘系列/沙丘", "movie:438631", nil)
	e.decide("没看/沙丘系列/沙丘2", "movie:693134", nil)
	library, _ := e.build(false)
	library.GeneratedAt = 1763280000000
	for i := range library.Items {
		library.Items[i].AddedAt = 1763280000000 - int64(i)
	}
	data := libraryJSON(library)
	if err := os.WriteFile(out, append(data, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
}
