package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/mmooyyii/arisa/scraper/quark"
)

// Service 是常驻服务: 盯着夸克的「最近」发现新转存/上传的内容, 增量扫描, 规则能定的直接定,
// 其余交给 AI, AI 拿不准的放进待确认等用户在网页上处理, 最后生成索引和改名计划.
//
// 重活 (扫描、AI、生成索引、改名) 都在一个工作 goroutine 里排队做, 不会互相踩.
type Service struct {
	home       string
	ConfigFile string
	state      *StateStore

	tmdbFactory    func(Config) TmdbAPI
	bangumiFactory func(Config) BangumiAPI
	imagesFactory  func(Config) *Images
	agentFactory   func(Config) AgentFunc

	quark   *LazyDrive
	Drive   *CachingDrive
	Store   *MappingStore
	Review  *ReviewStore
	Updater *Updater

	planFile, watchFile string

	mu          sync.Mutex
	queue       []Job
	busy        string
	loginError  string
	configError string
	lastScan    string
	lastBuild   string
	lastReport  *BuildReport
	lastScanRes *Scan
	pendingN    *int
	costDay     string
	costToday   float64
	activity    []string

	pendingCache struct {
		scan    *Scan
		mapping Mapping
		works   []PendingWork
	}
	itemCount struct {
		mtime time.Time
		n     int
	}
}

// AgentFunc 跑一批 AI 识别.
type AgentFunc func([]PendingWork) AgentResult

type ServiceOptions struct {
	// 测试时替换掉夸克等外部服务.
	DriveFactory   func(Config) (Drive, error)
	TmdbFactory    func(Config) TmdbAPI
	BangumiFactory func(Config) BangumiAPI
	ImagesFactory  func(Config) *Images
	AgentFactory   func(Config) AgentFunc
}

func NewService(home, configFile string, state *StateStore, opt ServiceOptions) *Service {
	s := &Service{home: home, ConfigFile: configFile, state: state}
	s.tmdbFactory = opt.TmdbFactory
	if s.tmdbFactory == nil {
		s.tmdbFactory = func(c Config) TmdbAPI {
			if strings.TrimSpace(c.Tmdb.APIKey) == "" {
				return nil
			}
			return NewTmdb(c.Tmdb.APIKey, httpClient(c.Tmdb.Proxy), filepath.Join(home, "cache/tmdb"))
		}
	}
	s.bangumiFactory = opt.BangumiFactory
	if s.bangumiFactory == nil {
		s.bangumiFactory = func(c Config) BangumiAPI {
			return NewBangumi(httpClient(c.Bangumi.Proxy), filepath.Join(home, "cache/bangumi"))
		}
	}
	s.imagesFactory = opt.ImagesFactory
	if s.imagesFactory == nil {
		s.imagesFactory = func(c Config) *Images {
			return NewImages(httpClient(c.Tmdb.Proxy), filepath.Join(home, "cache/images"))
		}
	}
	s.agentFactory = opt.AgentFactory
	if s.agentFactory == nil {
		s.agentFactory = func(c Config) AgentFunc {
			if !c.Agent.On() {
				return nil
			}
			return NewAgentRunner(c.Agent, home).Run
		}
	}
	driveFactory := opt.DriveFactory
	if driveFactory == nil {
		driveFactory = func(c Config) (Drive, error) {
			st, err := state.Load()
			if err != nil {
				return nil, err
			}
			if st.Cookie == "" {
				return nil, &LoginExpiredError{"没有登录"}
			}
			client := quark.NewWebClient(st.Cookie, func(ck string) { _ = state.Update(func(s *State) { s.Cookie = ck }) })
			return NewQuarkDrive(client, c.RateLimit), nil
		}
	}
	s.quark = &LazyDrive{factory: func() (Drive, error) { return driveFactory(s.Config()) }}
	s.Drive = NewCachingDrive(s.quark, filepath.Join(home, "scan-cache.json"))
	s.Store = &MappingStore{filepath.Join(home, "mapping.json"), s.Drive}
	s.Review = &ReviewStore{filepath.Join(home, "review.json")}
	s.planFile = filepath.Join(home, "rename-plan.txt")
	s.watchFile = filepath.Join(home, "watch.json")
	s.Updater = NewUpdater(home, s.Drive, func() *http.Client { return httpClient(s.Config().Update.Proxy) }, s.Log, s.idle, func() {
		s.Log("重启")
		// launchd 的 KeepAlive 会用 ~/.arisa/current 里的新版拉起来
		os.Exit(0)
	})
	return s
}

