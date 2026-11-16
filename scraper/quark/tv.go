package quark

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// TvAPI 是 TV 端开放接口的默认地址.
	TvAPI = "https://open-api-drive.quark.cn"
	// TvCodeAPI 是 code/refresh_token 换 access_token 的默认地址 (TV 客户端厂商的服务器).
	TvCodeAPI   = "http://api.extscreen.com/quarkdrive"
	tvClientID  = "d3194e61504e493eb6222857bccfed94"
	tvSignKey   = "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d"
	tvAppVer    = "1.8.2.2"
	tvDeviceMod = "M2004J7AC"
	// TvUserAgent 是 TV 端的 User-Agent, 取直链时要带.
	TvUserAgent = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1"
)

// TvSession 是 TV 端的登录态. 会存进 state.json, 旧版本 (Kotlin) 也读, 字段名不能改.
type TvSession struct {
	DeviceID     string `json:"deviceId"`
	RefreshToken string `json:"refreshToken,omitempty"`
	AccessToken  string `json:"accessToken,omitempty"`
}

// NewTvDevice 生成一个新设备 (随机 device_id, 还没登录).
func NewTvDevice() TvSession {
	return TvSession{DeviceID: hexOf("MD5", []byte(strconv.FormatInt(time.Now().UnixNano(), 10)))}
}

