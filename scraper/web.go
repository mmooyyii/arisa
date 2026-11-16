package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"

	"github.com/mmooyyii/arisa/scraper/quark"
	"rsc.io/qr"
)

//go:embed assets/web/index.html
var indexHTML []byte

// 管理网页: 状态、待确认、片库、改名计划、动态、设置、更新、夸克登录. 只在局域网用, 没有鉴权.
// 接口的 JSON 和旧版一样, 网页 (assets/web/index.html) 照着用.
type webServer struct {
	s *Service
	// logins 扫码登录进行中的会话, token → 登录器 (它攒着登录过程中拿到的 Cookie).
	logins sync.Map
}

func StartWebServer(s *Service, port int) error {
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return err
	}
	w := &webServer{s: s}
	go func() { _ = http.Serve(ln, w) }()
	return nil
}

type httpError struct{ msg string }

func (e httpError) Error() string { return e.msg }

func (w *webServer) ServeHTTP(rw http.ResponseWriter, r *http.Request) {
	defer func() {
		if p := recover(); p != nil {
			sendJSON(rw, 400, map[string]any{"error": fmt.Sprint(p)})
		}
	}()
	if err := w.handle(rw, r); err != nil {
		sendJSON(rw, 400, map[string]any{"error": err.Error()})
	}
}