// Config 每次都重新读配置文件, 网页上改了马上生效. 读不了时用默认值.
func (s *Service) Config() Config {
	c, err := LoadConfig(s.ConfigFile)
	if err != nil {
		s.mu.Lock()
		s.configError = err.Error()
		s.mu.Unlock()
	}
	return c
}

func (s *Service) Tmdb(c Config) TmdbAPI { return s.tmdbFactory(c) }

// ---------- 状态, 给网页看 ----------

type Status struct {
	Busy           string   `json:"busy,omitempty"`
	Queued         []string `json:"queued"`
	LoginError     string   `json:"loginError,omitempty"`
	ConfigError    string   `json:"configError,omitempty"`
	LastScan       string   `json:"lastScan,omitempty"`
	LastBuild      string   `json:"lastBuild,omitempty"`
	Items          *int     `json:"items,omitempty"`
	PendingVideos  *int     `json:"pendingVideos,omitempty"`
	PendingWorks   *int     `json:"pendingWorks,omitempty"`
	Review         int      `json:"review"`
	Renames        int      `json:"renames"`
	AgentEnabled   bool     `json:"agentEnabled"`
	AgentCostToday float64  `json:"agentCostToday"`
	Version        string   `json:"version"`
	// LatestVersion GitHub 上的最新版本, 还没检查过是空.
	LatestVersion string `json:"latestVersion,omitempty"`
}

func (s *Service) Status() Status {
	review, _ := s.Review.Load()
	renames := 0
	for _, p := range s.RenamePlans() {
		if p.Skipped == "" {
			renames++
		}
	}
	agentOn := s.Config().Agent.On()
	s.mu.Lock()
	defer s.mu.Unlock()
	st := Status{
		Busy: s.busy, Queued: []string{}, LoginError: s.loginError, ConfigError: s.configError,
		LastScan: s.lastScan, LastBuild: s.lastBuild, PendingWorks: s.pendingN,
		Review: len(review), Renames: renames, AgentEnabled: agentOn, AgentCostToday: s.costToday,
		Version: CurrentVersion(),
	}
	for _, j := range s.queue {
		st.Queued = append(st.Queued, j.Title())
	}
	if s.lastReport != nil {
		items, pending := s.lastReport.Items, s.lastReport.Pending
		st.Items, st.PendingVideos = &items, &pending
	} else {
		st.Items = s.localItemCountLocked()
	}
	if m := s.Updater.Latest(); m != nil {
		st.LatestVersion = m.Version
	}
	return st
}

func (s *Service) Activity() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.activity))
	for i := len(s.activity) - 1; i >= 0; i-- {
		out = append(out, s.activity[i])
	}
	return out
}

const timeLayout = "01-02 15:04:05"

func (s *Service) Log(msg string) {
	line := time.Now().Format(timeLayout) + " " + msg
	fmt.Println(line)
	s.mu.Lock()
	s.activity = append(s.activity, line)
	if len(s.activity) > 300 {
		s.activity = s.activity[len(s.activity)-300:]
	}
	s.mu.Unlock()
}

// ---------- 任务队列 ----------

type Job interface{ Title() string }

// ScanJob 扫描 → 自动识别 → AI → 生成索引. Full 时丢掉缓存全量扫描.
type ScanJob struct {
	Full   bool
	Reason string
}

func (j ScanJob) Title() string {
	if j.Full {
		return "全量扫描 (" + j.Reason + ")"
	}
	return "增量扫描 (" + j.Reason + ")"
}

type BuildJob struct{ Reason string }

func (j BuildJob) Title() string { return "生成索引 (" + j.Reason + ")" }

type RenameJob struct{ Fids map[string]bool }

func (j RenameJob) Title() string { return fmt.Sprintf("改名 %d 个", len(j.Fids)) }

// Submit 同类任务已经在排队就不重复加 (全量扫描可以顶掉排队中的增量扫描).
func (s *Service) Submit(job Job) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch j := job.(type) {
	case ScanJob:
		for _, q := range s.queue {
			if sj, ok := q.(ScanJob); ok && (sj.Full || !j.Full) {
				return
			}
		}
		var left []Job
		for _, q := range s.queue {
			switch q.(type) {
			case ScanJob, BuildJob:
				continue
			}
			left = append(left, q)
		}
		s.queue = left
	case BuildJob:
		for _, q := range s.queue {
			switch q.(type) {
			case ScanJob, BuildJob:
				return
			}
		}
	}
	s.queue = append(s.queue, job)
}

