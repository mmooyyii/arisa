// arisa-scraper: mac mini 上的刮削器. 识别网盘里的视频、生成片库索引给 TV App 读, 带管理网页.
package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/mmooyyii/arisa/scraper/quark"
)

const usage = `arisa-scraper <命令> [参数]

识别 ("这是哪部片") 由 AI 或人判断, 这里只提供确定性的工具. 输出 JSON 的命令供 AI 调用.

登录:
  login [cookie]                不带参数: 终端里显示二维码, 用夸克 App 扫码登录; 带参数: 用网页端 Cookie 登录
  tv-login                      TV 端扫码登录 (验证 TV 接口用)
  whoami                        查看登录状态

网盘:
  ls [路径]                     列目录
  inspect <网盘目录>             只读遍历, 用规则解析每个视频
  thumbnail <网盘路径> <输出文件> 下载夸克为视频生成的缩略图 (不下载视频)
  thumbnail --fid <fid> <输出文件>  同上, 按 fid (需要常驻服务的扫描缓存)
  link <路径> / tv-link <路径>   取直链
  put <本地文件> <网盘目录> / rm <网盘路径>

识别 (JSON):
  pending [--candidates]        还没识别的作品; --candidates 顺带给出 TMDB 候选
  auto                          自动识别确定的 (已有 NFO / TMDB 唯一且名字年份完全对上)
  tmdb search <片名> [--tv|--movie] [--year 年份]
  tmdb show <movie|tv> <id>     详情, 剧集含每季集数
  bangumi search <名字>          搜 Bangumi 的动画条目 (日本动画的中文名用它的)
  decide <fid> <movie:ID|tv:ID|skip> [--season N] [--episode-offset N] [--bangumi ID] [--note 说明]
  undecide <fid>
  ask <key> --reason 原因 [--guess movie:ID|tv:ID]   拿不准, 交给用户在网页上确认
  mapping                       查看映射表
  guide                         识别规则 (服务里的 AI 用的就是它)

生成:
  build [--dry-run]             按映射表生成索引和 NFO (增量, 内容没变不上传)
  rename                        生成改名计划 ~/.arisa/rename-plan.txt (不改网盘)
  rename --apply                按计划文件改名
  serve                         常驻服务: 盯网盘变化、自动识别、生成索引, 带管理网页

更新:
  version                       当前版本
  update                        马上检查更新 (常驻服务每隔几小时会自己检查)

选项:
  --config <文件>   默认 ~/.arisa/config.yaml
`

var home = func() string {
	if h := os.Getenv("ARISA_HOME"); h != "" {
		return h
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, ".arisa")
}()

func main() {
	args := os.Args[1:]
	configFile := takeOption(&args, "--config")
	if configFile == "" {
		configFile = filepath.Join(home, "config.yaml")
	}
	if len(args) == 0 {
		fmt.Print(usage)
		return
	}
	cmd, args := args[0], args[1:]
	state := &StateStore{file: filepath.Join(home, "state.json")}
	if err := run(cmd, args, configFile, state); err != nil {
		fmt.Fprintln(os.Stderr, "错误: "+err.Error())
		if isLoginExpired(err) {
			os.Exit(3)
		}
		os.Exit(1)
	}
}

func printJSON(v any) { fmt.Println(string(prettyJSON(v))) }

// need 取第 i 个参数, 没有时报用法错误.
func need(args []string, i int, what string) (string, error) {
	if i >= len(args) {
		return "", fmt.Errorf("缺少参数: %s", what)
	}
	return args[i], nil
}

