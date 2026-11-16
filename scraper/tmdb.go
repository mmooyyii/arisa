package main

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// httpClient 带可选代理 (http:// 或 socks5://).
func httpClient(proxy *string) *http.Client {
	tr := &http.Transport{
		DialContext:           (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 120 * time.Second,
		MaxIdleConnsPerHost:   4,
	}
	if proxy != nil && strings.TrimSpace(*proxy) != "" {
		if u, err := url.Parse(strings.TrimSpace(*proxy)); err == nil {
			tr.Proxy = http.ProxyURL(u)
		}
	} else {
		tr.Proxy = http.ProxyFromEnvironment
	}
	return &http.Client{Transport: tr, Timeout: 10 * time.Minute}
}

func sha1Hex(s string) string {
	h := sha1.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

// diskCache 读缓存, 没有或过期返回 nil.
func readCache(file string, ttl time.Duration) []byte {
	st, err := os.Stat(file)
	if err != nil || time.Since(st.ModTime()) >= ttl {
		return nil
	}
	data, _ := os.ReadFile(file)
	return data
}

const cacheTTL = 7 * 24 * time.Hour

// TmdbAuthError: key 不对, 每个请求都会失败, 直接中止, 不要把所有片子都当成没识别.
type TmdbAuthError struct{ Msg string }

func (e *TmdbAuthError) Error() string { return e.Msg }

func isTmdbAuth(err error) bool {
	var t *TmdbAuthError
	return errors.As(err, &t)
}

type TmdbAPI interface {
	SearchMovie(query string, year *int, language string) ([]TmdbSearchResult, error)
	SearchTv(query string, year *int, language string) ([]TmdbSearchResult, error)
	Movie(id int, language string) (*TmdbDetails, error)
	Tv(id int, language string) (*TmdbDetails, error)
	Season(tvID, number int, language string) (*TmdbSeasonDetails, error)
	// FindByImdb 返回 (movie|tv, id), 找不到 kind 为 "".
	FindByImdb(imdbID string) (string, int, error)
}

// Tmdb 是 TMDB v3 接口, 响应缓存在磁盘上 (7 天), 重复扫描不重复请求.
// api_key 支持 v3 key 和 v4 read access token (eyJ 开头).
type Tmdb struct {
	apiKey   string
	http     *http.Client
	cacheDir string
	base     string
}

func NewTmdb(apiKey string, client *http.Client, cacheDir string) *Tmdb {
	// api.themoviedb.org 在国内不通, 备用域名 api.tmdb.org 可以直连
	return &Tmdb{apiKey, client, cacheDir, "https://api.tmdb.org/3"}
}

func (t *Tmdb) SearchMovie(query string, year *int, language string) ([]TmdbSearchResult, error) {
	q := map[string]string{"query": query, "language": language}
	if year != nil {
		q["year"] = strconv.Itoa(*year)
	}
	var page struct{ Results []TmdbSearchResult }
	return page.Results, t.get("/search/movie", q, &page)
}

func (t *Tmdb) SearchTv(query string, year *int, language string) ([]TmdbSearchResult, error) {
	q := map[string]string{"query": query, "language": language}
	if year != nil {
		q["first_air_date_year"] = strconv.Itoa(*year)
	}
	var page struct{ Results []TmdbSearchResult }
	return page.Results, t.get("/search/tv", q, &page)
}

func (t *Tmdb) Movie(id int, language string) (*TmdbDetails, error) {
	return t.details(fmt.Sprintf("/movie/%d", id), language, "credits,images,external_ids,release_dates,alternative_titles")
}

func (t *Tmdb) Tv(id int, language string) (*TmdbDetails, error) {
	return t.details(fmt.Sprintf("/tv/%d", id), language, "credits,images,external_ids,content_ratings,alternative_titles")
}

func (t *Tmdb) Season(tvID, number int, language string) (*TmdbSeasonDetails, error) {
	var s TmdbSeasonDetails
	return &s, t.get(fmt.Sprintf("/tv/%d/season/%d", tvID, number), map[string]string{"language": language}, &s)
}

func (t *Tmdb) FindByImdb(imdbID string) (string, int, error) {
	var r struct {
		MovieResults []TmdbSearchResult `json:"movie_results"`
		TvResults    []TmdbSearchResult `json:"tv_results"`
	}
	if err := t.get("/find/"+imdbID, map[string]string{"external_source": "imdb_id"}, &r); err != nil {
		return "", 0, err
	}
	if len(r.MovieResults) > 0 {
		return TypeMovie, r.MovieResults[0].ID, nil
	}
	if len(r.TvResults) > 0 {
		return TypeTV, r.TvResults[0].ID, nil
	}
	return "", 0, nil
}

func (t *Tmdb) details(path, language, appendTo string) (*TmdbDetails, error) {
	lang, _, _ := strings.Cut(language, "-")
	var d TmdbDetails
	return &d, t.get(path, map[string]string{
		"language":           language,
		"append_to_response": appendTo,
		// Logo/背景图 只拿中文、英文和无文字的
		"include_image_language": lang + ",en,null",
	}, &d)
}

func (t *Tmdb) isBearer() bool { return strings.HasPrefix(t.apiKey, "eyJ") }

func encodeQuery(q map[string]string) string {
	keys := make([]string, 0, len(q))
	for k := range q {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		parts = append(parts, url.QueryEscape(k)+"="+url.QueryEscape(q[k]))
	}
	return strings.Join(parts, "&")
}

func (t *Tmdb) get(path string, query map[string]string, out any) error {
	u := t.base + path + "?" + encodeQuery(query)
	cacheFile := filepath.Join(t.cacheDir, sha1Hex(u)+".json")
	if data := readCache(cacheFile, cacheTTL); data != nil {
		return json.Unmarshal(data, out)
	}
	reqURL := u
	if !t.isBearer() {
		reqURL += "&api_key=" + url.QueryEscape(t.apiKey)
	}
	for attempt := 0; ; attempt++ {
		req, _ := http.NewRequest("GET", reqURL, nil)
		if t.isBearer() {
			req.Header.Set("Authorization", "Bearer "+t.apiKey)
		}
		req.Header.Set("Accept", "application/json")
		resp, err := t.http.Do(req)
		if err != nil {
			return fmt.Errorf("TMDB %s: %w", path, err)
		}
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			return fmt.Errorf("TMDB %s: %w", path, err)
		}
		switch {
		case resp.StatusCode/100 == 2:
			if err := json.Unmarshal(body, out); err != nil {
				return fmt.Errorf("TMDB %s: %w", path, err)
			}
			_ = os.MkdirAll(t.cacheDir, 0o755)
			_ = os.WriteFile(cacheFile, body, 0o644)
			return nil
		case resp.StatusCode == 401:
			return &TmdbAuthError{"TMDB API Key 无效 (401), 检查 tmdb.api_key"}
		case resp.StatusCode == 429 && attempt < 5:
			// 限流时退避重试
			time.Sleep(time.Second << attempt)
		default:
			return fmt.Errorf("TMDB %s %d: %s", path, resp.StatusCode, truncate(string(body), 200))
		}
	}
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func yearOf(date string) *int {
	if len(date) < 4 {
		return nil
	}
	n, err := strconv.Atoi(date[:4])
	if err != nil {
		return nil
	}
	return &n
}

type TmdbSearchResult struct {
	ID               int     `json:"id"`
	Title            string  `json:"title"`
	Name             string  `json:"name"`
	OriginalTitle    string  `json:"original_title"`
	OriginalName     string  `json:"original_name"`
	ReleaseDate      string  `json:"release_date"`
	FirstAirDate     string  `json:"first_air_date"`
	Overview         string  `json:"overview"`
	Popularity       float64 `json:"popularity"`
	VoteCount        int     `json:"vote_count"`
	GenreIDs         []int   `json:"genre_ids"`
	OriginalLanguage string  `json:"original_language"`
	PosterPath       string  `json:"poster_path"`
}

func (r TmdbSearchResult) DisplayTitle() string { return firstNonEmpty(r.Title, r.Name) }
func (r TmdbSearchResult) DisplayOriginal() string {
	return firstNonEmpty(r.OriginalTitle, r.OriginalName)
}
func (r TmdbSearchResult) Year() *int { return yearOf(firstNonEmpty(r.ReleaseDate, r.FirstAirDate)) }

func firstNonEmpty(s ...string) string {
	for _, x := range s {
		if x != "" {
			return x
		}
	}
	return ""
}

type TmdbImageInfo struct {
	FilePath    string  `json:"file_path"`
	Language    *string `json:"iso_639_1"`
	VoteAverage float64 `json:"vote_average"`
	Width       int     `json:"width"`
	Height      int     `json:"height"`
}

type TmdbCollectionRef struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type TmdbDetails struct {
	ID            int    `json:"id"`
	Title         string `json:"title"`
	Name          string `json:"name"`
	OriginalTitle string `json:"original_title"`
	OriginalName  string `json:"original_name"`
	ReleaseDate   string `json:"release_date"`
	FirstAirDate  string `json:"first_air_date"`
	Overview      string `json:"overview"`
	Tagline       string `json:"tagline"`
	Genres        []struct {
		ID   int    `json:"id"`
		Name string `json:"name"`
	} `json:"genres"`
	VoteAverage    *float64 `json:"vote_average"`
	Runtime        *int     `json:"runtime"`
	EpisodeRunTime []int    `json:"episode_run_time"`
	PosterPath     string   `json:"poster_path"`
	BackdropPath   string   `json:"backdrop_path"`
	ImdbID         string   `json:"imdb_id"`
	ExternalIDs    *struct {
		ImdbID string `json:"imdb_id"`
	} `json:"external_ids"`
	Credits *struct {
		Cast []struct {
			Name        string `json:"name"`
			Character   string `json:"character"`
			ProfilePath string `json:"profile_path"`
		} `json:"cast"`
		Crew []struct {
			Name string `json:"name"`
			Job  string `json:"job"`
		} `json:"crew"`
	} `json:"credits"`
	CreatedBy []struct {
		Name string `json:"name"`
	} `json:"created_by"`
	// AlternativeTitles: 电影放在 titles, 剧集放在 results.
	AlternativeTitles *struct {
		Titles  []tmdbAltTitle `json:"titles"`
		Results []tmdbAltTitle `json:"results"`
	} `json:"alternative_titles"`
	Images *struct {
		Backdrops []TmdbImageInfo `json:"backdrops"`
		Logos     []TmdbImageInfo `json:"logos"`
		Posters   []TmdbImageInfo `json:"posters"`
	} `json:"images"`
	Seasons []struct {
		SeasonNumber int    `json:"season_number"`
		Name         string `json:"name"`
		PosterPath   string `json:"poster_path"`
		EpisodeCount int    `json:"episode_count"`
	} `json:"seasons"`
	ReleaseDates *struct {
		Results []struct {
			Country      string `json:"iso_3166_1"`
			ReleaseDates []struct {
				Certification string `json:"certification"`
			} `json:"release_dates"`
		} `json:"results"`
	} `json:"release_dates"`
	ContentRatings *struct {
		Results []struct {
			Country string `json:"iso_3166_1"`
			Rating  string `json:"rating"`
		} `json:"results"`
	} `json:"content_ratings"`
	OriginalLanguage    string   `json:"original_language"`
	OriginCountry       []string `json:"origin_country"`
	ProductionCountries []struct {
		Code string `json:"iso_3166_1"`
	} `json:"production_countries"`
	// BelongsToCollection 电影所属的系列 (如「沙丘（系列）」), 只有电影有.
	BelongsToCollection *TmdbCollectionRef `json:"belongs_to_collection"`
}

type tmdbAltTitle struct {
	Country string `json:"iso_3166_1"`
	Title   string `json:"title"`
}

func (d *TmdbDetails) DisplayTitle() string    { return firstNonEmpty(d.Title, d.Name) }
func (d *TmdbDetails) DisplayOriginal() string { return firstNonEmpty(d.OriginalTitle, d.OriginalName) }
func (d *TmdbDetails) Year() *int              { return yearOf(firstNonEmpty(d.ReleaseDate, d.FirstAirDate)) }

type TmdbSeasonDetails struct {
	SeasonNumber int    `json:"season_number"`
	Name         string `json:"name"`
	Overview     string `json:"overview"`
	PosterPath   string `json:"poster_path"`
	Episodes     []struct {
		EpisodeNumber int    `json:"episode_number"`
		SeasonNumber  int    `json:"season_number"`
		Name          string `json:"name"`
		Overview      string `json:"overview"`
		StillPath     string `json:"still_path"`
		AirDate       string `json:"air_date"`
		Runtime       *int   `json:"runtime"`
	} `json:"episodes"`
}

// tmdbSearchJSON 是 tmdb search 的输出, 给 AI 和网页用. kind 为空时电影、剧集都搜.
func tmdbSearchJSON(t TmdbAPI, query, kind string, year *int, language string) ([]map[string]any, error) {
	out := []map[string]any{}
	add := func(kind string, results []TmdbSearchResult) {
		for i, r := range results {
			if i >= 8 {
				break
			}
			m := map[string]any{"target": fmt.Sprintf("%s:%d", kind, r.ID), "title": r.DisplayTitle()}
			if o := r.DisplayOriginal(); o != "" && o != r.DisplayTitle() {
				m["original"] = o
			}
			if y := r.Year(); y != nil {
				m["year"] = *y
			}
			m["popularity"] = r.Popularity
			if r.PosterPath != "" {
				m["poster"] = r.PosterPath
			}
			if strings.TrimSpace(r.Overview) != "" {
				m["overview"] = truncate(r.Overview, 100)
			}
			out = append(out, m)
		}
	}
	if kind != TypeTV {
		r, err := t.SearchMovie(query, year, language)
		if err != nil {
			return nil, err
		}
		add(TypeMovie, r)
	}
	if kind != TypeMovie {
		r, err := t.SearchTv(query, year, language)
		if err != nil {
			return nil, err
		}
		add(TypeTV, r)
	}
	return out, nil
}

// tmdbShowJSON 是 tmdb show 的输出, 剧集带每季集数.
func tmdbShowJSON(t TmdbAPI, kind string, id int, language string) (map[string]any, error) {
	var d *TmdbDetails
	var err error
	if kind == TypeMovie {
		d, err = t.Movie(id, language)
	} else {
		d, err = t.Tv(id, language)
	}
	if err != nil {
		return nil, err
	}
	m := map[string]any{"target": fmt.Sprintf("%s:%d", kind, id), "title": d.DisplayTitle()}
	if o := d.DisplayOriginal(); o != "" {
		m["original"] = o
	}
	if y := d.Year(); y != nil {
		m["year"] = *y
	}
	if d.Runtime != nil {
		m["runtime"] = *d.Runtime
	}
	if d.PosterPath != "" {
		m["poster"] = d.PosterPath
	}
	if d.Overview != "" {
		m["overview"] = truncate(d.Overview, 200)
	}
	seasons := []map[string]any{}
	for _, s := range d.Seasons {
		seasons = append(seasons, map[string]any{"season": s.SeasonNumber, "name": s.Name, "episodes": s.EpisodeCount})
	}
	m["seasons"] = seasons
	return m, nil
}