func (s *Service) idle() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.busy == "" && len(s.queue) == 0
}

// RunNext 处理队列里的一个任务, 返回 false 表示队列空. 测试直接调.
func (s *Service) RunNext() bool {
	s.mu.Lock()
	if len(s.queue) == 0 {
		s.mu.Unlock()
		return false
	}
	job := s.queue[0]
	s.queue = s.queue[1:]
	s.busy = job.Title()
	s.mu.Unlock()

	var err error
	func() {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("%v", r)
			}
		}()
		switch j := job.(type) {
		case ScanJob:
			err = s.pipeline(j.Full)
		case BuildJob:
			err = s.buildAll()
		case RenameJob:
			err = s.rename(j.Fids)
		}
	}()
	switch {
	case err == nil:
		s.loginRecovered()
	case isLoginExpired(err):
		s.loginExpired(err)
	default:
		s.Log(job.Title() + " 失败: " + err.Error())
	}
	s.mu.Lock()
	s.busy = ""
	s.mu.Unlock()
	return true
}

func (s *Service) loginRecovered() {
	s.mu.Lock()
	was := s.loginError
	s.loginError = ""
	s.mu.Unlock()
	if was != "" {
		s.Log("夸克登录已恢复")
	}
	_ = os.Remove(filepath.Join(s.home, "login-expired"))
}

func (s *Service) loginExpired(err error) {
	s.mu.Lock()
	was := s.loginError
	s.loginError = err.Error()
	s.mu.Unlock()
	if was == "" {
		s.Log("夸克登录失效: " + err.Error())
	}
	_ = os.WriteFile(filepath.Join(s.home, "login-expired"), []byte(time.Now().Format(time.RFC3339)+" "+err.Error()+"\n"), 0o644)
}

func (s *Service) LoginError() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.loginError
}

// ---------- 流水线 ----------

func (s *Service) scraper(c Config) *Scraper {
	tmdb := s.tmdbFactory(c)
	s.mu.Lock()
	if tmdb == nil {
		s.configError = "没有 TMDB API Key, 填到 " + s.ConfigFile + " 的 tmdb.api_key"
	} else {
		s.configError = ""
	}
	s.mu.Unlock()
	return NewScraper(c, s.Drive, tmdb, s.imagesFactory(c), s.bangumiFactory(c), s.Log)
}

func (s *Service) currentConfigError() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.configError
}

func (s *Service) scan(sc *Scraper) (*Scan, error) {
	scan, err := sc.Scan()
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.lastScanRes = scan
	s.mu.Unlock()
	_ = s.Drive.Save()
	return scan, nil
}

func (s *Service) pipeline(full bool) error {
	c := s.Config()
	if full {
		s.Drive.InvalidateAll()
		ws := s.watchState()
		ws.LastFullScan = time.Now().Format("2006-01-02")
		s.saveWatch(ws)
	}
	sc := s.scraper(c)
	scan, err := s.scan(sc)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.lastScan = time.Now().Format(timeLayout)
	s.mu.Unlock()
	if e := s.currentConfigError(); e != "" {
		s.Log(e)
		return nil
	}
	auto, err := sc.AutoDecide(scan, s.Store)
	if err != nil {
		return err
	}
	if auto > 0 {
		s.Log(fmt.Sprintf("规则自动识别 %d 部", auto))
	}
	if agent := s.agentFactory(c); agent != nil {
		if err := s.runAgent(sc, scan, c.Agent, agent); err != nil {
			return err
		}
	}
	return s.finish(sc, scan)
}

// finish 更新待识别、待确认, 生成索引.
func (s *Service) finish(sc *Scraper, scan *Scan) error {
	m, err := s.Store.Load()
	if err != nil {
		return err
	}
	left, err := sc.Pending(scan, m, false, nil)
	if err != nil {
		return err
	}
	keys := map[string]bool{}
	for _, w := range left {
		keys[w.Key] = true
	}
	s.Review.Retain(keys)
	n := len(left)
	s.mu.Lock()
	s.pendingN = &n
	s.mu.Unlock()
	return s.build(sc, scan)
}