// StreamingVideo 是夸克转码后的一路清晰度流.
type StreamingVideo struct {
	Resolution string `json:"resolution"`
	URL        string `json:"url"`
	Size       int64  `json:"size"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
	Duration   int    `json:"duration"`
	Format     string `json:"format"`
}

// TvClient 是夸克 TV 端开放接口: 扫码登录、列目录、取直链/转码流, 不能上传.
// 参考 OpenList drivers/quark_uc_tv.
//
// 注意: code/refresh_token 换 access_token 走的是 TV 客户端厂商的服务器 (api.extscreen.com).
type TvClient struct {
	// API 是开放接口地址, 默认 TvAPI; 测试时改成假服务器.
	API string
	// CodeAPI 是换 token 的地址, 默认 TvCodeAPI.
	CodeAPI string

	mu      sync.Mutex
	session TvSession
	cbMu    sync.Mutex
	// onChange 在登录态变化 (拿到/刷新 token) 时回调, 调用方负责持久化.
	onChange func(TvSession)
	hc       *http.Client
}

// NewTvClient 建一个 TV 端客户端. onChange 可以为 nil.
func NewTvClient(s TvSession, onChange func(TvSession)) *TvClient {
	return &TvClient{API: TvAPI, CodeAPI: TvCodeAPI, session: s, onChange: onChange, hc: defaultHTTPClient}
}

// Session 返回当前登录态.
func (c *TvClient) Session() TvSession {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.session
}

// IsLoggedIn 是否有 refresh_token.
func (c *TvClient) IsLoggedIn() bool { return c.Session().RefreshToken != "" }

// StartQrLogin 开始扫码登录: qrJpegBase64 是二维码图片, 扫码后用 queryToken 调 PollLogin.
func (c *TvClient) StartQrLogin() (qrJpegBase64, queryToken string, err error) {
	m, err := c.request("/oauth/authorize", "GET", []param{
		{"auth_type", "code"},
		{"client_id", tvClientID},
		{"scope", "netdisk"},
		{"qrcode", "1"},
		{"qr_width", "460"},
		{"qr_height", "460"},
	}, false)
	if err != nil {
		return "", "", err
	}
	return stringField(m, "qr_data"), stringField(m, "query_token"), nil
}

// PollLogin 查扫码结果: 还没扫码/没确认时返回 false, 登录成功返回 true.
func (c *TvClient) PollLogin(queryToken string) (bool, error) {
	m, err := c.request("/oauth/code", "GET", []param{
		{"client_id", tvClientID},
		{"scope", "netdisk"},
		{"query_token", queryToken},
	}, false)
	if err != nil {
		// 接口报错 (还没扫码等) 当没登录; 网络错误照常往外抛
		var qe *Error
		var ae *AuthError
		if errors.As(err, &qe) || errors.As(err, &ae) {
			return false, nil
		}
		return false, err
	}
	code := stringField(m, "code")
	if code == "" {
		return false, nil
	}
	if err := c.exchangeToken(code, ""); err != nil {
		return false, err
	}
	return true, nil
}

// UserInfo 返回用户信息 (整个响应).
func (c *TvClient) UserInfo() (map[string]any, error) {
	m, err := c.request("/user", "GET", []param{{"method", "user_info"}}, true)
	if err != nil {
		return nil, err
	}
	raw, _ := json.Marshal(m)
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, &Error{Msg: "/user: bad response: " + err.Error()}
	}
	return out, nil
}

// List 列出目录下的全部文件 (自动翻页).
func (c *TvClient) List(parentFid string) ([]File, error) {
	files := []File{}
	pageSize := 100
	page := 0
	for {
		m, err := c.request("/file", "GET", []param{
			{"method", "list"},
			{"parent_fid", parentFid},
			{"order_by", "1"},
			{"desc", "0"},
			{"category", ""},
			{"source", ""},
			{"ex_source", ""},
			{"list_all", "0"},
			{"page_size", strconv.Itoa(pageSize)},
			{"page_index", strconv.Itoa(page)},
		}, true)
		if err != nil {
			return nil, err
		}
		raw, ok := m["data"]
		if !ok || !isObject(raw) {
			break
		}
		var resp tvListData
		if err := decodeJSON("/file", raw, &resp); err != nil {
			return nil, err
		}
		for _, it := range resp.Files {
			if it.Fid == nil {
				return nil, &Error{Msg: "/file: bad response: missing fid"}
			}
			files = append(files, File{
				Fid:       string(*it.Fid),
				Name:      string(it.Filename),
				IsDir:     it.Isdir == 1,
				Size:      int64(it.Size),
				Category:  int(it.Category),
				UpdatedAt: int64(it.UpdatedAt),
				Thumbnail: string(it.ThumbnailURL),
			})
		}
		page++
		if len(resp.Files) == 0 || int64(page)*int64(pageSize) >= int64(resp.TotalCount) {
			break
		}
	}
	return files, nil
}

// DownloadLink 取原文件直链.
func (c *TvClient) DownloadLink(fid string) (Link, error) {
	m, err := c.request("/file", "GET", []param{
		{"method", "download"},
		{"group_by", "source"},
		{"fid", fid},
		{"resolution", "low,normal,high,super,2k,4k"},
		{"support", "dolby_vision"},
	}, true)
	if err != nil {
		return Link{}, err
	}
	data, ok := objectField(m, "data")
	if !ok {
		return Link{}, &Error{Msg: "download: no data"}
	}
	url := stringField(data, "download_url")
	if url == "" {
		return Link{}, &Error{Msg: "download: empty url"}
	}
	// size 有时是字符串有时是数字, 解析不了当 0 (原版为 null)
	size, _ := strconv.ParseInt(stringField(data, "size"), 10, 64)
	return Link{URL: url, Headers: map[string]string{"User-Agent": TvUserAgent}, Size: size}, nil
}

// StreamingLinks 返回夸克转码后的各清晰度流.
func (c *TvClient) StreamingLinks(fid string) ([]StreamingVideo, error) {
	m, err := c.request("/file", "GET", []param{
		{"method", "streaming"},
		{"group_by", "source"},
		{"fid", fid},
		{"resolution", "low,normal,high,super,2k,4k"},
		{"support", "dolby_vision"},
	}, true)
	if err != nil {
		return nil, err
	}
	raw, ok := m["data"]
	if !ok || !isObject(raw) {
		return []StreamingVideo{}, nil
	}
	var data struct {
		VideoInfo []struct {
			Resolution jStr `json:"resolution"`
			URL        jStr `json:"url"`
			Size       jInt `json:"size"`
			Width      jInt `json:"width"`
			Height     jInt `json:"height"`
			Duration   jInt `json:"duration"`
			Format     jStr `json:"format"`
		} `json:"video_info"`
	}
	if err := decodeJSON("/file", raw, &data); err != nil {
		return nil, err
	}
	out := []StreamingVideo{}
	for _, v := range data.VideoInfo {
		if v.URL == "" {
			continue
		}
		out = append(out, StreamingVideo{
			Resolution: string(v.Resolution),
			URL:        string(v.URL),
			Size:       int64(v.Size),
			Width:      int(v.Width),
			Height:     int(v.Height),
			Duration:   int(v.Duration),
			Format:     string(v.Format),
		})
	}
	return out, nil
}

func (c *TvClient) request(path, method string, query []param, retryOnTokenError bool) (map[string]json.RawMessage, error) {
	if retryOnTokenError && c.Session().AccessToken == "" {
		if err := c.refreshAccessToken(); err != nil {
			return nil, err
		}
	}

	s := c.Session()
	tm, token, reqID := tvSign(s.DeviceID, method, path)
	ps := []param{{"req_id", reqID}, {"access_token", s.AccessToken}}
	ps = append(ps, deviceParams(s.DeviceID)...)
	ps = append(ps, query...)
	req, err := http.NewRequest(method, withQuery(c.API+path, ps), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("User-Agent", TvUserAgent)
	req.Header["x-pan-tm"] = []string{tm}
	req.Header["x-pan-token"] = []string{token}
	req.Header["x-pan-client-id"] = []string{tvClientID}
	_, body, err := doRequest(c.hc, req)
	if err != nil {
		return nil, err
	}
	m, err := parseObject(body)
	if err != nil {
		return nil, &Error{Msg: path + ": bad response: " + take(string(body), 200)}
	}

	status := intField(m, "status")
	errno := intField(m, "errno")
	info := stringField(m, "error_info")
	if retryOnTokenError && isTokenError(status, errno, info) {
		if err := c.refreshAccessToken(); err != nil {
			return nil, err
		}
		return c.request(path, method, query, false)
	}
	if status >= 400 || errno != 0 {
		return nil, &Error{Msg: fmt.Sprintf("%s: status=%d errno=%d %s", path, status, errno, info)}
	}
	return m, nil
}

func isTokenError(status, errno int, info string) bool {
	lower := strings.ToLower(strings.TrimSpace(info))
	return (status == -1 && (errno == 10001 || errno == 11001)) ||
		strings.Contains(lower, "access token") || strings.Contains(lower, "access_token") ||
		strings.Contains(lower, "token无效") || strings.Contains(lower, "token 无效")
}

func (c *TvClient) refreshAccessToken() error {
	refresh := c.Session().RefreshToken
	if refresh == "" {
		return &Error{Msg: "not logged in"}
	}
	return c.exchangeToken("", refresh)
}

// exchangeToken 用扫码拿到的 code 或 refresh_token 换 access_token.
func (c *TvClient) exchangeToken(code, refreshToken string) error {
	s := c.Session()
	_, _, reqID := tvSign(s.DeviceID, "POST", "/token")
	body := obj{{"req_id", reqID}}
	for _, p := range deviceParams(s.DeviceID) {
		body = append(body, field{p.k, p.v})
	}
	if code != "" {
		body = append(body, field{"code", code})
	}
	if refreshToken != "" {
		body = append(body, field{"refresh_token", refreshToken})
	}
	b, err := body.MarshalJSON()
	if err != nil {
		return err
	}
	req, err := http.NewRequest("POST", c.CodeAPI+"/token", bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	_, text, err := doRequest(c.hc, req)
	if err != nil {
		return err
	}
	var resp struct {
		Code    jInt `json:"code"`
		Message jStr `json:"message"`
		Data    *struct {
			AccessToken  jStr `json:"access_token"`
			RefreshToken jStr `json:"refresh_token"`
		} `json:"data"`
	}
	if _, err := parseObject(text); err != nil {
		return &Error{Msg: "token: bad response: " + take(string(text), 200)}
	}
	if err := json.Unmarshal(text, &resp); err != nil {
		return &Error{Msg: "token: bad response: " + take(string(text), 200)}
	}
	if resp.Code != 200 || resp.Data == nil || resp.Data.RefreshToken == "" {
		return &Error{Msg: fmt.Sprintf("token: %d %s", int64(resp.Code), string(resp.Message))}
	}
	c.mu.Lock()
	c.session.RefreshToken = string(resp.Data.RefreshToken)
	c.session.AccessToken = string(resp.Data.AccessToken)
	c.mu.Unlock()
	if c.onChange != nil {
		// 并发时回调可能晚到, 交出去的是回调时刻最新的登录态
		c.cbMu.Lock()
		defer c.cbMu.Unlock()
		c.onChange(c.Session())
	}
	return nil
}

func deviceParams(deviceID string) []param {
	return []param{
		{"app_ver", tvAppVer},
		{"device_id", deviceID},
		{"device_brand", "Xiaomi"},
		{"platform", "tv"},
		{"device_name", tvDeviceMod},
		{"device_model", tvDeviceMod},
		{"build_device", tvDeviceMod},
		{"build_product", tvDeviceMod},
		{"device_gpu", "Adreno (TM) 550"},
		{"activity_rect", "{}"},
		{"channel", "GENERAL"},
	}
}

// tvSign 返回 (毫秒时间戳, x-pan-token, req_id).
func tvSign(deviceID, method, path string) (tm, token, reqID string) {
	tm = strconv.FormatInt(time.Now().UnixMilli(), 10)
	reqID = hexOf("MD5", []byte(deviceID+tm))
	token = hexOf("SHA-256", []byte(method+"&"+path+"&"+tm+"&"+tvSignKey))
	return
}

func isObject(raw json.RawMessage) bool {
	raw = bytes.TrimSpace(raw)
	return len(raw) > 0 && raw[0] == '{'
}

type tvListData struct {
	TotalCount jInt     `json:"total_count"`
	Files      []tvFile `json:"files"`
}

type tvFile struct {
	Fid          *jStr `json:"fid"`
	Filename     jStr  `json:"filename"`
	Category     jInt  `json:"category"`
	Size         jInt  `json:"size"`
	Isdir        jInt  `json:"isdir"`
	UpdatedAt    jInt  `json:"updated_at"`
	ThumbnailURL jStr  `json:"thumbnail_url"`
}