func sendJSON(rw http.ResponseWriter, code int, v any) {
	rw.Header().Set("Content-Type", "application/json; charset=utf-8")
	rw.WriteHeader(code)
	enc := json.NewEncoder(rw)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func ok(rw http.ResponseWriter) error { sendJSON(rw, 200, map[string]any{"ok": true}); return nil }

type body map[string]any

func readBody(r *http.Request) (body, error) {
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	b := body{}
	if strings.TrimSpace(string(data)) == "" {
		return b, nil
	}
	return b, json.Unmarshal(data, &b)
}

// str 非空字符串, 没有返回 "".
func (b body) str(k string) string {
	switch v := b[k].(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	}
	return ""
}

func (b body) need(k string) (string, error) {
	if v := b.str(k); v != "" {
		return v, nil
	}
	return "", httpError{"缺少 " + k}
}

func (b body) intOrNil(k string) *int {
	switch v := b[k].(type) {
	case float64:
		if v == float64(int(v)) {
			n := int(v)
			return &n
		}
	case string:
		if n, err := strconv.Atoi(v); err == nil {
			return &n
		}
	}
	return nil
}

func (w *webServer) handle(rw http.ResponseWriter, r *http.Request) error {
	s := w.s
	q := r.URL.Query()
	switch r.Method + " " + r.URL.Path {
	case "GET /", "GET /index.html":
		rw.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = rw.Write(indexHTML)
		return nil
	case "GET /api/status":
		sendJSON(rw, 200, s.Status())
	case "GET /api/activity":
		sendJSON(rw, 200, s.Activity())
	case "GET /api/review":
		list, err := s.Review.Load()
		if err != nil {
			return err
		}
		if list == nil {
			list = []ReviewEntry{}
		}
		sort.SliceStable(list, func(i, j int) bool { return list[i].At > list[j].At })
		sendJSON(rw, 200, list)
	case "GET /api/renames":
		out := []map[string]any{}
		for _, p := range s.RenamePlans() {
			m := map[string]any{"fid": p.Fid, "parentPath": p.ParentPath, "from": p.From, "to": p.To, "tmdb": p.Tmdb}
			if p.Skipped != "" {
				m["skipped"] = p.Skipped
			}
			out = append(out, m)
		}
		sendJSON(rw, 200, out)
	case "GET /api/thumbnail":
		fid := q.Get("fid")
		if fid == "" {
			return httpError{"缺少 fid"}
		}
		return w.thumbnail(rw, fid)
	case "GET /api/tmdb/search":
		c := s.Config()
		tmdb := s.Tmdb(c)
		if tmdb == nil {
			return httpError{"没有 TMDB API Key"}
		}
		kind := q.Get("kind")
		if kind != TypeMovie && kind != TypeTV {
			kind = ""
		}
		var year *int
		if y, err := strconv.Atoi(q.Get("year")); err == nil {
			year = &y
		}
		res, err := tmdbSearchJSON(tmdb, q.Get("q"), kind, year, c.Defaults.Language)
		if err != nil {
			return err
		}
		sendJSON(rw, 200, res)
	case "GET /api/tmdb/show":
		c := s.Config()
		tmdb := s.Tmdb(c)
		if tmdb == nil {
			return httpError{"没有 TMDB API Key"}
		}
		id, err := strconv.Atoi(q.Get("id"))
		if err != nil {
			return httpError{"id 不对"}
		}
		res, err := tmdbShowJSON(tmdb, q.Get("kind"), id, c.Defaults.Language)
		if err != nil {
			return err
		}
		sendJSON(rw, 200, res)
	case "POST /api/decide":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		fid, err := b.need("fid")
		if err != nil {
			return err
		}
		target, err := b.need("target")
		if err != nil {
			return err
		}
		path := b.str("path")
		if path == "" {
			path = fid
		}
		if err := s.Decide(fid, target, b.intOrNil("season"), path, b.str("key"), b.intOrNil("episodeOffset")); err != nil {
			return err
		}
		return ok(rw)
	case "POST /api/retry":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		key, err := b.need("key")
		if err != nil {
			return err
		}
		s.Retry(key)
		return ok(rw)
	case "POST /api/rename":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		fids := map[string]bool{}
		if list, ok := b["fids"].([]any); ok {
			for _, x := range list {
				fids[fmt.Sprint(x)] = true
			}
		}
		if len(fids) == 0 {
			return httpError{"没有选择要改名的目录"}
		}
		s.Submit(RenameJob{fids})
		return ok(rw)
	case "POST /api/scan":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		full, _ := b["full"].(bool)
		s.Submit(ScanJob{full, "手动"})
		return ok(rw)
	case "POST /api/cookie":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		cookie, err := b.need("cookie")
		if err != nil {
			return err
		}
		if err := s.SetCookie(cookie); err != nil {
			return err
		}
		return ok(rw)
	case "POST /api/login/start":
		login := quark.NewWebLogin()
		content, token, err := login.Start()
		if err != nil {
			return err
		}
		w.logins.Store(token, login)
		svg, err := qrSVG(content)
		if err != nil {
			return err
		}
		sendJSON(rw, 200, map[string]any{"token": token, "svg": svg})
	case "GET /api/login/poll":
		token := q.Get("token")
		if token == "" {
			return httpError{"缺少 token"}
		}
		v, found := w.logins.Load(token)
		if !found {
			return httpError{"二维码过期了, 刷新一下"}
		}
		cookie, err := v.(*quark.WebLogin).Poll(token)
		if err != nil {
			return err
		}
		if cookie != "" {
			w.logins.Delete(token)
			if err := s.SetCookie(cookie); err != nil {
				return err
			}
		}
		sendJSON(rw, 200, map[string]any{"done": cookie != ""})
	case "GET /api/library":
		return w.library(rw)
	case "GET /api/pending":
		inReview := s.Review.Keys()
		works, err := s.PendingWorks()
		if err != nil {
			return err
		}
		list := []map[string]any{}
		for _, wk := range works {
			list = append(list, map[string]any{"work": wk, "inReview": inReview[wk.Key]})
		}
		sendJSON(rw, 200, map[string]any{"scanned": works != nil, "works": list})
	case "POST /api/undecide":
		b, err := readBody(r)
		if err != nil {
			return err
		}
		fid, err := b.need("fid")
		if err != nil {
			return err
		}
		if err := s.Undecide(fid); err != nil {
			return err
		}
		return ok(rw)
	case "GET /api/config":
		return w.config(rw)
	case "POST /api/config":
		return w.saveConfig(rw, r)
	case "GET /api/update":
		w.update(rw)
	case "POST /api/update/check":
		// 自动更新可能会等任务做完再重启, 不能卡住请求
		go s.CheckUpdate()
		return ok(rw)
	default:
		rw.Header().Set("Content-Type", "text/plain; charset=utf-8")
		rw.WriteHeader(404)
		_, _ = rw.Write([]byte("not found"))
	}
	return nil
}

// library 已识别的作品 + 它们来自映射表里的哪几条, 以及跳过的条目.
func (w *webServer) library(rw http.ResponseWriter) error {
	m, err := w.s.Store.Load()
	if err != nil {
		return err
	}
	byTarget := map[string][]string{}
	for _, fid := range sortedKeys(m.Entries) {
		t := m.Entries[fid].Target
		byTarget[t] = append(byTarget[t], fid)
	}
	entryJSON := func(fid string) map[string]any {
		e := m.Entries[fid]
		out := map[string]any{"fid": fid, "path": e.Path, "by": e.By, "at": e.At}
		if e.Season != nil {
			out["season"] = *e.Season
		}
		if e.EpisodeOffset != nil {
			out["episodeOffset"] = *e.EpisodeOffset
		}
		if e.Note != "" {
			out["note"] = e.Note
		}
		return out
	}
	entries := func(target string) []map[string]any {
		out := []map[string]any{}
		for _, fid := range byTarget[target] {
			out = append(out, entryJSON(fid))
		}
		return out
	}
	lib := w.s.Library()
	items := []map[string]any{}
	var generatedAt int64
	if lib != nil {
		generatedAt = lib.GeneratedAt
		for _, it := range lib.Items {
			files := len(it.Files)
			for _, s := range it.Seasons {
				for _, e := range s.Episodes {
					files += len(e.Files)
				}
			}
			o := map[string]any{"id": it.ID, "title": it.Title, "type": it.Type, "addedAt": it.AddedAt, "files": files, "seasons": len(it.Seasons), "mappings": entries(it.ID)}
			if it.OriginalTitle != "" {
				o["originalTitle"] = it.OriginalTitle
			}
			if it.Year != nil {
				o["year"] = *it.Year
			}
			if it.Poster != "" {
				o["poster"] = it.Poster
			}
			if it.Rating != nil {
				o["rating"] = *it.Rating
			}
			items = append(items, o)
		}
	}
	sendJSON(rw, 200, map[string]any{"generatedAt": generatedAt, "items": items, "skipped": entries(Skip)})
	return nil
}

// ---------- 设置 ----------

const keyMask = "••••"

func mask(key string) string {
	if key == "" {
		return ""
	}
	r := []rune(key)
	if len(r) > 4 {
		r = r[len(r)-4:]
	}
	return keyMask + string(r)
}

// config 配置文件的内容 (不含环境变量), key 只露后 4 位.
func (w *webServer) config(rw http.ResponseWriter) error {
	raw, err := LoadConfigFile(w.s.ConfigFile)
	if err != nil {
		return err
	}
	raw.Tmdb.APIKey = mask(raw.Tmdb.APIKey)
	raw.Agent.APIKey = mask(raw.Agent.APIKey)
	env := []string{}
	for _, k := range []string{"ARISA_TMDB_API_KEY", "ARISA_DEEPSEEK_API_KEY"} {
		if strings.TrimSpace(os.Getenv(k)) != "" {
			env = append(env, k)
		}
	}
	sendJSON(rw, 200, map[string]any{"config": ConfigJSON(raw), "path": w.s.ConfigFile, "env": env})
	return nil
}

func (w *webServer) saveConfig(rw http.ResponseWriter, r *http.Request) error {
	old, err := LoadConfigFile(w.s.ConfigFile)
	if err != nil {
		return err
	}
	var req struct {
		Config json.RawMessage `json:"config"`
	}
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(data, &req); err != nil {
		return err
	}
	if len(req.Config) == 0 || string(req.Config) == "null" {
		return httpError{"缺少 config"}
	}
	c, err := ParseConfigJSON(req.Config)
	if err != nil {
		return err
	}
	// 没改过的 key 传回来的是打码的样子, 换回原值
	keep := func(n, o string) string {
		if strings.HasPrefix(n, keyMask) {
			return o
		}
		return strings.TrimSpace(n)
	}
	c.Tmdb.APIKey = keep(c.Tmdb.APIKey, old.Tmdb.APIKey)
	c.Agent.APIKey = keep(c.Agent.APIKey, old.Agent.APIKey)
	seen := map[string]bool{}
	for i := range c.Libraries {
		p := "/" + strings.Trim(strings.TrimSpace(c.Libraries[i].Path), "/")
		if p == "/" {
			return httpError{"片库目录不能是网盘根目录"}
		}
		if seen[p] {
			return httpError{"片库目录重复了"}
		}
		seen[p] = true
		c.Libraries[i].Path = p
	}
	if c.Serve.Port < 1 || c.Serve.Port > 65535 {
		return httpError{"端口不对"}
	}
	if err := SaveConfig(w.s.ConfigFile, c); err != nil {
		return err
	}
	w.s.Log("网页上修改了设置")
	// 新加的目录在网盘里找不到时提醒一下, 照样保存 (可能还没建)
	warnings := []string{}
	oldPaths := map[string]bool{}
	for _, l := range old.Libraries {
		oldPaths[l.Path] = true
	}
	for _, l := range c.Libraries {
		if oldPaths[l.Path] {
			continue
		}
		f, err := Resolve(w.s.Drive, l.Path)
		switch {
		case err != nil:
			warnings = append(warnings, l.Path+": 没法确认网盘里有没有 ("+err.Error()+")")
		case f == nil:
			warnings = append(warnings, "网盘里找不到 "+l.Path)
		}
	}
	if c.Serve.Port != old.Serve.Port {
		warnings = append(warnings, "端口要重启服务后才生效")
	}
	ob, _ := json.Marshal(old.Libraries)
	nb, _ := json.Marshal(c.Libraries)
	if string(ob) != string(nb) {
		w.s.Submit(ScanJob{false, "片库目录变了"})
	}
	sendJSON(rw, 200, map[string]any{"ok": true, "warnings": warnings})
	return nil
}

// ---------- 更新 ----------

func (w *webServer) update(rw http.ResponseWriter) {
	u := w.s.Updater
	info := u.Info()
	c := w.s.Config().Update
	out := map[string]any{
		"version": CurrentVersion(), "managed": u.Managed(), "currentCode": VersionCode(CurrentVersion()),
		"selfUpdate": c.SelfUpdate, "mirrorApp": c.App, "checkHours": c.CheckHours,
	}
	if m := info.Latest; m != nil {
		out["latest"] = m.Version
		out["latestCode"] = m.VersionCode
		if m.Notes != "" {
			out["notes"] = m.Notes
		}
		out["publishedAt"] = m.PublishedAt
	}
	if info.AppOnDrive != "" {
		out["appOnDrive"] = info.AppOnDrive
	}
	if info.LastCheck != 0 {
		out["lastCheck"] = info.LastCheck
	}
	if info.LastError != "" {
		out["error"] = info.LastError
	}
	sendJSON(rw, 200, out)
}

// qrMatrix 二维码, 四周留 2 格白边; 返回 (边长, 某格是不是黑的).
func qrMatrix(content string) (int, func(x, y int) bool, error) {
	code, err := qr.Encode(content, qr.L)
	if err != nil {
		return 0, nil, err
	}
	const margin = 2
	size := code.Size + 2*margin
	return size, func(x, y int) bool {
		x, y = x-margin, y-margin
		return x >= 0 && y >= 0 && x < code.Size && y < code.Size && code.Black(x, y)
	}, nil
}

// qrSVG 二维码画成 SVG, 一个模块一个方块.
func qrSVG(content string) (string, error) {
	size, black, err := qrMatrix(content)
	if err != nil {
		return "", err
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">`, size, size)
	sb.WriteString(`<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="`)
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			if black(x, y) {
				fmt.Fprintf(&sb, "M%d %dh1v1h-1z", x, y)
			}
		}
	}
	sb.WriteString(`" /></svg>`)
	return sb.String(), nil
}

// thumbnail 夸克缩略图要带 Cookie, 浏览器不能直接引用, 由这里转发. 地址可能过期, 先重新列一次目录.
func (w *webServer) thumbnail(rw http.ResponseWriter, fid string) error {
	d := w.s.Drive
	f, err := d.Fresh(fid)
	if err != nil || f == nil {
		f = d.Cached(fid)
	}
	notFound := func(msg string) error {
		rw.Header().Set("Content-Type", "text/plain; charset=utf-8")
		rw.WriteHeader(404)
		_, _ = rw.Write([]byte(msg))
		return nil
	}
	if f == nil {
		return notFound("没有这个文件")
	}
	data, err := d.Thumbnail(*f)
	if err != nil {
		return notFound(err.Error())
	}
	rw.Header().Set("Cache-Control", "max-age=86400")
	if len(data) > 12 && string(data[8:12]) == "WEBP" {
		rw.Header().Set("Content-Type", "image/webp")
	} else {
		rw.Header().Set("Content-Type", "image/jpeg")
	}
	_, _ = rw.Write(data)
	return nil
}