func (s *Service) pendingNow(sc *Scraper, scan *Scan, withCandidates bool, only map[string]bool) ([]PendingWork, error) {
	m, err := s.Store.Load()
	if err != nil {
		return nil, err
	}
	return sc.Pending(scan, m, withCandidates, only)
}

// runAgent 分批交给 AI, 直到没有新的待识别 (已在待确认里的不再交给 AI).
func (s *Service) runAgent(sc *Scraper, scan *Scan, c AgentConfig, agent AgentFunc) error {
	tried := map[string]bool{}
	for {
		skip := s.Review.Keys()
		all, err := s.pendingNow(sc, scan, false, nil)
		if err != nil {
			return err
		}
		var todo []PendingWork
		for _, w := range all {
			if !skip[w.Key] && !tried[w.Key] {
				todo = append(todo, w)
			}
		}
		keys := batchKeys(todo, c.BatchSize)
		if len(keys) == 0 {
			return nil
		}
		for k := range keys {
			tried[k] = true
		}
		batch, err := s.pendingNow(sc, scan, true, keys)
		if err != nil {
			return err
		}
		// decide 命令从这里查路径
		_ = os.WriteFile(filepath.Join(s.home, "pending.json"), prettyJSON(batch), 0o644)
		s.Log(fmt.Sprintf("交给 AI 识别 %d 部", len(batch)))
		result := agent(batch)
		cost := ""
		if result.CostCny != nil {
			s.addCost(*result.CostCny)
			cost = fmt.Sprintf(" 花费 ¥%.3f", *result.CostCny)
		}
		status := "出错"
		if result.Ok {
			status = "完成"
		}
		firstLine, _, _ := strings.Cut(result.Summary, "\n")
		s.Log("AI " + status + cost + ": " + truncate(firstLine, 200))

		// AI 没处理的放进待确认, 免得下次又交给它
		still, err := s.pendingNow(sc, scan, false, nil)
		if err != nil {
			return err
		}
		stillKeys := map[string]bool{}
		for _, w := range still {
			stillKeys[w.Key] = true
		}
		asked := s.Review.Keys()
		unhandled := 0
		for _, w := range batch {
			if !stillKeys[w.Key] || asked[w.Key] {
				continue
			}
			reason, by := "AI 没有给出结论", "unhandled"
			if !result.Ok {
				reason, by = "AI 运行出错: "+truncate(result.Summary, 100), "error"
			}
			_ = s.Review.Add(ReviewEntry{Work: w, Reason: reason, By: by})
			unhandled++
		}
		if unhandled > 0 {
			s.Log(fmt.Sprintf("%d 部 AI 没处理, 放进待确认", unhandled))
		}
		if !result.Ok {
			return nil
		}
	}
}

// batchKeys 取一批作品, 同一个顶层目录 (同一部作品的不同季、版本) 的放在同一批, AI 才能前后一致地映射.
// 一个顶层目录本身超过批量时整个放进一批.
func batchKeys(works []PendingWork, size int) map[string]bool {
	var order []string
	groups := map[string][]PendingWork{}
	for _, w := range works {
		g := w.Key
		if len(w.Dirs) > 0 {
			g = w.Dirs[0].Fid
		}
		if _, ok := groups[g]; !ok {
			order = append(order, g)
		}
		groups[g] = append(groups[g], w)
	}
	keys := map[string]bool{}
	n := 0
	for _, g := range order {
		if n > 0 && n+len(groups[g]) > size {
			break
		}
		for _, w := range groups[g] {
			keys[w.Key] = true
		}
		n += len(groups[g])
	}
	return keys
}

func (s *Service) addCost(cny float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	today := time.Now().Format("2006-01-02")
	if today != s.costDay {
		s.costDay, s.costToday = today, 0
	}
	s.costToday += cny
}

func (s *Service) buildAll() error {
	sc := s.scraper(s.Config())
	if e := s.currentConfigError(); e != "" {
		s.Log(e)
		return nil
	}
	scan, err := s.scan(sc)
	if err != nil {
		return err
	}
	return s.finish(sc, scan)
}

