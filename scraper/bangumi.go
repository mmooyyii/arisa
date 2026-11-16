package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode"
)

type BangumiSubject struct {
	ID     int    `json:"id"`
	Name   string `json:"name"`
	NameCn string `json:"name_cn"`
	Date   string `json:"date"`
	// Platform: TV / OVA / 剧场版 / WEB …
	Platform string `json:"platform"`
	Eps      *int   `json:"eps"`
	Summary  string `json:"summary"`
}

func (s BangumiSubject) DisplayName() string {
	if strings.TrimSpace(s.NameCn) != "" {
		return s.NameCn
	}
	return s.Name
}

func (s BangumiSubject) Year() *int { return yearOf(s.Date) }

type BangumiAPI interface {
	SearchAnime(keyword string) ([]BangumiSubject, error)
	Subject(id int) (*BangumiSubject, error)
}

// Bangumi 番组计划 (bgm.tv), 动画的中文名以它为准. 公开接口不需要 key, 但要求带能识别应用的 User-Agent.
// 有磁盘缓存, 和 TMDB 一样 7 天过期.
type Bangumi struct {
	http     *http.Client
	cacheDir string
	base     string
}

const BangumiUserAgent = "arisa/0.1 (personal media library scraper)"

func NewBangumi(client *http.Client, cacheDir string) *Bangumi {
	return &Bangumi{client, cacheDir, "https://api.bgm.tv"}
}

func (b *Bangumi) SearchAnime(keyword string) ([]BangumiSubject, error) {
	// 和旧版写出的请求体一样, 缓存能接着用
	kw, _ := json.Marshal(keyword)
	body := `{"keyword":` + string(kw) + `,"filter":{"type":[2]}}`
	data, err := b.cached("search:"+body, func() (*http.Request, error) {
		req, err := http.NewRequest("POST", b.base+"/v0/search/subjects?limit=20", strings.NewReader(body))
		if err == nil {
			req.Header.Set("Content-Type", "application/json")
		}
		return req, err
	})
	if err != nil {
		return nil, err
	}
	var page struct{ Data []BangumiSubject }
	return page.Data, json.Unmarshal(data, &page)
}

func (b *Bangumi) Subject(id int) (*BangumiSubject, error) {
	data, err := b.cached(fmt.Sprintf("subject:%d", id), func() (*http.Request, error) {
		return http.NewRequest("GET", fmt.Sprintf("%s/v0/subjects/%d", b.base, id), nil)
	})
	if err != nil {
		return nil, err
	}
	var s BangumiSubject
	return &s, json.Unmarshal(data, &s)
}

func (b *Bangumi) cached(key string, request func() (*http.Request, error)) ([]byte, error) {
	file := filepath.Join(b.cacheDir, sha1Hex(key)+".json")
	if data := readCache(file, cacheTTL); data != nil {
		return data, nil
	}
	req, err := request()
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", BangumiUserAgent)
	resp, err := b.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Bangumi: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("Bangumi %d", resp.StatusCode)
	}
	_ = os.MkdirAll(b.cacheDir, 0o755)
	_ = os.WriteFile(file, data, 0o644)
	return data, nil
}

// bangumiNormalize: 小写, 去掉空白、ASCII 标点和常见的中日文标点.
func bangumiNormalize(s string) string {
	var sb strings.Builder
	for _, r := range strings.ToLower(s) {
		if r == ' ' || r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || isASCIIPunct(r) ||
			strings.ContainsRune("·：:，,。！!？?～~'’\"“”「」『』【】", r) {
			continue
		}
		sb.WriteRune(r)
	}
	return sb.String()
}

func isASCIIPunct(r rune) bool {
	return r < 128 && unicode.IsPunct(r) || r < 128 && unicode.IsSymbol(r)
}

// MatchBangumi 给 TMDB 的动画作品找对应的 Bangumi 条目: 用原名 (日文) 搜, 名字对上且年份相差不超过 1 年的才算.
// TMDB 常把多季合成一部, 这里取最早的那一季 (通常就是作品的主条目).
func MatchBangumi(api BangumiAPI, original string, year *int, movie bool) (*BangumiSubject, error) {
	target := bangumiNormalize(original)
	if target == "" {
		return nil, nil
	}
	results, err := api.SearchAnime(original)
	if err != nil {
		return nil, err
	}
	type scored struct {
		s     BangumiSubject
		score float64
	}
	var list []scored
	for _, s := range results {
		if bangumiNormalize(s.Name) != target && bangumiNormalize(s.NameCn) != target {
			continue
		}
		score := 2.0
		sy := s.Year()
		switch {
		case year == nil || sy == nil:
		case *sy == *year:
			score += 1
		case abs(*sy-*year) <= 1:
			score += 0.5
		default:
			continue
		}
		if (s.Platform == "剧场版") == movie {
			score += 0.5
		}
		list = append(list, scored{s, score})
	}
	if len(list) == 0 {
		return nil, nil
	}
	// 分高的优先, 一样时日期早的优先 (没有日期的排最后)
	date := func(s BangumiSubject) string {
		if s.Date == "" {
			return "9999"
		}
		return s.Date
	}
	sort.SliceStable(list, func(i, j int) bool {
		if list[i].score != list[j].score {
			return list[i].score > list[j].score
		}
		return date(list[i].s) < date(list[j].s)
	})
	best := list[0].s
	return &best, nil
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// bangumiSearchJSON 是 bangumi search 的输出, 给 AI 和网页用.
func bangumiSearchJSON(api BangumiAPI, keyword string) ([]map[string]any, error) {
	results, err := api.SearchAnime(keyword)
	if err != nil {
		return nil, err
	}
	out := []map[string]any{}
	for i, s := range results {
		if i >= 10 {
			break
		}
		m := map[string]any{"bangumi": s.ID, "name": s.Name}
		if strings.TrimSpace(s.NameCn) != "" {
			m["name_cn"] = s.NameCn
		}
		if s.Date != "" {
			m["date"] = s.Date
		}
		if s.Platform != "" {
			m["platform"] = s.Platform
		}
		if s.Eps != nil {
			m["eps"] = *s.Eps
		}
		out = append(out, m)
	}
	return out, nil
}