func run(cmd string, args []string, configFile string, state *StateStore) error {
	switch cmd {
	case "login":
		cookie := ""
		if len(args) > 0 {
			cookie = args[0]
		} else {
			var err error
			if cookie, err = qrLogin(); err != nil {
				return err
			}
		}
		client := quark.NewWebClient(cookie, nil)
		member, err := client.Member()
		if err != nil {
			return err
		}
		if err := state.Update(func(s *State) { s.Cookie = client.Cookie() }); err != nil {
			return err
		}
		_ = os.Remove(filepath.Join(home, "login-expired"))
		fmt.Printf("登录成功: %v\n", member["data"])
	case "tv-login":
		return tvLogin(state)
	case "whoami":
		if data, err := os.ReadFile(filepath.Join(home, "login-expired")); err == nil {
			fmt.Println("⚠️ 定时任务记录过登录失效: " + strings.TrimSpace(string(data)))
		}
		s, err := state.Load()
		if err != nil {
			return err
		}
		web := "未登录"
		if s.Cookie != "" {
			if c, err := webClient(state); err == nil {
				if m, err := c.Member(); err == nil {
					web = fmt.Sprint(m["data"])
				} else {
					web = "失效: " + err.Error()
				}
			}
		}
		fmt.Println("网页端: " + web)
		tv := "未登录"
		if s.Tv != nil && s.Tv.RefreshToken != "" {
			if c, err := tvClient(state); err == nil {
				if m, err := c.UserInfo(); err == nil {
					tv = fmt.Sprint(m["data"])
				} else {
					tv = "失效: " + err.Error()
				}
			}
		}
		fmt.Println("TV 端: " + tv)
	case "ls":
		client, err := webClient(state)
		if err != nil {
			return err
		}
		path := "/"
		if len(args) > 0 {
			path = args[0]
		}
		dir, err := client.Resolve(path)
		if err != nil {
			return err
		}
		if dir == nil {
			return errors.New("路径不存在")
		}
		list, err := client.List(dir.Fid)
		if err != nil {
			return err
		}
		for _, f := range list {
			kind, size := "-", humanSize(f.Size)
			if f.IsDir {
				kind, size = "d", ""
			}
			fmt.Printf("%s %10s  %s  (%s)\n", kind, size, f.Name, f.Fid)
		}
	case "inspect":
		path, err := need(args, 0, "网盘目录")
		if err != nil {
			return err
		}
		client, err := webClient(state)
		if err != nil {
			return err
		}
		drive := NewQuarkDrive(client, DefaultConfig().RateLimit)
		root, err := Resolve(drive, path)
		if err != nil {
			return err
		}
		if root == nil {
			return errors.New("路径不存在")
		}
		var walk func(fid, path string, dirs []string) error
		walk = func(fid, path string, dirs []string) error {
			list, err := drive.List(fid)
			if err != nil {
				return err
			}
			for _, f := range list {
				if f.IsDir {
					if err := walk(f.Fid, path+"/"+f.Name, append(append([]string{}, dirs...), f.Name)); err != nil {
						return err
					}
				} else if IsVideo(f.Name) && !IsExtra(f.Name, dirs) {
					p := ParseName(f.Name, dirs)
					title := strings.Join(p.Titles, " | ")
					if title == "" {
						title = "(无片名)"
					}
					if p.Year != nil {
						title += fmt.Sprintf(" (%d)", *p.Year)
					}
					if p.Episode != nil {
						title += fmt.Sprintf(" S%dE%d", deref(p.Season), *p.Episode)
					}
					fmt.Printf("%s\t%s/%s\n", title, path, f.Name)
				}
			}
			return nil
		}
		return walk(root.Fid, strings.TrimRight(path, "/"), nil)
	case "thumbnail":
		client, err := webClient(state)
		if err != nil {
			return err
		}
		c, _ := LoadConfig(configFile)
		drive := NewQuarkDrive(client, c.RateLimit)
		var f *quark.File
		if fid := takeOption(&args, "--fid"); fid != "" {
			// 用服务的扫描缓存找到文件所在目录, 再重新列一次拿到新的缩略图地址
			f, err = NewCachingDrive(drive, filepath.Join(home, "scan-cache.json")).Fresh(fid)
			if err != nil {
				return err
			}
			if f == nil {
				return fmt.Errorf("扫描缓存里没有 %s", fid)
			}
		} else {
			path, err := need(args, 0, "网盘路径")
			if err != nil {
				return err
			}
			args = args[1:]
			if f, err = client.Resolve(path); err != nil {
				return err
			}
			if f == nil {
				return errors.New("路径不存在")
			}
		}
		out, err := need(args, 0, "输出文件")
		if err != nil {
			return err
		}
		data, err := drive.Thumbnail(*f)
		if err != nil {
			return err
		}
		if err := os.WriteFile(out, data, 0o644); err != nil {
			return err
		}
		abs, _ := filepath.Abs(out)
		fmt.Println(abs)
	case "link":
		path, err := need(args, 0, "路径")
		if err != nil {
			return err
		}
		client, err := webClient(state)
		if err != nil {
			return err
		}
		f, err := client.Resolve(path)
		if err != nil {
			return err
		}
		if f == nil {
			return errors.New("路径不存在")
		}
		link, err := client.DownloadLink(f.Fid)
		if err != nil {
			return err
		}
		fmt.Println(link.URL)
		for _, k := range sortedKeys(link.Headers) {
			v := link.Headers[k]
			if k == "Cookie" {
				v = truncate(v, 20) + "…"
			}
			fmt.Printf("  %s: %s\n", k, v)
		}
	case "tv-link":
		path, err := need(args, 0, "路径")
		if err != nil {
			return err
		}
		web, err := webClient(state)
		if err != nil {
			return err
		}
		f, err := web.Resolve(path)
		if err != nil {
			return err
		}
		if f == nil {
			return errors.New("路径不存在")
		}
		tv, err := tvClient(state)
		if err != nil {
			return err
		}
		link, err := tv.DownloadLink(f.Fid)
		if err != nil {
			return err
		}
		fmt.Println("原文件: " + link.URL)
		if list, err := tv.StreamingLinks(f.Fid); err != nil {
			fmt.Println("转码流: " + err.Error())
		} else {
			for _, v := range list {
				fmt.Printf("转码 %s %dx%d: %s\n", v.Resolution, v.Width, v.Height, v.URL)
			}
		}
	case "put":
		local, err := need(args, 0, "本地文件")
		if err != nil {
			return err
		}
		remote, err := need(args, 1, "网盘目录")
		if err != nil {
			return err
		}
		client, err := webClient(state)
		if err != nil {
			return err
		}
		dir, err := client.Resolve(remote)
		if err != nil {
			return err
		}
		if dir == nil {
			return errors.New("网盘目录不存在")
		}
		data, err := os.ReadFile(local)
		if err != nil {
			return err
		}
		fid, err := client.Upload(dir.Fid, filepath.Base(local), data)
		if err != nil {
			return err
		}
		fmt.Println("fid=" + fid)
	case "rm":
		path, err := need(args, 0, "网盘路径")
		if err != nil {
			return err
		}
		client, err := webClient(state)
		if err != nil {
			return err
		}
		f, err := client.Resolve(path)
		if err != nil {
			return err
		}
		if f == nil {
			return errors.New("路径不存在")
		}
		if err := client.Delete([]string{f.Fid}); err != nil {
			return err
		}
		fmt.Println("已删除")
	case "parse":
		for _, a := range args {
			parts := strings.Split(a, "/")
			p := ParseName(parts[len(parts)-1], parts[:len(parts)-1])
			fmt.Printf("%s\n  → titles=%q year=%v season=%v episode=%v\n", a, p.Titles, ptr(p.Year), ptr(p.Season), ptr(p.Episode))
		}

	case "pending":
		withCandidates := removeArg(&args, "--candidates")
		ctx, err := newContext(configFile, state, withCandidates, true)
		if err != nil {
			return err
		}
		scan, err := ctx.scraper.Scan()
		if err != nil {
			return err
		}
		m, err := ctx.store.Load()
		if err != nil {
			return err
		}
		pending, err := ctx.scraper.Pending(scan, m, withCandidates, nil)
		if err != nil {
			return err
		}
		text := prettyJSON(pending)
		// decide 时从这里查路径
		_ = os.WriteFile(filepath.Join(home, "pending.json"), text, 0o644)
		fmt.Println(string(text))
	case "auto":
		ctx, err := newContext(configFile, state, true, false)
		if err != nil {
			return err
		}
		scan, err := ctx.scraper.Scan()
		if err != nil {
			return err
		}
		n, err := ctx.scraper.AutoDecide(scan, ctx.store)
		if err != nil {
			return err
		}
		fmt.Printf("自动识别 %d 部\n", n)
	case "tmdb":
		c, err := LoadConfig(configFile)
		if err != nil {
			return err
		}
		return tmdbCommand(args, c)
	case "bangumi":
		if len(args) == 0 || args[0] != "search" {
			return errors.New("用法: bangumi search <名字>")
		}
		c, err := LoadConfig(configFile)
		if err != nil {
			return err
		}
		api := NewBangumi(httpClient(c.Bangumi.Proxy), filepath.Join(home, "cache/bangumi"))
		res, err := bangumiSearchJSON(api, strings.Join(args[1:], " "))
		if err != nil {
			return err
		}
		printJSON(res)
	case "decide":
		season, err := intOption(&args, "--season")
		if err != nil {
			return err
		}
		offset, err := intOption(&args, "--episode-offset")
		if err != nil {
			return err
		}
		bgm, err := intOption(&args, "--bangumi")
		if err != nil {
			return err
		}
		note := takeOption(&args, "--note")
		fid, err := need(args, 0, "fid")
		if err != nil {
			return err
		}
		target, err := need(args, 1, "movie:ID|tv:ID|skip")
		if err != nil {
			return err
		}
		if !ValidTarget(target) {
			return errors.New("target 要写成 movie:ID / tv:ID / skip")
		}
		ctx, err := newContext(configFile, state, false, true)
		if err != nil {
			return err
		}
		path := pathFromPending(fid)
		if path == "" {
			path = fid
		}
		if _, err := ctx.store.Update(func(m *Mapping) error {
			m.Entries[fid] = MappingEntry{Target: target, Path: path, Season: season, EpisodeOffset: offset, Bangumi: bgm, Note: note, By: "skill", At: time.Now().UnixMilli()}
			return nil
		}); err != nil {
			return err
		}
		msg := path + " → " + target
		if season != nil {
			msg += fmt.Sprintf(" 第 %d 季", *season)
		}
		if offset != nil {
			msg += fmt.Sprintf(" 集数 +%d", *offset)
		}
		fmt.Println(msg)
	case "undecide":
		fid, err := need(args, 0, "fid")
		if err != nil {
			return err
		}
		ctx, err := newContext(configFile, state, false, true)
		if err != nil {
			return err
		}
		if _, err := ctx.store.Update(func(m *Mapping) error {
			if _, ok := m.Entries[fid]; !ok {
				return fmt.Errorf("映射表里没有 %s", fid)
			}
			delete(m.Entries, fid)
			return nil
		}); err != nil {
			return err
		}
		fmt.Println("已删除")
	case "ask":
		reason := takeOption(&args, "--reason")
		guess := takeOption(&args, "--guess")
		key, err := need(args, 0, "key")
		if err != nil {
			return err
		}
		if reason == "" {
			return errors.New("要写 --reason")
		}
		if guess != "" && (!ValidTarget(guess) || guess == Skip) {
			return errors.New("--guess 要写成 movie:ID / tv:ID")
		}
		var work *PendingWork
		for _, w := range pendingWorks() {
			if w.Key == key {
				w := w
				work = &w
			}
		}
		if work == nil {
			return fmt.Errorf("待识别列表里没有 %s", key)
		}
		if err := (&ReviewStore{filepath.Join(home, "review.json")}).Add(ReviewEntry{Work: *work, Reason: reason, Guess: guess, By: "ai"}); err != nil {
			return err
		}
		fmt.Println("已交给用户确认: " + work.Path)
	case "guide":
		fmt.Print(guideText)
	case "mapping":
		ctx, err := newContext(configFile, state, false, true)
		if err != nil {
			return err
		}
		m, err := ctx.store.Load()
		if err != nil {
			return err
		}
		printJSON(m)

	case "build":
		dry := removeArg(&args, "--dry-run")
		ctx, err := newContext(configFile, state, true, false)
		if err != nil {
			return err
		}
		return buildCommand(ctx, !dry)
	case "rename":
		planFile := filepath.Join(home, "rename-plan.txt")
		ctx, err := newContext(configFile, state, true, false)
		if err != nil {
			return err
		}
		if removeArg(&args, "--apply") {
			data, err := os.ReadFile(planFile)
			if err != nil {
				return errors.New("没有计划文件, 先运行 rename")
			}
			var plans []RenamePlan
			for _, line := range strings.Split(string(data), "\n") {
				if p := ParseRenamePlan(line); p != nil {
					plans = append(plans, *p)
				}
			}
			done, skipped, err := ctx.scraper.ApplyRenames(plans)
			if err != nil {
				return err
			}
			fmt.Printf("改名 %d 个\n", done)
			for _, s := range skipped {
				fmt.Println("跳过: " + s)
			}
			_ = os.Remove(planFile)
			return nil
		}
		scan, err := ctx.scraper.Scan()
		if err != nil {
			return err
		}
		m, err := ctx.store.Load()
		if err != nil {
			return err
		}
		_, report, err := ctx.scraper.Build(scan, m, false)
		if err != nil {
			return err
		}
		renames := append([]RenamePlan{}, report.Renames...)
		sort.SliceStable(renames, func(i, j int) bool {
			a, b := renames[i], renames[j]
			if (a.Skipped != "") != (b.Skipped != "") {
				return a.Skipped == ""
			}
			if a.ParentPath != b.ParentPath {
				return a.ParentPath < b.ParentPath
			}
			return a.From < b.From
		})
		var sb strings.Builder
		sb.WriteString("# 改名计划, 确认后运行 arisa-scraper rename --apply\n")
		sb.WriteString("# 不想改的行删掉即可; 最后一列有内容的是跳过的及原因\n")
		sb.WriteString("# fid\t父目录fid\t父目录\t原名\t新名\tTMDB\t跳过原因\n")
		for _, r := range renames {
			sb.WriteString(r.Line() + "\n")
		}
		if err := os.WriteFile(planFile, []byte(sb.String()), 0o644); err != nil {
			return err
		}
		var skip []RenamePlan
		for _, r := range renames {
			if r.Skipped == "" {
				fmt.Printf("%s/%s\n    → %s\n", r.ParentPath, r.From, r.To)
			} else {
				skip = append(skip, r)
			}
		}
		if len(skip) > 0 {
			fmt.Printf("\n跳过 %d 个:\n", len(skip))
		}
		for _, r := range skip {
			fmt.Printf("  %s/%s: %s\n", r.ParentPath, r.From, r.Skipped)
		}
		fmt.Printf("\n计划已写到 %s, 确认后运行 rename --apply\n", planFile)
	case "version":
		fmt.Println(CurrentVersion())
	case "update":
		c, err := LoadConfig(configFile)
		if err != nil {
			return err
		}
		client, err := webClient(state)
		if err != nil {
			return err
		}
		u := NewUpdater(home, NewQuarkDrive(client, c.RateLimit), func() *http.Client { return httpClient(c.Update.Proxy) }, func(s string) { fmt.Println(s) },
			func() bool { return true },
			func() {
				fmt.Println("已切换到新版, 常驻服务需要重启: launchctl kickstart -k gui/$(id -u)/com.arisa.scraper")
			})
		if err := u.Check(c.Update.SelfUpdate, c.Update.App); err != nil {
			return err
		}
		latest := ""
		if m := u.Latest(); m != nil {
			latest = m.Version
		}
		fmt.Printf("当前 %s, 最新 %s\n", CurrentVersion(), latest)
	case "serve":
		s := NewService(home, configFile, state, ServiceOptions{})
		if err := s.Start(); err != nil {
			return err
		}
		select {}

	// 给片库 JSON 补主色调和 BlurHash (生成演示数据用)
	case "enrich":
		file, err := need(args, 0, "片库 JSON")
		if err != nil {
			return err
		}
		data, err := os.ReadFile(file)
		if err != nil {
			return err
		}
		lib := &Library{Schema: LibrarySchema}
		if err := json.Unmarshal(data, lib); err != nil {
			return err
		}
		images := NewImages(httpClient(nil), filepath.Join(home, "cache/images"))
		for i := range lib.Items {
			it := &lib.Items[i]
			poster := images.Analyze(it.Poster, "w185")
			backdrop := images.Analyze(it.Backdrop, "w300")
			it.Art = Artwork{firstNonEmpty(backdrop.Color, poster.Color), poster.BlurHash, backdrop.BlurHash}
		}
		return os.WriteFile(file, prettyJSON(lib), 0o644)
	default:
		fmt.Print(usage)
		os.Exit(1)
	}
	return nil
}

