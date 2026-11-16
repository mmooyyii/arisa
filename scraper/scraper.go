package main

import (
	"fmt"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/mmooyyii/arisa/scraper/quark"
)

// ScannedFile 是扫描到的一个视频文件.
type ScannedFile struct {
	File quark.File
	// Path 网盘完整路径.
	Path    string
	Library LibraryConfig
	// Dirs 从片库根往下的目录, 不含片库根本身.
	Dirs []quark.File
	// Siblings 同目录的所有文件, 用来找 NFO 和字幕.
	Siblings []quark.File
}

func (f ScannedFile) ParentNames() []string {
	names := make([]string, len(f.Dirs))
	for i, d := range f.Dirs {
		names[i] = d.Name
	}
	return names
}

func (f ScannedFile) MediaFile() MediaFile {
	return MediaFile{
		Fid: f.File.Fid, Name: f.File.Name, Path: f.Path, Size: f.File.Size, UpdatedAt: f.File.UpdatedAt,
		Duration: f.File.Duration, Subtitles: SubtitlesOf(f.File, f.Siblings),
	}
}

// Scan 是一次扫描的结果.
type Scan struct {
	Files []ScannedFile
	// LibraryRoots 片库路径 (配置里写的) → 目录 fid.
	LibraryRoots map[string]string
}

// work 是规则猜出来的一部作品: 一部电影的一个文件, 或者一个剧集目录下的所有文件.
type work struct {
	kind  string
	files []ScannedFile
	// showDir 剧集的根目录, 电影为 nil.
	showDir *quark.File
	parsed  ParsedName
}

func (w *work) first() ScannedFile { return w.files[0] }

// key 做映射时用的: 剧集用目录, 电影用文件.
func (w *work) key() string {
	if w.showDir != nil {
		return w.showDir.Fid
	}
	return w.first().File.Fid
}

func (w *work) displayPath() string {
	if w.showDir == nil {
		return w.first().Path
	}
	p := w.first().Path
	if i := strings.Index(p, "/"+w.showDir.Name+"/"); i >= 0 {
		p = p[:i]
	}
	return p + "/" + w.showDir.Name
}

// PendingWork 是待识别的作品, 给 AI 和网页看. 字段名和旧版一致 (pending.json、review.json 里有).
type PendingWork struct {
	// Key 用这个 fid 做 decide; 也可以用 dirs 里任意一级目录的 fid.
	Key string `json:"key"`
	// Guess 规则猜的类型: movie / tv
	Guess     string   `json:"guess"`
	Path      string   `json:"path"`
	FileCount int      `json:"fileCount"`
	Samples   []string `json:"samples"`
	// Minutes 每个示例文件的时长 (分钟), 和 samples 一一对应, 0 表示不知道. 电影通常 80 分钟以上, 剧集单集一般不到 60 分钟.
	Minutes []int `json:"minutes"`
	// Dirs 从片库往下的各级目录 (名字和 fid), 想把整个目录映射过去时用.
	Dirs  []PendingDir `json:"dirs"`
	Rules PendingParse `json:"rules"`
	// Nfo 视频旁已有 NFO 里的 ID.
	Nfo string `json:"nfo,omitempty"`
	// Candidates 规则在 TMDB 上搜到的候选, 按得分排序.
	Candidates []PendingCandidate `json:"candidates"`
	// SampleFid 其中一个视频的 fid, 看缩略图用.
	SampleFid string `json:"sampleFid,omitempty"`
}

type PendingDir struct {
	Fid  string `json:"fid"`
	Name string `json:"name"`
}

type PendingParse struct {
	Titles  []string `json:"titles"`
	Year    *int     `json:"year,omitempty"`
	Season  *int     `json:"season,omitempty"`
	Episode *int     `json:"episode,omitempty"`
}

type PendingCandidate struct {
	Target   string  `json:"target"`
	Title    string  `json:"title"`
	Original string  `json:"original,omitempty"`
	Year     *int    `json:"year,omitempty"`
	Score    float64 `json:"score"`
	Overview string  `json:"overview,omitempty"`
}

type BuildReport struct {
	Items int
	// Pending 没有映射的视频 (待识别).
	Pending    int
	NfoWritten int
	// IndexFile 索引没变化时不上传, 为空.
	IndexFile string
	Renames   []RenamePlan
}

// RenamePlan 是改名建议: 把作品目录改成 "片名 (年份)". Skipped 不为空时只是说明为什么不改.
// 落到计划文件里, 确认后按 fid 执行, 不会改到计划之外的东西.
type RenamePlan struct {
	Fid, ParentFid, ParentPath, From, To, Tmdb, Skipped string
}

func (p RenamePlan) Line() string {
	return strings.Join([]string{p.Fid, p.ParentFid, p.ParentPath, p.From, p.To, p.Tmdb, p.Skipped}, "\t")
}

func ParseRenamePlan(line string) *RenamePlan {
	p := strings.Split(line, "\t")
	if len(p) < 7 || strings.HasPrefix(line, "#") {
		return nil
	}
	return &RenamePlan{p[0], p[1], p[2], p[3], p[4], p[5], p[6]}
}

// Scraper 是刮削器的确定性部分: 扫描网盘、按映射表生成索引/NFO、改名.
// "这是哪部片" 的判断交给 AI 或用户, 结论写进映射表.
type Scraper struct {
	config  Config
	drive   Drive
	tmdb    TmdbAPI
	images  *Images
	bangumi BangumiAPI
	log     func(string)
}

func NewScraper(config Config, drive Drive, tmdb TmdbAPI, images *Images, bangumi BangumiAPI, log func(string)) *Scraper {
	if log == nil {
		log = func(string) {}
	}
	return &Scraper{config, drive, tmdb, images, bangumi, log}
}

// ---------- 扫描 ----------

func (s *Scraper) Scan() (*Scan, error) {
	scan := &Scan{LibraryRoots: map[string]string{}}
	for _, lib := range s.config.Libraries {
		root, err := Resolve(s.drive, lib.Path)
		if err != nil {
			return nil, err
		}
		if root == nil || !root.IsDir {
			s.log("片库目录不存在: " + lib.Path)
			continue
		}
		scan.LibraryRoots[lib.Path] = root.Fid
		before := len(scan.Files)
		if err := s.walk(lib, *root, strings.TrimRight(lib.Path, "/"), nil, &scan.Files); err != nil {
			return nil, err
		}
		s.log(fmt.Sprintf("%s: %d 个视频", lib.Path, len(scan.Files)-before))
	}
	return scan, nil
}

func (s *Scraper) walk(lib LibraryConfig, dir quark.File, path string, dirs []quark.File, out *[]ScannedFile) error {
	children, err := s.drive.List(dir.Fid)
	if err != nil {
		return err
	}
	names := make([]string, len(dirs))
	for i, d := range dirs {
		names[i] = d.Name
	}
	for _, c := range children {
		if c.IsDir {
			if strings.HasPrefix(c.Name, ".") {
				continue
			}
			sub := append(append([]quark.File{}, dirs...), c)
			if err := s.walk(lib, c, path+"/"+c.Name, sub, out); err != nil {
				return err
			}
		} else if IsVideo(c.Name) && !IsExtra(c.Name, names) {
			*out = append(*out, ScannedFile{c, path + "/" + c.Name, lib, dirs, children})
		}
	}
	return nil
}