func (s *Service) build(sc *Scraper, scan *Scan) error {
	m, err := s.Store.Load()
	if err != nil {
		return err
	}
	lib, report, err := sc.Build(scan, m, true)
	if err != nil {
		return err
	}
	// 本地留一份, 方便查看
	_ = writeFileAtomic(filepath.Join(s.home, "library.json.gz"), EncodeLibrary(lib), 0o644)
	if err := s.Store.Backup(); err != nil {
		s.Log("备份映射表失败: " + err.Error())
	}
	var plan strings.Builder
	plan.WriteString("# 改名计划, 网页上勾选确认, 或运行 arisa-scraper rename --apply\n")
	plan.WriteString("# fid\t父目录fid\t父目录\t原名\t新名\tTMDB\t跳过原因\n")
	for _, r := range report.Renames {
		plan.WriteString(r.Line() + "\n")
	}
	_ = os.WriteFile(s.planFile, []byte(plan.String()), 0o644)
	_ = s.Drive.Save()
	s.mu.Lock()
	s.lastReport = report
	s.lastBuild = time.Now().Format(timeLayout)
	s.mu.Unlock()
	msg := fmt.Sprintf("索引: %d 部作品, %d 个视频待识别", report.Items, report.Pending)
	if report.IndexFile != "" {
		msg += ", 已更新 " + report.IndexFile
	} else {
		msg += ", 没有变化"
	}
	s.Log(msg)
	return nil
}

func (s *Service) RenamePlans() []RenamePlan {
	data, err := os.ReadFile(s.planFile)
	if err != nil {
		return nil
	}
	var out []RenamePlan
	for _, line := range strings.Split(strings.TrimRight(string(data), "\n"), "\n") {
		if p := ParseRenamePlan(line); p != nil {
			out = append(out, *p)
		}
	}
	return out
}

func (s *Service) rename(fids map[string]bool) error {
	sc := s.scraper(s.Config())
	var plans []RenamePlan
	for _, p := range s.RenamePlans() {
		if fids[p.Fid] {
			plans = append(plans, p)
		}
	}
	done, skipped, err := sc.ApplyRenames(plans)
	if err != nil {
		return err
	}
	msg := fmt.Sprintf("改名 %d 个", done)
	if len(skipped) > 0 {
		msg += fmt.Sprintf(", 跳过 %d 个", len(skipped))
	}
	s.Log(msg)
	for _, k := range skipped {
		s.Log("  跳过 " + k)
	}
	if s.currentConfigError() == "" {
		return s.buildAll()
	}
	return nil
}

// ---------- 网页上的操作 ----------

func (s *Service) Decide(fid, target string, season *int, path, reviewKey string, episodeOffset *int) error {
	if !ValidTarget(target) {
		return fmt.Errorf("target 要写成 movie:ID / tv:ID / skip")
	}
	if _, err := s.Store.Update(func(m *Mapping) error {
		m.Entries[fid] = MappingEntry{Target: target, Path: path, Season: season, EpisodeOffset: episodeOffset, By: "user", At: time.Now().UnixMilli()}
		return nil
	}); err != nil {
		return err
	}
	if reviewKey != "" {
		s.Review.Remove(reviewKey)
	}
	msg := "确认 " + path + " → " + target
	if season != nil {
		msg += fmt.Sprintf(" 第 %d 季", *season)
	}
	s.Log(msg)
	s.Submit(BuildJob{"确认了 " + path})
	return nil
}

// Undecide 删掉一条映射 (认错了), 那些文件回到未识别, 下次扫描重新交给 AI.
func (s *Service) Undecide(fid string) error {
	var removed MappingEntry
	if _, err := s.Store.Update(func(m *Mapping) error {
		e, ok := m.Entries[fid]
		if !ok {
			return fmt.Errorf("映射表里没有 %s", fid)
		}
		removed = e
		delete(m.Entries, fid)
		return nil
	}); err != nil {
		return err
	}
	s.Log("撤销 " + removed.Path + " → " + removed.Target)
	s.Submit(ScanJob{false, "撤销了 " + removed.Path})
	return nil
}

// PendingWorks 还没识别的作品 (按最近一次扫描算), 没扫描过时是 nil.
func (s *Service) PendingWorks() ([]PendingWork, error) {
	s.mu.Lock()
	scan := s.lastScanRes
	s.mu.Unlock()
	if scan == nil {
		return nil, nil
	}
	m, err := s.Store.Load()
	if err != nil {
		return nil, err
	}
	// 会读 NFO, 网页每几秒刷新一次, 扫描结果和映射表都没变时用上次算的
	s.mu.Lock()
	if s.pendingCache.scan == scan && s.pendingCache.mapping.equal(m) {
		works := s.pendingCache.works
		s.mu.Unlock()
		return works, nil
	}
	s.mu.Unlock()
	works, err := NewScraper(s.Config(), s.Drive, nil, nil, nil, nil).Pending(scan, m, false, nil)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.pendingCache.scan, s.pendingCache.mapping, s.pendingCache.works = scan, m, works
	s.mu.Unlock()
	return works, nil
}