type cliContext struct {
	scraper *Scraper
	store   *MappingStore
}

func newContext(configFile string, state *StateStore, needTmdb, quiet bool) (*cliContext, error) {
	c, err := LoadConfig(configFile)
	if err != nil {
		return nil, err
	}
	client, err := webClient(state)
	if err != nil {
		return nil, err
	}
	drive := NewQuarkDrive(client, c.RateLimit)
	tmdbHTTP := httpClient(c.Tmdb.Proxy)
	var tmdb TmdbAPI
	if strings.TrimSpace(c.Tmdb.APIKey) != "" {
		tmdb = NewTmdb(c.Tmdb.APIKey, tmdbHTTP, filepath.Join(home, "cache/tmdb"))
	}
	if needTmdb && tmdb == nil {
		return nil, fmt.Errorf("没有 TMDB API Key. 在 https://www.themoviedb.org/settings/api 申请后, 填到 %s 的 tmdb.api_key, 或设置环境变量 ARISA_TMDB_API_KEY", filepath.Join(home, "config.yaml"))
	}
	// JSON 输出的命令把日志打到 stderr, 不污染 stdout
	log := func(s string) { fmt.Println(s) }
	if quiet {
		log = func(s string) { fmt.Fprintln(os.Stderr, s) }
	}
	images := NewImages(tmdbHTTP, filepath.Join(home, "cache/images"))
	bgm := NewBangumi(httpClient(c.Bangumi.Proxy), filepath.Join(home, "cache/bangumi"))
	return &cliContext{
		scraper: NewScraper(c, drive, tmdb, images, bgm, log),
		store:   &MappingStore{filepath.Join(home, "mapping.json"), drive},
	}, nil
}