// ---------- 待识别 ----------

func unresolved(scan *Scan, m Mapping) []ScannedFile {
	var out []ScannedFile
	for _, f := range scan.Files {
		if _, e := m.Resolve(f); e == nil {
			out = append(out, f)
		}
	}
	return out
}

// byLibrary 按片库分组, 保持出现的顺序.
func byLibrary(files []ScannedFile) ([]LibraryConfig, map[string][]ScannedFile) {
	var libs []LibraryConfig
	groups := map[string][]ScannedFile{}
	for _, f := range files {
		if _, ok := groups[f.Library.Path]; !ok {
			libs = append(libs, f.Library)
		}
		groups[f.Library.Path] = append(groups[f.Library.Path], f)
	}
	return libs, groups
}

// Pending 没有映射的视频, 按规则分成作品. withCandidates 时顺带查 TMDB 给出候选.
// only 不为 nil 时只返回这些 key 的作品.
func (s *Scraper) Pending(scan *Scan, m Mapping, withCandidates bool, only map[string]bool) ([]PendingWork, error) {
	libs, groups := byLibrary(unresolved(scan, m))
	out := []PendingWork{}
	for _, lib := range libs {
		for _, w := range s.group(lib, groups[lib.Path]) {
			if only != nil && !only[w.key()] {
				continue
			}
			candidates := []PendingCandidate{}
			if withCandidates && s.tmdb != nil && len(w.parsed.Titles) > 0 {
				c, err := s.candidates(w.kind, w.parsed, s.config.Defaults.Language)
				if isTmdbAuth(err) {
					return nil, err
				}
				if err == nil {
					candidates = c
				}
			}
			display := w.displayPath()
			var samples []string
			var minutes []int
			seen := map[string]bool{}
			for _, f := range w.files {
				name := strings.TrimLeft(strings.TrimPrefix(f.Path, display), "/")
				if name == "" {
					name = f.File.Name
				}
				if seen[name] || len(samples) >= 8 {
					continue
				}
				seen[name] = true
				samples = append(samples, name)
				minutes = append(minutes, (f.File.Duration+30)/60)
			}
			dirs := []PendingDir{}
			for _, d := range w.first().Dirs {
				dirs = append(dirs, PendingDir{d.Fid, d.Name})
			}
			guess := TypeTV
			if w.kind == TypeMovie {
				guess = TypeMovie
			}
			titles := w.parsed.Titles
			if titles == nil {
				titles = []string{}
			}
			out = append(out, PendingWork{
				Key: w.key(), Guess: guess, Path: display, FileCount: len(w.files),
				Samples: samples, Minutes: minutes, Dirs: dirs,
				Rules:      PendingParse{titles, w.parsed.Year, w.parsed.Season, w.parsed.Episode},
				Nfo:        s.nfoTarget(w),
				Candidates: candidates,
				SampleFid:  w.first().File.Fid,
			})
		}
	}
	return out, nil
}

// AutoDecide 只自动接受确定的: 视频旁已有 NFO 的, 以及 TMDB 上只有一个候选、片名年份完全对上的.
// 其余留给 AI. 返回新增的映射数.
func (s *Scraper) AutoDecide(scan *Scan, store *MappingStore) (int, error) {
	m, err := store.Load()
	if err != nil {
		return 0, err
	}
	added := map[string]MappingEntry{}
	now := time.Now().UnixMilli()
	libs, groups := byLibrary(unresolved(scan, m))
	for _, lib := range libs {
		eff := s.config.Effective(lib)
		for _, w := range s.group(lib, groups[lib.Path]) {
			if eff.ExistingNfo == NfoTrust || eff.ExistingNfo == NfoMerge {
				if nfo := s.nfoTarget(w); nfo != "" {
					added[w.key()] = MappingEntry{Target: nfo, Path: w.displayPath(), By: "nfo", At: now}
					continue
				}
			}
			if s.tmdb == nil || len(w.parsed.Titles) == 0 {
				continue
			}
			c, err := s.candidates(w.kind, w.parsed, eff.Language)
			if isTmdbAuth(err) {
				return 0, err
			}
			if err == nil && len(c) == 1 && c[0].Score >= math.Max(eff.ConfidenceThreshold, 0.97) {
				added[w.key()] = MappingEntry{Target: c[0].Target, Path: w.displayPath(), By: "rules", At: now}
			}
		}
	}
	if len(added) == 0 {
		return 0, nil
	}
	count := 0
	// 查 TMDB 期间别人可能已经改过映射表, 只补上还没有的
	_, err = store.Update(func(m *Mapping) error {
		for fid, e := range added {
			if _, ok := m.Entries[fid]; !ok {
				m.Entries[fid] = e
				count++
			}
		}
		return nil
	})
	return count, err
}

func (s *Scraper) group(lib LibraryConfig, files []ScannedFile) []*work {
	var works []*work
	type tvItem struct {
		f ScannedFile
		p ParsedName
	}
	var tvKeys []string
	tvGroups := map[string][]tvItem{}
	showDirs := map[string]*quark.File{}

	for _, f := range files {
		p := ParseName(f.File.Name, f.ParentNames())
		var isTv bool
		switch lib.Type {
		case LibTV:
			isTv = true
		case LibMovie:
			isTv = false
		default:
			isTv = p.IsEpisode()
		}
		if !isTv {
			works = append(works, &work{TypeMovie, []ScannedFile{f}, nil, p})
			continue
		}
		// 剧集根目录: tv 片库取第一层目录; auto 取最近的非季、非画质/版本目录
		var showDir *quark.File
		if lib.Type == LibTV {
			if len(f.Dirs) > 0 {
				d := f.Dirs[0]
				showDir = &d
			}
		} else {
			for i := len(f.Dirs) - 1; i >= 0; i-- {
				if SeasonOfDir(f.Dirs[i].Name) == nil && !IsNoiseDir(f.Dirs[i].Name) {
					d := f.Dirs[i]
					showDir = &d
					break
				}
			}
		}
		var key string
		if showDir != nil {
			key = showDir.Fid
		} else if len(p.Titles) > 0 {
			key = "title:" + strings.ToLower(p.Titles[0])
		} else {
			key = "title:" + f.File.Fid
		}
		if _, ok := tvGroups[key]; !ok {
			tvKeys = append(tvKeys, key)
			showDirs[key] = showDir
		}
		tvGroups[key] = append(tvGroups[key], tvItem{f, p})
	}
	for _, key := range tvKeys {
		list := tvGroups[key]
		showDir := showDirs[key]
		// 剧名以目录名为准, 没有目录时用文件名里最常见的片名
		var titles []string
		var year *int
		if showDir != nil {
			dp := ParseText(showDir.Name)
			titles, year = dp.Titles, dp.Year
		}
		if len(titles) == 0 {
			counts := map[string]int{}
			var order []string
			byKey := map[string][]string{}
			for _, it := range list {
				k := strings.Join(it.p.Titles, "\x00")
				if _, ok := counts[k]; !ok {
					order = append(order, k)
					byKey[k] = it.p.Titles
				}
				counts[k]++
			}
			best := ""
			for _, k := range order {
				if counts[k] > counts[best] || best == "" && len(order) > 0 && k == order[0] {
					best = k
				}
			}
			titles = byKey[best]
		}
		if year == nil {
			for _, it := range list {
				if it.p.Year != nil {
					year = it.p.Year
					break
				}
			}
		}
		fs := make([]ScannedFile, len(list))
		for i, it := range list {
			fs[i] = it.f
		}
		works = append(works, &work{TypeTV, fs, showDir, ParsedName{Titles: titles, Year: year}})
	}
	return works
}