// localItemCountLocked 刚启动还没生成过索引时, 作品数从本地那份片库读, 按修改时间缓存.
func (s *Service) localItemCountLocked() *int {
	st, err := os.Stat(filepath.Join(s.home, "library.json.gz"))
	if err != nil {
		return nil
	}
	if st.ModTime().Equal(s.itemCount.mtime) {
		n := s.itemCount.n
		return &n
	}
	lib := s.Library()
	if lib == nil {
		return nil
	}
	s.itemCount.mtime, s.itemCount.n = st.ModTime(), len(lib.Items)
	n := len(lib.Items)
	return &n
}

// Library 最近一次生成的片库 (本地那份).
func (s *Service) Library() *Library {
	data, err := os.ReadFile(filepath.Join(s.home, "library.json.gz"))
	if err != nil {
		return nil
	}
	lib, err := DecodeLibrary(data)
	if err != nil {
		return nil
	}
	return lib
}

// Retry 从待确认移出, 下次扫描重新交给 AI.
func (s *Service) Retry(key string) {
	if s.Review.Remove(key) {
		s.Submit(ScanJob{false, "重新识别"})
	}
}

func (s *Service) SetCookie(cookie string) error {
	cookie = strings.TrimSpace(cookie)
	if _, err := quark.NewWebClient(cookie, nil).Member(); err != nil {
		return err
	}
	if err := s.state.Update(func(st *State) { st.Cookie = cookie }); err != nil {
		return err
	}
	s.quark.Reset()
	s.mu.Lock()
	s.loginError = ""
	s.mu.Unlock()
	_ = os.Remove(filepath.Join(s.home, "login-expired"))
	s.Log("已更新夸克登录")
	s.Submit(ScanJob{false, "重新登录"})
	return nil
}

// ---------- 盯网盘 ----------

type watchState struct {
	LastSeen     int64  `json:"lastSeen,omitempty"`
	LastFullScan string `json:"lastFullScan,omitempty"`
	LastRecycled int64  `json:"lastRecycled,omitempty"`
}

func (s *Service) watchState() watchState {
	var ws watchState
	if data, err := os.ReadFile(s.watchFile); err == nil {
		_ = json.Unmarshal(data, &ws)
	}
	return ws
}

func (s *Service) saveWatch(ws watchState) { _ = writeFileAtomic(s.watchFile, prettyJSON(ws), 0o644) }

// ignoredSources 不改变网盘内容的动态.
var ignoredSources = map[string]bool{"PLAY": true, "VIEW_FILE": true}

// isOwnFile 自己写的 NFO、索引、映射表备份、TV 的观看进度, 它们的增删不用触发扫描.
func isOwnFile(name string) bool {
	return strings.HasSuffix(strings.ToLower(name), ".nfo") || strings.HasPrefix(name, "library-") ||
		strings.HasPrefix(name, "mapping") || strings.HasPrefix(name, "progress-")
}