func buildCommand(ctx *cliContext, write bool) error {
	start := time.Now()
	m, err := ctx.store.Load()
	if err != nil {
		return err
	}
	scan, err := ctx.scraper.Scan()
	if err != nil {
		return err
	}
	_, report, err := ctx.scraper.Build(scan, m, write)
	if err != nil {
		return err
	}
	if write {
		if err := ctx.store.Backup(); err != nil {
			return err
		}
	}
	var renames []RenamePlan
	for _, r := range report.Renames {
		if r.Skipped == "" {
			renames = append(renames, r)
		}
	}
	index := report.IndexFile
	if index == "" {
		index = "(没变或未写)"
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, "时间: %s  耗时 %ds\n", time.Now().Format("2006-01-02T15:04:05"), int(time.Since(start).Seconds()))
	fmt.Fprintf(&sb, "%d 部作品, %d 个视频待识别, 写 NFO %d 个, 索引 %s\n", report.Items, report.Pending, report.NfoWritten, index)
	fmt.Fprintf(&sb, "建议改名 %d 个 (rename 命令生成计划)\n", len(renames))
	for _, r := range renames {
		fmt.Fprintf(&sb, "  %s/%s → %s\n", r.ParentPath, r.From, r.To)
	}
	_ = os.WriteFile(filepath.Join(home, "report.txt"), []byte(sb.String()), 0o644)
	fmt.Printf("%d 部作品, %d 个视频待识别, 写 NFO %d 个, 建议改名 %d 个\n", report.Items, report.Pending, report.NfoWritten, len(renames))
	return nil
}