func findNamed(files []quark.File, name string) *quark.File {
	for _, f := range files {
		if strings.EqualFold(f.Name, name) {
			f := f
			return &f
		}
	}
	return nil
}

// nfoTarget 视频旁已有的 NFO 里记录的作品, 如 "movie:438631", 没有返回 "".
func (s *Scraper) nfoTarget(w *work) string {
	var nfo *quark.File
	first := w.first()
	if w.kind == TypeTV {
		if w.showDir == nil {
			return ""
		}
		siblings := first.Siblings
		if len(first.Dirs) == 0 || first.Dirs[len(first.Dirs)-1].Fid != w.showDir.Fid {
			l, err := s.drive.List(w.showDir.Fid)
			if err != nil {
				return ""
			}
			siblings = l
		}
		nfo = findNamed(siblings, "tvshow.nfo")
	} else {
		base := trimExt(first.File.Name)
		if nfo = findNamed(first.Siblings, base+".nfo"); nfo == nil {
			nfo = findNamed(first.Siblings, "movie.nfo")
		}
	}
	if nfo == nil {
		return ""
	}
	data, err := s.drive.Download(nfo.Fid)
	if err != nil {
		return ""
	}
	ids := ReadNfo(string(data))
	if ids == nil {
		return ""
	}
	kind := TypeTV
	if ids.Root == "movie" {
		kind = TypeMovie
	}
	if ids.TmdbID != nil {
		return fmt.Sprintf("%s:%d", kind, *ids.TmdbID)
	}
	if ids.ImdbID == "" || s.tmdb == nil {
		return ""
	}
	k, id, err := s.tmdb.FindByImdb(ids.ImdbID)
	if err != nil || k == "" {
		return ""
	}
	return fmt.Sprintf("%s:%d", k, id)
}

// trimExt 去掉扩展名, 没有点时原样返回.
func trimExt(name string) string {
	if i := strings.LastIndex(name, "."); i >= 0 {
		return name[:i]
	}
	return name
}

// candidates 用规则解析出的片名搜 TMDB, 打分排序.
func (s *Scraper) candidates(kind string, parsed ParsedName, language string) ([]PendingCandidate, error) {
	if s.tmdb == nil {
		return nil, nil
	}
	var order []int
	seen := map[int]TmdbSearchResult{}
	for _, title := range parsed.Titles {
		years := []*int{nil}
		if parsed.Year != nil {
			years = []*int{parsed.Year, nil}
		}
		for _, y := range years {
			var results []TmdbSearchResult
			var err error
			if kind == TypeMovie {
				results, err = s.tmdb.SearchMovie(title, y, language)
			} else {
				results, err = s.tmdb.SearchTv(title, y, language)
			}
			if err != nil {
				return nil, err
			}
			for _, r := range results {
				if _, ok := seen[r.ID]; !ok {
					seen[r.ID] = r
					order = append(order, r.ID)
				}
			}
			if len(results) > 0 {
				break
			}
		}
	}
	queries := map[string]bool{}
	for _, t := range parsed.Titles {
		queries[matchNormalize(t)] = true
	}
	year := parsed.Year
	var out []PendingCandidate
	for _, id := range order {
		c := seen[id]
		cYear := c.Year()
		exact := queries[matchNormalize(c.DisplayTitle())] || c.DisplayOriginal() != "" && queries[matchNormalize(c.DisplayOriginal())]
		var score float64
		switch {
		case exact && year != nil && cYear != nil && *cYear == *year:
			score = 0.97
		case exact && year != nil && cYear != nil && abs(*cYear-*year) <= 1:
			score = 0.9
		case exact && (year == nil || cYear == nil):
			score = 0.8
		case exact:
			score = 0.5
		case year == nil || cYear == nil || *cYear == *year:
			score = 0.4
		default:
			score = 0.2
		}
		original := c.DisplayOriginal()
		if original == c.DisplayTitle() {
			original = ""
		}
		out = append(out, PendingCandidate{fmt.Sprintf("%s:%d", kind, c.ID), c.DisplayTitle(), original, cYear, score, truncate(c.Overview, 80)})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	if len(out) > 6 {
		out = out[:6]
	}
	return out, nil
}

// matchNormalize: 小写, 去掉空白和标点 (比较片名用).
func matchNormalize(s string) string {
	var sb strings.Builder
	for _, r := range strings.ToLower(s) {
		if r == ' ' || r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || isASCIIPunct(r) ||
			strings.ContainsRune("·：:，,。！!？?'’\"“”", r) {
			continue
		}
		sb.WriteRune(r)
	}
	return sb.String()
}

// ---------- 生成索引 ----------

type fileEntry struct {
	f ScannedFile
	e MappingEntry
}

// Build 按映射表生成索引: 每个文件找到它的映射, 同一作品的文件合并成一个条目.
// TMDB 和图片都有磁盘缓存, 只有新作品才真正发请求; 索引内容没变就不上传.
func (s *Scraper) Build(scan *Scan, m Mapping, write bool) (*Library, *BuildReport, error) {
	if s.tmdb == nil {
		return nil, nil, fmt.Errorf("生成索引需要 TMDB")
	}
	var targets []string
	byTarget := map[string][]fileEntry{}
	unmatched := []MediaFile{}
	for _, f := range scan.Files {
		_, e := m.Resolve(f)
		if e == nil {
			unmatched = append(unmatched, f.MediaFile())
			continue
		}
		if e.IsSkip() {
			continue
		}
		if _, ok := byTarget[e.Target]; !ok {
			targets = append(targets, e.Target)
		}
		byTarget[e.Target] = append(byTarget[e.Target], fileEntry{f, *e})
	}

	var items []MediaItem
	filesOf := map[string][]ScannedFile{}
	// 电影在 TMDB 上属于哪个系列, 合集按它归组
	seriesOf := map[string]TmdbCollectionRef{}
	for _, target := range targets {
		list := byTarget[target]
		kind, id, _ := list[0].e.Tmdb()
		eff := s.config.Effective(list[0].f.Library)
		var sources []string
		for _, fe := range list {
			if !contains(sources, fe.e.By) {
				sources = append(sources, fe.e.By)
			}
		}
		source := strings.Join(sources, "+")
		files := make([]ScannedFile, len(list))
		for i, fe := range list {
			files[i] = fe.f
		}
		var bgm *int
		for _, fe := range list {
			if fe.e.Bangumi != nil {
				bgm = fe.e.Bangumi
				break
			}
		}
		item, err := func() (*MediaItem, error) {
			var item *MediaItem
			var err error
			if kind == TypeMovie {
				item, err = s.buildMovie(id, files, eff, source)
			} else {
				item, err = s.buildTv(id, list, eff, source)
			}
			if err != nil {
				return nil, err
			}
			item = s.withBangumi(item, bgm, eff)
			if kind == TypeMovie {
				d, err := s.details(kind, id, eff)
				if err != nil {
					return nil, err
				}
				if d.BelongsToCollection != nil {
					seriesOf[item.ID] = *d.BelongsToCollection
				}
			}
			return item, nil
		}()
		if isTmdbAuth(err) {
			return nil, nil, err
		}
		if err != nil {
			s.log(fmt.Sprintf("获取详情失败 %s: %v", target, err))
			for _, f := range files {
				unmatched = append(unmatched, f.MediaFile())
			}
			continue
		}
		items = append(items, *item)
		filesOf[item.ID] = files
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].AddedAt > items[j].AddedAt })

	previous, _ := s.loadLatestIndex()
	collections := s.collections(items, filesOf, seriesOf)
	lib := &Library{Schema: LibrarySchema, GeneratedAt: time.Now().UnixMilli(), Items: items, Unmatched: unmatched, Collections: collections}
	AnnotatePinyin(lib)
	s.log(fmt.Sprintf("%d 部作品, %d 个合集, %d 个视频待识别", len(items), len(collections), len(unmatched)))

	renames, err := s.planRenames(scan, m, items)
	if err != nil {
		return nil, nil, err
	}
	report := &BuildReport{Items: len(items), Pending: len(unmatched), Renames: renames}
	if write {
		report.NfoWritten = s.writeNfos(scan, m, items)
		// 内容没变 (只有生成时间不同) 就不传新索引, 避免每次定时任务都写网盘
		if previous == nil || !sameContent(previous, lib) {
			name, err := s.writeIndex(lib)
			if err != nil {
				return nil, nil, err
			}
			report.IndexFile = name
		} else {
			s.log("索引没有变化")
		}
	}
	return lib, report, nil
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// byYearTitle: 年份 (没有的排最后), 再按片名.
func byYearTitle(a, b MediaItem) bool {
	switch {
	case a.Year == nil && b.Year == nil:
	case a.Year == nil:
		return false
	case b.Year == nil:
		return true
	case *a.Year != *b.Year:
		return *a.Year < *b.Year
	}
	return a.Title < b.Title
}