// Poll 看一次「最近」: 有新的转存/上传落在扫描过的目录里, 就让这些目录的缓存失效并排一次增量扫描.
// 夸克的 Cookie 每次请求都会续期, 这里定时请求也起到保活作用.
func (s *Service) Poll() error {
	records, err := s.Drive.Recent()
	var recycled []quark.RecycledFile
	if err == nil {
		recycled, err = s.Drive.Recycled()
	}
	if err != nil {
		if isLoginExpired(err) {
			s.loginExpired(err)
			return nil
		}
		return err
	}
	if s.LoginError() != "" {
		s.loginRecovered()
		s.Submit(ScanJob{false, "登录恢复"})
	}
	ws := s.watchState()
	latest := ws.LastSeen
	for _, r := range records {
		latest = max(latest, r.Time)
	}
	// 至少记成 1, 和「从没看过回收站」的 0 区分开
	latestRecycled := int64(1)
	for _, r := range recycled {
		latestRecycled = max(latestRecycled, r.Time)
	}
	latestRecycled = max(latestRecycled, ws.LastRecycled)
	if ws.LastSeen == 0 {
		// 第一次运行只记下位置, 启动时本来就会扫描
		ws.LastSeen, ws.LastRecycled = latest, latestRecycled
		s.saveWatch(ws)
		return nil
	}

	// 转存、上传: 父目录重新列; 新出现的目录本来就不在缓存里, 扫描时会列
	var added []quark.File
	for _, r := range records {
		if r.Time > ws.LastSeen && !ignoredSources[r.Source] {
			for _, f := range r.Files {
				if !isOwnFile(f.Name) {
					added = append(added, f)
				}
			}
		}
	}
	touched := 0
	for _, f := range added {
		if f.ParentFid != "" && s.Drive.IsListed(f.ParentFid) {
			s.Drive.Invalidate(f.ParentFid)
			touched++
		}
	}
	// 删除: 「最近」里看不到, 看回收站. lastRecycled 为 0 是旧版本留下的状态, 只记位置
	var deleted []quark.RecycledFile
	if ws.LastRecycled != 0 {
		for _, r := range recycled {
			if r.Time > ws.LastRecycled && !isOwnFile(r.Name) {
				deleted = append(deleted, r)
			}
		}
	}
	for _, f := range deleted {
		if f.ParentFid != "" && s.Drive.IsListed(f.ParentFid) {
			s.Drive.Invalidate(f.ParentFid)
			touched++
		}
		if f.IsDir {
			s.Drive.Forget(f.Fid)
		}
	}
	if touched > 0 {
		var what []string
		if len(added) > 0 {
			what = append(what, fmt.Sprintf("新增 %d 个", len(added)))
		}
		if len(deleted) > 0 {
			what = append(what, fmt.Sprintf("删除 %d 个", len(deleted)))
		}
		s.Log(fmt.Sprintf("网盘有变化: %s, 涉及 %d 个目录", strings.Join(what, ", "), touched))
		s.Submit(ScanJob{false, "网盘有变化"})
	}
	ws.LastSeen, ws.LastRecycled = latest, latestRecycled
	s.saveWatch(ws)
	return nil
}

// DailyFullScan 每天在配置的钟点全量扫一次: 移动、删除在「最近」里看不到.
func (s *Service) DailyFullScan() {
	now := time.Now()
	today := now.Format("2006-01-02")
	ws := s.watchState()
	if now.Hour() >= s.Config().Serve.FullScanHour && ws.LastFullScan != today {
		ws.LastFullScan = today
		s.saveWatch(ws)
		s.Submit(ScanJob{true, "每日"})
	}
}

// ---------- 运行 ----------

// CheckUpdate 检查更新, 网页和定时任务调.
func (s *Service) CheckUpdate() {
	c := s.Config().Update
	if err := s.Updater.Check(c.SelfUpdate, c.App); err != nil {
		s.Log("检查更新失败: " + err.Error())
	}
}

func (s *Service) Start() error {
	c := s.Config()
	_, err := os.Stat(filepath.Join(s.home, "scan-cache.json"))
	s.Submit(ScanJob{Full: os.IsNotExist(err), Reason: "启动"})
	go func() {
		for {
			if !s.RunNext() {
				time.Sleep(time.Second)
			}
		}
	}()
	go func() {
		for {
			func() {
				defer func() {
					if r := recover(); r != nil {
						s.Log(fmt.Sprintf("看网盘动态失败: %v", r))
					}
				}()
				if err := s.Poll(); err != nil {
					s.Log("看网盘动态失败: " + err.Error())
				}
				s.DailyFullScan()
			}()
			// 登录失效时没必要频繁请求
			wait := time.Duration(s.Config().Serve.PollSeconds) * time.Second
			if s.LoginError() != "" {
				wait = 10 * time.Minute
			}
			time.Sleep(wait)
		}
	}()
	go func() {
		time.Sleep(time.Minute)
		for {
			s.CheckUpdate()
			time.Sleep(time.Duration(max(1, s.Config().Update.CheckHours)) * time.Hour)
		}
	}()
	s.Log("刮削器版本 " + CurrentVersion())
	if err := StartWebServer(s, c.Serve.Port); err != nil {
		return err
	}
	s.Log(fmt.Sprintf("Arisa 刮削服务已启动, http://localhost:%d", c.Serve.Port))
	return nil
}