func tmdbCommand(args []string, c Config) error {
	if strings.TrimSpace(c.Tmdb.APIKey) == "" {
		return errors.New("没有 TMDB API Key")
	}
	tmdb := NewTmdb(c.Tmdb.APIKey, httpClient(c.Tmdb.Proxy), filepath.Join(home, "cache/tmdb"))
	lang := c.Defaults.Language
	sub := ""
	if len(args) > 0 {
		sub, args = args[0], args[1:]
	}
	switch sub {
	case "search":
		tv := removeArg(&args, "--tv")
		movie := removeArg(&args, "--movie")
		year, err := intOption(&args, "--year")
		if err != nil {
			return err
		}
		kind := ""
		if tv {
			kind = TypeTV
		} else if movie {
			kind = TypeMovie
		}
		res, err := tmdbSearchJSON(tmdb, strings.Join(args, " "), kind, year, lang)
		if err != nil {
			return err
		}
		printJSON(res)
	case "show":
		if len(args) < 2 {
			return errors.New("用法: tmdb show <movie|tv> <id>")
		}
		id, err := strconv.Atoi(args[1])
		if err != nil {
			return fmt.Errorf("id 不对: %s", args[1])
		}
		res, err := tmdbShowJSON(tmdb, args[0], id, lang)
		if err != nil {
			return err
		}
		printJSON(res)
	default:
		return errors.New("用法: tmdb search <片名> [--tv|--movie] [--year Y] / tmdb show <movie|tv> <id>")
	}
	return nil
}