func episodeCount(it MediaItem) int {
	n := 0
	for _, s := range it.Seasons {
		n += len(s.Episodes)
	}
	return n
}

// collections 合集, 两种来源, 一部作品只进一个:
//  1. TMDB 上属于同一个系列的电影 (片库里有两部以上), 不管放在网盘的哪个目录. 名字用 TMDB 的系列名, 封面用最早的那部.
//  2. 剩下的作品里, 同一个顶层目录里有多部的 (和改名计划里的「合集」是同一个判断). TMDB 的系列只管电影,
//     一部动画的正篇、新编集版、剧场版放在一个目录里的情况靠这一条.
//     名字和封面用合集里集数最多的剧; 全是电影时名字用目录名, 封面用最早的那部.
func (s *Scraper) collections(items []MediaItem, filesOf map[string][]ScannedFile, seriesOf map[string]TmdbCollectionRef) []MediaCollection {
	var seriesIDs []int
	members := map[int][]MediaItem{}
	for _, it := range items {
		ref, ok := seriesOf[it.ID]
		if !ok {
			continue
		}
		if _, ok := members[ref.ID]; !ok {
			seriesIDs = append(seriesIDs, ref.ID)
		}
		members[ref.ID] = append(members[ref.ID], it)
	}
	var out []MediaCollection
	inSeries := map[string]bool{}
	for _, sid := range seriesIDs {
		ms := members[sid]
		if len(ms) < 2 {
			continue
		}
		ref := seriesOf[ms[0].ID]
		sorted := append([]MediaItem{}, ms...)
		sort.SliceStable(sorted, func(i, j int) bool { return byYearTitle(sorted[i], sorted[j]) })
		name := ref.Name
		if strings.TrimSpace(name) == "" {
			name = sorted[0].Title
		}
		ids := make([]string, len(sorted))
		for i, it := range sorted {
			ids[i] = it.ID
			inSeries[it.ID] = true
		}
		out = append(out, MediaCollection{fmt.Sprintf("tmdb:%d", ref.ID), name, sorted[0].ID, ids})
	}
	var rest []MediaItem
	for _, it := range items {
		if !inSeries[it.ID] {
			rest = append(rest, it)
		}
	}
	out = append(out, directoryCollections(rest, filesOf)...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

func directoryCollections(items []MediaItem, filesOf map[string][]ScannedFile) []MediaCollection {
	// 作品的文件分散在几个顶层目录时, 算在文件最多的那个里
	type top struct {
		item MediaItem
		dir  quark.File
	}
	var tops []top
	for _, it := range items {
		var order []string
		counts := map[string]int{}
		dirOf := map[string]quark.File{}
		for _, f := range filesOf[it.ID] {
			if len(f.Dirs) == 0 {
				continue
			}
			fid := f.Dirs[0].Fid
			if _, ok := counts[fid]; !ok {
				order = append(order, fid)
				dirOf[fid] = f.Dirs[0]
			}
			counts[fid]++
		}
		if len(order) == 0 {
			continue
		}
		best := order[0]
		for _, fid := range order {
			if counts[fid] > counts[best] {
				best = fid
			}
		}
		tops = append(tops, top{it, dirOf[best]})
	}
	var dirOrder []string
	groups := map[string][]top{}
	for _, t := range tops {
		if _, ok := groups[t.dir.Fid]; !ok {
			dirOrder = append(dirOrder, t.dir.Fid)
		}
		groups[t.dir.Fid] = append(groups[t.dir.Fid], t)
	}
	var out []MediaCollection
	for _, fid := range dirOrder {
		g := groups[fid]
		if len(g) < 2 {
			continue
		}
		dir := g[0].dir
		var sorted []MediaItem
		var mainTv *MediaItem
		for _, t := range g {
			sorted = append(sorted, t.item)
			if t.item.Type == TypeTV && (mainTv == nil || episodeCount(t.item) > episodeCount(*mainTv)) {
				it := t.item
				mainTv = &it
			}
		}
		sort.SliceStable(sorted, func(i, j int) bool { return byYearTitle(sorted[i], sorted[j]) })
		name, cover := dir.Name, sorted[0].ID
		if mainTv != nil {
			name, cover = mainTv.Title, mainTv.ID
		}
		ids := make([]string, len(sorted))
		for i, it := range sorted {
			ids[i] = it.ID
		}
		out = append(out, MediaCollection{"dir:" + dir.Fid, name, cover, ids})
	}
	return out
}

// tmdbAnimation 是 TMDB 的「动画」类型.
const tmdbAnimation = 16

// withBangumi 日本动画的片名用 Bangumi 的中文名 (TMDB 的中文名常是机翻或台译).
// override 是映射里指定的条目; 没指定时按原名和年份自动匹配, 匹配不上就保留 TMDB 的.
func (s *Scraper) withBangumi(item *MediaItem, override *int, eff Effective) *MediaItem {
	if s.bangumi == nil {
		return item
	}
	var subject *BangumiSubject
	var err error
	if override != nil {
		subject, err = s.bangumi.Subject(*override)
	} else {
		if item.TmdbID == nil {
			return item
		}
		movie := item.Type == TypeMovie
		var d *TmdbDetails
		d, err = s.details(item.Type, *item.TmdbID, eff)
		if err == nil {
			anime := false
			for _, g := range d.Genres {
				if g.ID == tmdbAnimation {
					anime = d.OriginalLanguage == "ja" || contains(d.OriginCountry, "JP")
				}
			}
			if !anime || d.DisplayOriginal() == "" {
				return item
			}
			subject, err = MatchBangumi(s.bangumi, d.DisplayOriginal(), d.Year(), movie)
		}
	}
	if err != nil {
		s.log(fmt.Sprintf("Bangumi 查询失败 %s: %v", item.Title, err))
		return item
	}
	if subject == nil {
		return item
	}
	title := subject.DisplayName()
	out := *item
	out.Title = title
	original := item.OriginalTitle
	if original == "" {
		original = subject.Name
	}
	if original == title {
		original = ""
	}
	out.OriginalTitle = original
	// TMDB 的中文名留着当别名, 搜台译/机翻名也能找到
	var aliases []string
	for _, a := range append([]string{item.Title}, item.Aliases...) {
		if a != title && !contains(aliases, a) {
			aliases = append(aliases, a)
		}
	}
	out.Aliases = aliases
	id := subject.ID
	out.BangumiID = &id
	return &out
}

func (s *Scraper) details(kind string, id int, eff Effective) (*TmdbDetails, error) {
	get := func(lang string) (*TmdbDetails, error) {
		if kind == TypeMovie {
			return s.tmdb.Movie(id, lang)
		}
		return s.tmdb.Tv(id, lang)
	}
	d, err := get(eff.Language)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(d.Overview) != "" || eff.FallbackLanguage == eff.Language {
		return d, nil
	}
	// 主语言没有简介时用回退语言补
	fb, err := get(eff.FallbackLanguage)
	if err != nil {
		return nil, err
	}
	out := *d
	out.Overview = fb.Overview
	return &out, nil
}

// 别名只收这些地区的: 两岸三地和新加坡的中文译名, 英文名, 日文名.
var aliasCountries = map[string]bool{"CN": true, "TW": true, "HK": true, "SG": true, "US": true, "GB": true, "JP": true}

// TMDB 剧集类型有几个没有中文翻译, 还有几个是两个类型合在一起的 (Sci-Fi & Fantasy),
// 拆成和电影一样的类型, 按类型筛选时电影和剧集才能放在一起.
var genreZh = map[string][]string{
	"Sci-Fi & Fantasy":   {"科幻", "奇幻"},
	"Action & Adventure": {"动作", "冒险"},
	"War & Politics":     {"战争", "政治"},
	"Kids":               {"儿童"},
	"News":               {"新闻"},
	"Reality":            {"真人秀"},
	"Soap":               {"肥皂剧"},
	"Talk":               {"脱口秀"},
}

// bestImage 投票最高的 (一样时取前面的).
func bestImage(list []TmdbImageInfo, keep func(TmdbImageInfo) bool) string {
	var best *TmdbImageInfo
	for i := range list {
		if keep(list[i]) && (best == nil || list[i].VoteAverage > best.VoteAverage) {
			best = &list[i]
		}
	}
	if best == nil {
		return ""
	}
	return best.FilePath
}

func (s *Scraper) base(kind string, d *TmdbDetails, eff Effective, source string, files []ScannedFile) *MediaItem {
	lang, _, _ := strings.Cut(eff.Language, "-")
	var logo, backdrop string
	if d.Images != nil {
		logos := d.Images.Logos
		logo = bestImage(logos, func(i TmdbImageInfo) bool { return i.Language != nil && *i.Language == lang })
		if logo == "" {
			logo = bestImage(logos, func(i TmdbImageInfo) bool { return i.Language != nil && *i.Language == "en" })
		}
		if logo == "" {
			logo = bestImage(logos, func(TmdbImageInfo) bool { return true })
		}
		// 背景图优先无文字的
		backdrop = bestImage(d.Images.Backdrops, func(i TmdbImageInfo) bool { return i.Language == nil })
	}
	if backdrop == "" {
		backdrop = d.BackdropPath
	}
	poster := d.PosterPath
	posterInfo := s.images.Analyze(poster, "w185")
	backdropInfo := s.images.Analyze(backdrop, "w300")

	var cert string
	for _, c := range []string{"CN", "US"} {
		if cert != "" {
			break
		}
		if kind == TypeMovie {
			if d.ReleaseDates == nil {
				continue
			}
			for _, r := range d.ReleaseDates.Results {
				if r.Country == c {
					for _, rd := range r.ReleaseDates {
						if strings.TrimSpace(rd.Certification) != "" {
							cert = rd.Certification
							break
						}
					}
					break
				}
			}
		} else if d.ContentRatings != nil {
			for _, r := range d.ContentRatings.Results {
				if r.Country == c && strings.TrimSpace(r.Rating) != "" {
					cert = r.Rating
					break
				}
			}
		}
	}

	var aliases []string
	if d.AlternativeTitles != nil {
		for _, a := range append(append([]tmdbAltTitle{}, d.AlternativeTitles.Titles...), d.AlternativeTitles.Results...) {
			t := strings.TrimSpace(a.Title)
			if aliasCountries[a.Country] && t != "" && t != d.DisplayTitle() && t != d.DisplayOriginal() && !contains(aliases, t) && len(aliases) < 10 {
				aliases = append(aliases, t)
			}
		}
	}
	var genres []string
	for _, g := range d.Genres {
		names, ok := genreZh[g.Name]
		if !ok {
			names = []string{g.Name}
		}
		for _, n := range names {
			if !contains(genres, n) {
				genres = append(genres, n)
			}
		}
	}
	countries := d.OriginCountry
	if len(countries) == 0 {
		for _, c := range d.ProductionCountries {
			countries = append(countries, c.Code)
		}
	}
	var uniq []string
	for _, c := range countries {
		if !contains(uniq, c) {
			uniq = append(uniq, c)
		}
	}
	var rating *float64
	if d.VoteAverage != nil && *d.VoteAverage > 0 {
		r := math.Floor(*d.VoteAverage*10+0.5) / 10
		rating = &r
	}
	runtime := d.Runtime
	if runtime == nil && len(d.EpisodeRunTime) > 0 {
		r := d.EpisodeRunTime[0]
		runtime = &r
	}
	var cast []CastMember
	if d.Credits != nil {
		for i, c := range d.Credits.Cast {
			if i >= 15 {
				break
			}
			cast = append(cast, CastMember{c.Name, strings.TrimSpace(c.Character), c.ProfilePath})
			if strings.TrimSpace(c.Character) == "" {
				cast[len(cast)-1].Character = ""
			} else {
				cast[len(cast)-1].Character = c.Character
			}
		}
	}
	var directors []string
	if kind == TypeMovie {
		if d.Credits != nil {
			for _, c := range d.Credits.Crew {
				if c.Job == "Director" && !contains(directors, c.Name) {
					directors = append(directors, c.Name)
				}
			}
		}
	} else {
		for _, c := range d.CreatedBy {
			directors = append(directors, c.Name)
		}
	}
	added := int64(math.MaxInt64)
	for _, f := range files {
		added = min(added, f.File.UpdatedAt)
	}
	imdb := d.ImdbID
	if imdb == "" && d.ExternalIDs != nil {
		imdb = d.ExternalIDs.ImdbID
	}
	original := d.DisplayOriginal()
	if original == d.DisplayTitle() {
		original = ""
	}
	tmdbID := d.ID
	overview := d.Overview
	if strings.TrimSpace(overview) == "" {
		overview = ""
	}
	return &MediaItem{
		ID: fmt.Sprintf("%s:%d", kind, d.ID), Type: kind, TmdbID: &tmdbID, ImdbID: imdb,
		Title: d.DisplayTitle(), OriginalTitle: original, Aliases: aliases, Year: d.Year(), Overview: overview,
		Genres: genres, Countries: uniq, Language: d.OriginalLanguage, Rating: rating, Runtime: runtime,
		Certification: cert, Poster: poster, Backdrop: backdrop, Logo: logo,
		Art: Artwork{
			Color:            firstNonEmpty(backdropInfo.Color, posterInfo.Color),
			PosterBlurHash:   posterInfo.BlurHash,
			BackdropBlurHash: backdropInfo.BlurHash,
		},
		Cast: cast, Directors: directors, AddedAt: added,
		Match: MatchInfo{source, 1.0},
	}
}

func (s *Scraper) buildMovie(id int, files []ScannedFile, eff Effective, source string) (*MediaItem, error) {
	d, err := s.details(TypeMovie, id, eff)
	if err != nil {
		return nil, err
	}
	item := s.base(TypeMovie, d, eff, source, files)
	for _, f := range files {
		item.Files = append(item.Files, f.MediaFile())
	}
	return item, nil
}

func (s *Scraper) buildTv(id int, list []fileEntry, eff Effective, source string) (*MediaItem, error) {
	d, err := s.details(TypeTV, id, eff)
	if err != nil {
		return nil, err
	}
	// 有的资源按绝对集数编号 (第二季目录里是 26~50 集), 换算成季内集数
	seasonCounts := map[int]int{}
	for _, sm := range d.Seasons {
		if sm.SeasonNumber > 0 {
			seasonCounts[sm.SeasonNumber] = sm.EpisodeCount
		}
	}
	relative := func(season int, ep *int) *int {
		count, ok := seasonCounts[season]
		if !ok || ep == nil || *ep <= count {
			return ep
		}
		offset := 0
		for i := 1; i < season; i++ {
			offset += seasonCounts[i]
		}
		if r := *ep - offset; r >= 1 && r <= count {
			return &r
		}
		return ep
	}
	// 每个文件的季/集; 映射里指定了季号的以映射为准
	type located struct {
		f       ScannedFile
		season  int
		episode *int
	}
	bySeason := map[int][]located{}
	for _, fe := range list {
		p := ParseName(fe.f.File.Name, fe.f.ParentNames())
		season := 1
		if fe.e.Season != nil {
			season = *fe.e.Season
		} else if p.Season != nil {
			season = *p.Season
		}
		var ep *int
		if p.Episode != nil {
			e := *p.Episode
			if fe.e.EpisodeOffset != nil {
				e += *fe.e.EpisodeOffset
			}
			ep = &e
		}
		bySeason[season] = append(bySeason[season], located{fe.f, season, relative(season, ep)})
	}
	numbers := make([]int, 0, len(bySeason))
	for n := range bySeason {
		numbers = append(numbers, n)
	}
	sort.Ints(numbers)
	var seasons []Season
	for _, number := range numbers {
		sd, err := s.tmdb.Season(id, number, eff.Language)
		if err != nil {
			sd = nil
		}
		type known struct {
			name, overview, still, airDate string
			runtime                        *int
		}
		knownEp := map[int]known{}
		if sd != nil {
			for _, e := range sd.Episodes {
				knownEp[e.EpisodeNumber] = known{e.Name, e.Overview, e.StillPath, e.AirDate, e.Runtime}
			}
		}
		var epOrder []string
		epFiles := map[string][]located{}
		for _, l := range bySeason[number] {
			k := "null"
			if l.episode != nil {
				k = strconv.Itoa(*l.episode)
			}
			if _, ok := epFiles[k]; !ok {
				epOrder = append(epOrder, k)
			}
			epFiles[k] = append(epFiles[k], l)
		}
		var episodes []Episode
		for _, k := range epOrder {
			fs := epFiles[k]
			e := Episode{Season: number}
			if ep := fs[0].episode; ep != nil {
				e.Episode = *ep
				if kn, ok := knownEp[*ep]; ok {
					e.Title, e.Still, e.AirDate, e.Runtime = kn.name, kn.still, kn.airDate, kn.runtime
					if strings.TrimSpace(kn.overview) != "" {
						e.Overview = kn.overview
					}
				}
			}
			for _, l := range fs {
				e.Files = append(e.Files, l.f.MediaFile())
			}
			episodes = append(episodes, e)
		}
		sort.SliceStable(episodes, func(i, j int) bool { return episodes[i].Episode < episodes[j].Episode })
		season := Season{Number: number, Episodes: episodes}
		var summaryName, summaryPoster string
		for _, sm := range d.Seasons {
			if sm.SeasonNumber == number {
				summaryName, summaryPoster = sm.Name, sm.PosterPath
				break
			}
		}
		season.Name, season.Poster = summaryName, summaryPoster
		if sd != nil {
			season.Name = firstNonEmpty(sd.Name, summaryName)
			season.Poster = firstNonEmpty(sd.PosterPath, summaryPoster)
			if strings.TrimSpace(sd.Overview) != "" {
				season.Overview = sd.Overview
			}
		}
		seasons = append(seasons, season)
	}
	files := make([]ScannedFile, len(list))
	for i, fe := range list {
		files[i] = fe.f
	}
	item := s.base(TypeTV, d, eff, source, files)
	item.Seasons = seasons
	return item, nil
}

// ---------- 改名 ----------

// planRenames 只改作品所在的顶层目录; 顶层目录里有多部作品时当合集, 改下一层每部的目录.
// 目录里有还没识别的文件、改完会重名的, 都只记录不改.
func (s *Scraper) planRenames(scan *Scan, m Mapping, items []MediaItem) ([]RenamePlan, error) {
	itemByID := map[string]*MediaItem{}
	for i := range items {
		itemByID[items[i].ID] = &items[i]
	}
	// 每个文件对应的作品, 没识别的是 "", skip 的不参与
	type entry struct {
		f      ScannedFile
		target string
	}
	type groupKey struct{ lib, top string }
	var keys []groupKey
	groups := map[groupKey][]entry{}
	for _, f := range scan.Files {
		if len(f.Dirs) == 0 {
			continue
		}
		_, e := m.Resolve(f)
		target := ""
		if e != nil {
			if e.IsSkip() {
				continue
			}
			target = e.Target
		}
		k := groupKey{strings.TrimRight(f.Library.Path, "/"), f.Dirs[0].Fid}
		if _, ok := groups[k]; !ok {
			keys = append(keys, k)
		}
		groups[k] = append(groups[k], entry{f, target})
	}
	idsOf := func(list []entry) map[string]bool {
		ids := map[string]bool{}
		for _, e := range list {
			ids[e.target] = true
		}
		return ids
	}
	nonNull := func(ids map[string]bool) int {
		n := len(ids)
		if ids[""] {
			n--
		}
		return n
	}

	var plans []RenamePlan
	plan := func(dir quark.File, parentFid, parentPath string, ids map[string]bool) {
		var item *MediaItem
		if len(ids) == 1 {
			for id := range ids {
				item = itemByID[id]
			}
		}
		skip := ""
		switch {
		case ids[""] && len(ids) > 1:
			skip = "部分文件还没识别"
		case item == nil:
			skip = "还没识别"
		}
		to, tmdb := "", ""
		if item != nil {
			to = targetName(item)
			tmdb = item.ID + " " + item.Title
		}
		if skip == "" && to == dir.Name {
			return
		}
		plans = append(plans, RenamePlan{dir.Fid, parentFid, parentPath, dir.Name, to, tmdb, skip})
	}
	for _, k := range keys {
		list := groups[k]
		top := list[0].f.Dirs[0]
		rootFid, ok := scan.LibraryRoots[k.lib]
		if !ok {
			rootFid = scan.LibraryRoots[k.lib+"/"]
		}
		ids := idsOf(list)
		if nonNull(ids) <= 1 {
			plan(top, rootFid, k.lib, ids)
			continue
		}
		// 合集: 保留合集目录, 改下一层
		var childOrder []string
		children := map[string][]entry{}
		childDir := map[string]quark.File{}
		for _, e := range list {
			if len(e.f.Dirs) < 2 {
				continue
			}
			c := e.f.Dirs[1]
			if _, ok := children[c.Fid]; !ok {
				childOrder = append(childOrder, c.Fid)
				childDir[c.Fid] = c
			}
			children[c.Fid] = append(children[c.Fid], e)
		}
		for _, fid := range childOrder {
			childIDs := idsOf(children[fid])
			// 下一层里还是多部作品的 (如 "剧场版" 目录), 不再往下改
			if nonNull(childIDs) <= 1 {
				plan(childDir[fid], top.Fid, k.lib+"/"+top.Name, childIDs)
			}
		}
	}

	// 改完会和同目录其他目录重名的不改
	var parentOrder []string
	byParent := map[string][]RenamePlan{}
	for _, p := range plans {
		if _, ok := byParent[p.ParentFid]; !ok {
			parentOrder = append(parentOrder, p.ParentFid)
		}
		byParent[p.ParentFid] = append(byParent[p.ParentFid], p)
	}
	var out []RenamePlan
	for _, parent := range parentOrder {
		group := byParent[parent]
		siblings, err := s.drive.List(parent)
		if err != nil {
			return nil, err
		}
		for _, p := range group {
			if p.Skipped == "" {
				clash := false
				for _, sib := range siblings {
					if sib.Fid != p.Fid && sib.Name == p.To {
						clash = true
					}
				}
				same := 0
				for _, q := range group {
					if q.Skipped == "" && q.To == p.To {
						same++
					}
				}
				if clash || same > 1 {
					p.Skipped = "改完会重名"
				}
			}
			out = append(out, p)
		}
	}
	return out, nil
}

// ApplyRenames 按计划改名. 执行前核对 fid 和原名, 网盘里已经变了的跳过. 返回成功数和跳过的原因.
func (s *Scraper) ApplyRenames(plans []RenamePlan) (int, []string, error) {
	done := 0
	var skipped []string
	var parentOrder []string
	byParent := map[string][]RenamePlan{}
	for _, p := range plans {
		if p.Skipped != "" {
			continue
		}
		if _, ok := byParent[p.ParentFid]; !ok {
			parentOrder = append(parentOrder, p.ParentFid)
		}
		byParent[p.ParentFid] = append(byParent[p.ParentFid], p)
	}
	for _, parent := range parentOrder {
		siblings, err := s.drive.List(parent)
		if err != nil {
			return done, skipped, err
		}
		for _, p := range byParent[parent] {
			var current *quark.File
			taken := false
			for i := range siblings {
				if siblings[i].Fid == p.Fid {
					current = &siblings[i]
				} else if siblings[i].Name == p.To {
					taken = true
				}
			}
			label := p.ParentPath + "/" + p.From
			switch {
			case current == nil:
				skipped = append(skipped, label+": 已不存在")
			case current.Name == p.To:
			case current.Name != p.From:
				skipped = append(skipped, label+": 名字已变成 "+current.Name)
			case taken:
				skipped = append(skipped, label+": 已有同名 "+p.To)
			default:
				if err := s.drive.Rename(p.Fid, p.To); err != nil {
					return done, skipped, err
				}
				s.log("改名 " + label + " → " + p.To)
				done++
			}
		}
	}
	return done, skipped, nil
}

var badNameChars = regexp.MustCompile(`[*?"<>|]`)

// targetName "片名 (年份)", 去掉网盘不允许的字符.
func targetName(item *MediaItem) string {
	t := strings.NewReplacer("/", "／", "\\", "＼", ":", "：").Replace(item.Title)
	t = strings.TrimSpace(badNameChars.ReplaceAllString(t, ""))
	if item.Year != nil {
		return fmt.Sprintf("%s (%d)", t, *item.Year)
	}
	return t
}

// ---------- 写网盘 ----------

// writeNfos NFO 写在映射所在的位置: 电影写在每个视频旁边; 剧集的 tvshow.nfo 写在映射的目录里.
// 已经存在的不覆盖 (除非 existing_nfo: overwrite), 所以增量运行时只会写新作品的.
func (s *Scraper) writeNfos(scan *Scan, m Mapping, items []MediaItem) int {
	itemByID := map[string]*MediaItem{}
	for i := range items {
		itemByID[items[i].ID] = &items[i]
	}
	written := 0
	doneDirs := map[string]bool{}
	for _, f := range scan.Files {
		keyFid, entry := m.Resolve(f)
		if entry == nil || entry.IsSkip() || entry.By == "nfo" {
			continue
		}
		eff := s.config.Effective(f.Library)
		if eff.Output == OutputIndex {
			continue
		}
		item := itemByID[entry.Target]
		if item == nil {
			continue
		}
		overwrite := eff.ExistingNfo == NfoOverwrite
		full := eff.Output == OutputIndexFull
		err := func() error {
			if item.Type == TypeMovie {
				base := trimExt(f.File.Name)
				parent := scan.LibraryRoots[f.Library.Path]
				if len(f.Dirs) > 0 {
					parent = f.Dirs[len(f.Dirs)-1].Fid
				}
				ok, err := s.put(parent, f.Siblings, base+".nfo", []byte(NfoMovie(item)), overwrite)
				if err != nil {
					return err
				}
				if ok {
					written++
				}
				if full {
					s.putImage(parent, f.Siblings, base+"-poster.jpg", item.Poster)
					s.putImage(parent, f.Siblings, base+"-fanart.jpg", item.Backdrop)
					s.putImage(parent, f.Siblings, base+"-clearlogo.png", item.Logo)
				}
				return nil
			}
			// 映射在目录上时, tvshow.nfo 写在那个目录
			var showDir *quark.File
			for i := range f.Dirs {
				if f.Dirs[i].Fid == keyFid {
					showDir = &f.Dirs[i]
					break
				}
			}
			if showDir != nil && !doneDirs[showDir.Fid] {
				doneDirs[showDir.Fid] = true
				siblings, err := s.drive.List(showDir.Fid)
				if err != nil {
					return err
				}
				ok, err := s.put(showDir.Fid, siblings, "tvshow.nfo", []byte(NfoTvShow(item)), overwrite)
				if err != nil {
					return err
				}
				if ok {
					written++
				}
				if full {
					s.putImage(showDir.Fid, siblings, "poster.jpg", item.Poster)
					s.putImage(showDir.Fid, siblings, "fanart.jpg", item.Backdrop)
					s.putImage(showDir.Fid, siblings, "clearlogo.png", item.Logo)
				}
			}
			if full && len(f.Dirs) > 0 {
				var ep *Episode
				for si := range item.Seasons {
					for ei := range item.Seasons[si].Episodes {
						for _, mf := range item.Seasons[si].Episodes[ei].Files {
							if mf.Fid == f.File.Fid && ep == nil {
								ep = &item.Seasons[si].Episodes[ei]
							}
						}
					}
				}
				if ep != nil {
					parent := f.Dirs[len(f.Dirs)-1].Fid
					base := trimExt(f.File.Name)
					ok, err := s.put(parent, f.Siblings, base+".nfo", []byte(NfoEpisode(item, ep)), overwrite)
					if err != nil {
						return err
					}
					if ok {
						written++
					}
					s.putImage(parent, f.Siblings, base+"-thumb.jpg", ep.Still)
				}
			}
			return nil
		}()
		if err != nil {
			if isLoginExpired(err) {
				s.log("写 NFO 失败 " + f.Path + ": " + err.Error())
				return written
			}
			s.log("写 NFO 失败 " + f.Path + ": " + err.Error())
		}
	}
	return written
}

// put 写文件; 已存在时默认跳过, overwrite 时先删再传 (夸克不覆盖同名文件).
func (s *Scraper) put(parent string, siblings []quark.File, name string, data []byte, overwrite bool) (bool, error) {
	var existing []string
	for _, f := range siblings {
		if f.Name == name {
			existing = append(existing, f.Fid)
		}
	}
	if len(existing) > 0 && !overwrite {
		return false, nil
	}
	if err := s.drive.Delete(existing); err != nil {
		return false, err
	}
	_, err := s.drive.Upload(parent, name, data)
	return err == nil, err
}

func (s *Scraper) putImage(parent string, siblings []quark.File, name, tmdbPath string) {
	if tmdbPath == "" || s.images == nil || findExact(siblings, name) {
		return
	}
	data, err := s.images.Download(TmdbImage(tmdbPath, "original"))
	if err != nil || data == nil {
		return
	}
	if _, err := s.drive.Upload(parent, name, data); err != nil {
		s.log("上传图片失败 " + name + ": " + err.Error())
	}
}

func findExact(files []quark.File, name string) bool {
	for _, f := range files {
		if f.Name == name {
			return true
		}
	}
	return false
}

func (s *Scraper) loadLatestIndex() (*Library, error) {
	dir, err := findDir(s.drive, s.drive.RootFid(), ArisaDir)
	if err != nil || dir == "" {
		return nil, err
	}
	files, err := s.drive.List(dir)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, f := range files {
		names = append(names, f.Name)
	}
	latest := LatestIndex(names)
	for _, f := range files {
		if f.Name == latest && latest != "" {
			data, err := s.drive.Download(f.Fid)
			if err != nil {
				return nil, err
			}
			return DecodeLibrary(data)
		}
	}
	return nil, nil
}

func digitsOf(s string) int64 {
	var sb strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			sb.WriteRune(r)
		}
	}
	n, _ := strconv.ParseInt(sb.String(), 10, 64)
	return n
}

func (s *Scraper) writeIndex(lib *Library) (string, error) {
	dir, err := findOrMkdir(s.drive, s.drive.RootFid(), ArisaDir)
	if err != nil {
		return "", err
	}
	name := LibraryFileName(lib.GeneratedAt)
	if _, err := s.drive.Upload(dir, name, EncodeLibrary(lib)); err != nil {
		return "", err
	}
	// 只保留最近几个版本
	files, err := s.drive.List(dir)
	if err != nil {
		return "", err
	}
	var idx []quark.File
	for _, f := range files {
		if IsIndexFile(f.Name) {
			idx = append(idx, f)
		}
	}
	sort.SliceStable(idx, func(i, j int) bool { return digitsOf(idx[i].Name) > digitsOf(idx[j].Name) })
	var old []string
	for i, f := range idx {
		if i >= s.config.Scan.KeepIndexes {
			old = append(old, f.Fid)
		}
	}
	if err := s.drive.Delete(old); err != nil {
		return "", err
	}
	s.log("索引已写入 /" + ArisaDir + "/" + name)
	return name, nil
}