// pendingWorks 上一次 pending 的输出 (常驻服务交给 AI 前也会写).
func pendingWorks() []PendingWork {
	data, err := os.ReadFile(filepath.Join(home, "pending.json"))
	if err != nil {
		return nil
	}
	var works []PendingWork
	_ = json.Unmarshal(data, &works)
	return works
}

// pathFromPending 从上一次 pending 的输出里查 fid 对应的路径 (作品 key 或某一级目录).
func pathFromPending(fid string) string {
	works := pendingWorks()
	for _, w := range works {
		if w.Key == fid {
			return w.Path
		}
	}
	for _, w := range works {
		for i, d := range w.Dirs {
			if d.Fid == fid {
				prefix := w.Path
				if j := strings.Index(prefix, "/"+w.Dirs[0].Name); j >= 0 {
					prefix = prefix[:j]
				}
				var names []string
				for _, x := range w.Dirs[:i+1] {
					names = append(names, x.Name)
				}
				return prefix + "/" + strings.Join(names, "/")
			}
		}
	}
	return ""
}

// qrLogin 网页版扫码登录: 终端里画二维码 (SSH 远程也能扫), 扫完返回 Cookie.
func qrLogin() (string, error) {
	login := quark.NewWebLogin()
	content, token, err := login.Start()
	if err != nil {
		return "", err
	}
	qrText, err := terminalQR(content)
	if err != nil {
		return "", err
	}
	fmt.Println(qrText)
	fmt.Println("用夸克 App 扫上面的二维码并确认登录 (4 分钟内有效)")
	for i := 0; i < 120; i++ {
		time.Sleep(2 * time.Second)
		cookie, err := login.Poll(token)
		if err != nil {
			return "", err
		}
		if cookie != "" {
			return cookie, nil
		}
	}
	return "", errors.New("扫码超时, 重新运行 login")
}

// terminalQR 用上下半块字符画二维码, 一个字符表示上下两个点, 白底黑块, 留一圈白边方便扫.
func terminalQR(content string) (string, error) {
	size, black, err := qrMatrix(content)
	if err != nil {
		return "", err
	}
	var sb strings.Builder
	for y := 0; y < size; y += 2 {
		for x := 0; x < size; x++ {
			top := black(x, y)
			bottom := y+1 < size && black(x, y+1)
			switch {
			case top && bottom:
				sb.WriteRune(' ')
			case top:
				sb.WriteRune('▄')
			case bottom:
				sb.WriteRune('▀')
			default:
				sb.WriteRune('█')
			}
		}
		sb.WriteByte('\n')
	}
	return sb.String(), nil
}

func tvLogin(state *StateStore) error {
	st, err := state.Load()
	if err != nil {
		return err
	}
	session := quark.NewTvDevice()
	if st.Tv != nil {
		session = *st.Tv
	}
	client := quark.NewTvClient(session, func(s quark.TvSession) { _ = state.Update(func(st *State) { st.Tv = &s }) })
	qrData, queryToken, err := client.StartQrLogin()
	if err != nil {
		return err
	}
	img := filepath.Join(home, "tv-login-qr.jpg")
	_ = os.MkdirAll(home, 0o755)
	if i := strings.Index(qrData, "base64,"); i >= 0 {
		qrData = qrData[i+len("base64,"):]
	}
	data, err := base64.StdEncoding.DecodeString(qrData)
	if err != nil {
		return err
	}
	if err := os.WriteFile(img, data, 0o644); err != nil {
		return err
	}
	fmt.Println("用夸克 App 扫码: " + img)
	_ = exec.Command("open", img).Start()
	for i := 0; i < 120; i++ {
		time.Sleep(2 * time.Second)
		if ok, _ := client.PollLogin(queryToken); ok {
			fmt.Println("登录成功")
			return nil
		}
	}
	return errors.New("扫码超时")
}

func webClient(state *StateStore) (*quark.WebClient, error) {
	st, err := state.Load()
	if err != nil {
		return nil, err
	}
	if st.Cookie == "" {
		return nil, &LoginExpiredError{"没有登录, 先运行 login"}
	}
	return quark.NewWebClient(st.Cookie, func(c string) { _ = state.Update(func(s *State) { s.Cookie = c }) }), nil
}

func tvClient(state *StateStore) (*quark.TvClient, error) {
	st, err := state.Load()
	if err != nil {
		return nil, err
	}
	if st.Tv == nil {
		return nil, errors.New("没有 TV 登录, 先运行 tv-login")
	}
	return quark.NewTvClient(*st.Tv, func(s quark.TvSession) { _ = state.Update(func(st *State) { st.Tv = &s }) }), nil
}

func humanSize(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1fG", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1fM", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1fK", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%dB", n)
}

// takeOption 取出 "--name 值" 两项, 没有返回 "".
func takeOption(args *[]string, name string) string {
	a := *args
	for i := 0; i+1 < len(a); i++ {
		if a[i] == name {
			v := a[i+1]
			*args = append(append([]string{}, a[:i]...), a[i+2:]...)
			return v
		}
	}
	return ""
}

func intOption(args *[]string, name string) (*int, error) {
	v := takeOption(args, name)
	if v == "" {
		return nil, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return nil, fmt.Errorf("%s 要写数字: %s", name, v)
	}
	return &n, nil
}

func removeArg(args *[]string, name string) bool {
	for i, a := range *args {
		if a == name {
			*args = append(append([]string{}, (*args)[:i]...), (*args)[i+1:]...)
			return true
		}
	}
	return false
}

func deref(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

func ptr(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}
