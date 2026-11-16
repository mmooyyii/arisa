package quark

import (
	"bytes"
	"crypto/md5"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// WebAPI 是网页端接口的默认地址.
	WebAPI     = "https://drive.quark.cn/1/clouddrive"
	webReferer = "https://pan.quark.cn"
	// WebUserAgent 是网页端 (PC 客户端) 的 User-Agent, 取直链、扫码登录都用它.
	WebUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
	ossUA        = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"
)

// WebClient 是夸克网页端接口 (Cookie 登录): 列目录、取直链、上传、删除、建目录.
// 参考 OpenList drivers/quark_uc. 可以被多个 goroutine 同时使用.
type WebClient struct {
	// API 是接口基础地址, 默认 WebAPI; 测试时改成假服务器.
	API string

	mu     sync.Mutex
	cookie string
	// cbMu 保证回调按顺序执行, 每次都把最新的 cookie 交出去.
	cbMu sync.Mutex
	// onCookieChange 在服务端下发新的 __puus 时回调, 调用方负责持久化.
	onCookieChange func(string)
	hc             *http.Client
}

// NewWebClient 建一个网页端客户端. onCookieChange 可以为 nil.
func NewWebClient(cookie string, onCookieChange func(string)) *WebClient {
	return &WebClient{API: WebAPI, cookie: cookie, onCookieChange: onCookieChange, hc: defaultHTTPClient}
}

// Cookie 返回当前的 cookie (可能已经滚动更新过 __puus).
func (c *WebClient) Cookie() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.cookie
}

// Member 返回账号信息 (整个响应). 数字保留成 json.Number.
func (c *WebClient) Member() (map[string]any, error) {
	text, err := c.request("/member", "GET", []param{
		{"fetch_subscribe", "false"}, {"_ch", "home"}, {"fetch_identity", "false"},
	}, nil)
	if err != nil {
		return nil, err
	}
	dec := json.NewDecoder(bytes.NewReader(text))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, &Error{Msg: "/member: bad response: " + err.Error()}
	}
	return m, nil
}

// List 列出目录下的全部文件 (自动翻页).
func (c *WebClient) List(parentFid string) ([]File, error) {
	const path = "/file/sort"
	files := []File{}
	size := 100
	page := 1
	for {
		text, err := c.request(path, "GET", []param{
			{"pdir_fid", parentFid},
			{"_page", strconv.Itoa(page)},
			{"_size", strconv.Itoa(size)},
			{"_fetch_total", "1"},
			{"fetch_all_file", "1"},
			{"fetch_risk_file_name", "1"},
			{"_sort", "file_type:asc,file_name:asc"},
		}, nil)
		if err != nil {
			return nil, err
		}
		var resp sortResp
		if err := decodeJSON(path, text, &resp); err != nil {
			return nil, err
		}
		for _, it := range resp.Data.List {
			f, err := it.toFile(path)
			if err != nil {
				return nil, err
			}
			files = append(files, f)
		}
		if len(resp.Data.List) == 0 || int64(page*size) >= int64(resp.Metadata.Total) {
			break
		}
		page++
	}
	return files, nil
}

// Recent 是网页版首页的「最近」: 每次转存、上传、播放各一条, 新的在前.
// 用来发现网盘变化, 比遍历目录便宜得多.
func (c *WebClient) Recent(size int) ([]RecentRecord, error) {
	const path = "/behavior/recent/list"
	text, err := c.request(path, "GET", []param{{"item_size", strconv.Itoa(size)}}, nil)
	if err != nil {
		return nil, err
	}
	var resp recentResp
	if err := decodeJSON(path, text, &resp); err != nil {
		return nil, err
	}
	out := make([]RecentRecord, 0, len(resp.Data.List))
	for _, r := range resp.Data.List {
		files := make([]File, 0, len(r.Files))
		for _, it := range r.Files {
			f, err := it.toFile(path)
			if err != nil {
				return nil, err
			}
			files = append(files, f)
		}
		out = append(out, RecentRecord{Source: string(r.Source), Time: int64(r.MaxTm), Files: files})
	}
	return out, nil
}

// Recycled 是回收站, 最近删除的在前. 「最近」里看不到删除, 靠这个发现.
func (c *WebClient) Recycled(size int) ([]RecycledFile, error) {
	const path = "/file/recycle/list"
	text, err := c.request(path, "GET", []param{{"_page", "1"}, {"_size", strconv.Itoa(size)}}, nil)
	if err != nil {
		return nil, err
	}
	var resp recycleResp
	if err := decodeJSON(path, text, &resp); err != nil {
		return nil, err
	}
	out := make([]RecycledFile, 0, len(resp.Data.List))
	for _, it := range resp.Data.List {
		if it.Fid == nil {
			return nil, &Error{Msg: path + ": bad response: missing fid"}
		}
		out = append(out, RecycledFile{
			Fid:       string(*it.Fid),
			Name:      unescapeHTML(string(it.FileName)),
			IsDir:     it.FileType == 0,
			ParentFid: string(it.PdirFid),
			Time:      int64(it.MoveRecycleAt),
		})
	}
	return out, nil
}

// Resolve 按 "/电影/沙丘" 这样的路径找文件或目录, 不存在返回 nil, nil.
func (c *WebClient) Resolve(path string) (*File, error) {
	current := File{Fid: Root, IsDir: true}
	for _, part := range strings.Split(path, "/") {
		if part == "" {
			continue
		}
		list, err := c.List(current.Fid)
		if err != nil {
			return nil, err
		}
		found := false
		for _, f := range list {
			if f.Name == part {
				current = f
				found = true
				break
			}
		}
		if !found {
			return nil, nil
		}
	}
	return &current, nil
}

// DownloadLink 取直链, 请求时要带 Cookie/Referer/User-Agent.
func (c *WebClient) DownloadLink(fid string) (Link, error) {
	const path = "/file/download"
	text, err := c.request(path, "POST", nil, obj{{"fids", []string{fid}}})
	if err != nil {
		return Link{}, err
	}
	var resp downResp
	if err := decodeJSON(path, text, &resp); err != nil {
		return Link{}, err
	}
	if len(resp.Data) == 0 {
		return Link{}, &Error{Msg: "download: no data"}
	}
	item := resp.Data[0]
	return Link{
		URL:     string(item.DownloadURL),
		Headers: map[string]string{"Cookie": c.Cookie(), "Referer": webReferer, "User-Agent": WebUserAgent},
		Size:    int64(item.Size),
	}, nil
}

// Download 下载整个文件到内存 (只用于 NFO、索引这类小文件).
func (c *WebClient) Download(fid string) ([]byte, error) {
	link, err := c.DownloadLink(fid)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest("GET", link.URL, nil)
	if err != nil {
		return nil, err
	}
	for k, v := range link.Headers {
		req.Header.Set(k, v)
	}
	resp, body, err := doRequest(c.hc, req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, &Error{Msg: fmt.Sprintf("download %d", resp.StatusCode)}
	}
	return body, nil
}

// Thumbnail 取夸克为视频生成的缩略图 (列目录时给的 thumbnail 地址), 要带 Cookie.
func (c *WebClient) Thumbnail(url string) ([]byte, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cookie", c.Cookie())
	req.Header.Set("Referer", webReferer)
	req.Header.Set("User-Agent", WebUserAgent)
	resp, body, err := doRequest(c.hc, req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, &Error{Msg: fmt.Sprintf("缩略图 %d", resp.StatusCode)}
	}
	return body, nil
}

// Mkdir 建目录, 返回新目录的 fid.
func (c *WebClient) Mkdir(parentFid, name string) (string, error) {
	text, err := c.request("/file", "POST", nil, obj{
		{"dir_init_lock", false},
		{"dir_path", ""},
		{"file_name", name},
		{"pdir_fid", parentFid},
	})
	if err != nil {
		return "", err
	}
	m, _ := parseObject(text)
	data, ok := objectField(m, "data")
	if !ok {
		return "", &Error{Msg: "mkdir: no fid"}
	}
	return stringField(data, "fid"), nil
}

// Delete 删除文件/目录 (进回收站).
func (c *WebClient) Delete(fids []string) error {
	_, err := c.request("/file/delete", "POST", nil, obj{
		{"action_type", 1},
		{"exclude_fids", []string{}},
		{"filelist", append([]string{}, fids...)},
	})
	return err
}

// Rename 重命名.
func (c *WebClient) Rename(fid, newName string) error {
	_, err := c.request("/file/rename", "POST", nil, obj{
		{"fid", fid},
		{"file_name", newName},
	})
	return err
}

// Upload 上传一个内存里的小文件 (NFO、索引、图片), 返回新文件的 fid.
// 夸克不会覆盖同名文件, 需要覆盖时调用方先删旧的.
func (c *WebClient) Upload(parentFid, name string, data []byte) (string, error) {
	return c.upload(parentFid, name, data, guessMime(name))
}

func (c *WebClient) upload(parentFid, name string, data []byte, mimeType string) (string, error) {
	const prePath = "/file/upload/pre"
	now := time.Now().UnixMilli()
	preText, err := c.request(prePath, "POST", nil, obj{
		{"ccp_hash_update", true},
		{"dir_name", ""},
		{"file_name", name},
		{"format_type", mimeType},
		{"l_created_at", now},
		{"l_updated_at", now},
		{"pdir_fid", parentFid},
		{"size", len(data)},
	})
	if err != nil {
		return "", err
	}
	var pre upPreResp
	if err := decodeJSON(prePath, preText, &pre); err != nil {
		return "", err
	}
	if pre.Data == nil {
		return "", &Error{Msg: prePath + ": bad response: missing data"}
	}

	// 先报 hash, 网盘里已有相同内容时直接秒传
	const hashPath = "/file/update/hash"
	hashText, err := c.request(hashPath, "POST", nil, obj{
		{"md5", hexOf("MD5", data)},
		{"sha1", hexOf("SHA-1", data)},
		{"task_id", string(pre.Data.TaskID)},
	})
	if err != nil {
		return "", err
	}
	var hash hashResp
	if err := decodeJSON(hashPath, hashText, &hash); err != nil {
		return "", err
	}
	if hash.Data.Finish {
		return ifEmpty(string(hash.Data.Fid), string(pre.Data.Fid)), nil
	}

	partSize := int(pre.Metadata.PartSize)
	if partSize <= 0 {
		partSize = max(len(data), 1)
	}
	var etags []string
	offset := 0
	partNumber := 1
	for {
		end := min(offset+partSize, len(data))
		etag, err := c.uploadPart(pre.Data, mimeType, partNumber, data[offset:end])
		if err != nil {
			return "", err
		}
		etags = append(etags, etag)
		offset = end
		partNumber++
		if offset >= len(data) {
			break
		}
	}

	if err := c.commit(pre.Data, etags); err != nil {
		return "", err
	}
	if _, err := c.request("/file/upload/finish", "POST", nil, obj{
		{"obj_key", string(pre.Data.ObjKey)},
		{"task_id", string(pre.Data.TaskID)},
	}); err != nil {
		return "", err
	}
	return string(pre.Data.Fid), nil
}

func (c *WebClient) uploadPart(pre *upPreData, mimeType string, partNumber int, part []byte) (string, error) {
	date := gmtDate()
	authMeta := "PUT\n\n" + mimeType + "\n" + date + "\nx-oss-date:" + date + "\nx-oss-user-agent:" + ossUA + "\n" +
		"/" + string(pre.Bucket) + "/" + string(pre.ObjKey) + "?partNumber=" + strconv.Itoa(partNumber) + "&uploadId=" + string(pre.UploadID)
	authKey, err := c.uploadAuth(pre, authMeta)
	if err != nil {
		return "", err
	}
	url := withQuery(ossURL(pre), []param{{"partNumber", strconv.Itoa(partNumber)}, {"uploadId", string(pre.UploadID)}})
	req, err := http.NewRequest("PUT", url, bytes.NewReader(part))
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", authKey)
	req.Header.Set("Content-Type", mimeType)
	req.Header.Set("Referer", webReferer+"/")
	// 小写头名原样发出, 和原版一致
	req.Header["x-oss-date"] = []string{date}
	req.Header["x-oss-user-agent"] = []string{ossUA}
	resp, body, err := doRequest(c.hc, req)
	if err != nil {
		return "", err
	}
	if resp.StatusCode != 200 {
		return "", &Error{Msg: fmt.Sprintf("upload part %d: %s", resp.StatusCode, take(string(body), 300))}
	}
	etags := resp.Header.Values("Etag")
	if len(etags) == 0 {
		return "", &Error{Msg: "upload part: no etag"}
	}
	return etags[len(etags)-1], nil
}

func (c *WebClient) commit(pre *upPreData, etags []string) error {
	var x strings.Builder
	x.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUpload>\n")
	for i, etag := range etags {
		x.WriteString("<Part>\n<PartNumber>" + strconv.Itoa(i+1) + "</PartNumber>\n<ETag>" + etag + "</ETag>\n</Part>\n")
	}
	x.WriteString("</CompleteMultipartUpload>")
	xml := []byte(x.String())
	sum := md5.Sum(xml)
	contentMD5 := base64.StdEncoding.EncodeToString(sum[:])
	cbJSON, err := marshalNoEscape(obj{
		{"callbackUrl", string(pre.Callback.CallbackURL)},
		{"callbackBody", string(pre.Callback.CallbackBody)},
	})
	if err != nil {
		return err
	}
	callback := base64.StdEncoding.EncodeToString(cbJSON)
	date := gmtDate()
	authMeta := "POST\n" + contentMD5 + "\napplication/xml\n" + date + "\nx-oss-callback:" + callback + "\nx-oss-date:" + date + "\n" +
		"x-oss-user-agent:" + ossUA + "\n/" + string(pre.Bucket) + "/" + string(pre.ObjKey) + "?uploadId=" + string(pre.UploadID)
	authKey, err := c.uploadAuth(pre, authMeta)
	if err != nil {
		return err
	}
	url := withQuery(ossURL(pre), []param{{"uploadId", string(pre.UploadID)}})
	req, err := http.NewRequest("POST", url, bytes.NewReader(xml))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", authKey)
	req.Header.Set("Content-MD5", contentMD5)
	// Content-Type 必须恰好是 application/xml, 不能带 "; charset=utf-8", 否则和签名里的对不上
	req.Header.Set("Content-Type", "application/xml")
	req.Header.Set("Referer", webReferer+"/")
	req.Header["x-oss-callback"] = []string{callback}
	req.Header["x-oss-date"] = []string{date}
	req.Header["x-oss-user-agent"] = []string{ossUA}
	resp, body, err := doRequest(c.hc, req)
	if err != nil {
		return err
	}
	if resp.StatusCode != 200 {
		return &Error{Msg: fmt.Sprintf("upload commit %d: %s", resp.StatusCode, take(string(body), 300))}
	}
	return nil
}

func (c *WebClient) uploadAuth(pre *upPreData, authMeta string) (string, error) {
	text, err := c.request("/file/upload/auth", "POST", nil, obj{
		{"auth_info", string(pre.AuthInfo)},
		{"auth_meta", authMeta},
		{"task_id", string(pre.TaskID)},
	})
	if err != nil {
		return "", err
	}
	m, _ := parseObject(text)
	data, _ := objectField(m, "data")
	key := stringField(data, "auth_key")
	if key == "" {
		return "", &Error{Msg: "upload auth: no key"}
	}
	return key, nil
}

// ossURL: upload_url 形如 http://xxx, 拼成 https://bucket.xxx/objKey
func ossURL(pre *upPreData) string {
	u := string(pre.UploadURL)
	if i := strings.Index(u, "://"); i >= 0 {
		u = u[i+3:]
	}
	return "https://" + string(pre.Bucket) + "." + u + "/" + string(pre.ObjKey)
}

// request 调网页端接口, 返回响应原文. 登录失效返回 *AuthError, 其它接口错误返回 *Error.
func (c *WebClient) request(path, method string, query []param, body obj) ([]byte, error) {
	ps := append([]param{{"pr", "ucpro"}, {"fr", "pc"}}, query...)
	var rd io.Reader
	if body != nil {
		b, err := body.MarshalJSON()
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, withQuery(c.API+path, ps), rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cookie", c.Cookie())
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Referer", webReferer)
	req.Header.Set("User-Agent", WebUserAgent)
	if body != nil {
		// OkHttp 用字符串建请求体时会自动加上 charset
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	resp, text, err := doRequest(c.hc, req)
	if resp != nil {
		c.updateCookie(resp)
	}
	if err != nil {
		return nil, err
	}
	m, err := parseObject(text)
	if err != nil {
		return nil, &Error{Msg: path + ": bad response: " + take(string(text), 200)}
	}
	status := intField(m, "status")
	code := intField(m, "code")
	message := stringField(m, "message")
	if status == 401 || strings.Contains(strings.ToLower(message), "login") || strings.Contains(message, "登录") {
		return nil, &AuthError{Msg: fmt.Sprintf("%s: 登录失效 status=%d code=%d %s", path, status, code, message)}
	}
	if status >= 400 || code != 0 {
		return nil, &Error{Msg: fmt.Sprintf("%s: status=%d code=%d %s", path, status, code, message)}
	}
	return text, nil
}

// updateCookie: 夸克会滚动下发 __puus, 不更新的话 cookie 过一阵就失效.
func (c *WebClient) updateCookie(resp *http.Response) {
	puus := ""
	for _, h := range resp.Header.Values("Set-Cookie") {
		pair, _, _ := strings.Cut(h, ";")
		if strings.HasPrefix(pair, "__puus=") {
			puus = pair
			break
		}
	}
	if puus == "" {
		return
	}
	c.mu.Lock()
	var parts []string
	for _, p := range strings.Split(c.cookie, ";") {
		p = strings.TrimSpace(p)
		if p != "" && !strings.HasPrefix(p, "__puus=") {
			parts = append(parts, p)
		}
	}
	updated := strings.Join(append(parts, puus), "; ")
	changed := updated != c.cookie
	if changed {
		c.cookie = updated
	}
	c.mu.Unlock()
	if changed && c.onCookieChange != nil {
		// 并发时回调可能晚到, 所以交出去的是回调时刻最新的 cookie, 不是这次算出来的
		c.cbMu.Lock()
		defer c.cbMu.Unlock()
		c.onCookieChange(c.Cookie())
	}
}

func gmtDate() string {
	return time.Now().UTC().Format("Mon, 02 Jan 2006 15:04:05 GMT")
}

// unescapeHTML 依次替换, 和原版的链式 replace 结果一致 (先换 &amp;).
func unescapeHTML(s string) string {
	s = strings.ReplaceAll(s, "&amp;", "&")
	s = strings.ReplaceAll(s, "&lt;", "<")
	s = strings.ReplaceAll(s, "&gt;", ">")
	s = strings.ReplaceAll(s, "&quot;", "\"")
	s = strings.ReplaceAll(s, "&#39;", "'")
	return s
}

func guessMime(name string) string {
	ext := name
	if i := strings.LastIndexByte(name, '.'); i >= 0 {
		ext = name[i+1:]
	}
	switch strings.ToLower(ext) {
	case "nfo", "xml":
		return "application/xml"
	case "json":
		return "application/json"
	case "gz":
		return "application/gzip"
	case "jpg", "jpeg":
		return "image/jpeg"
	case "png":
		return "image/png"
	case "webp":
		return "image/webp"
	}
	return "application/octet-stream"
}

// objectField 取 m[name] 当 JSON 对象, 缺失/null/不是对象时 ok=false.
func objectField(m map[string]json.RawMessage, name string) (map[string]json.RawMessage, bool) {
	raw, ok := m[name]
	if !ok {
		return nil, false
	}
	sub, err := parseObject(raw)
	if err != nil {
		return nil, false
	}
	return sub, true
}

// ---- 响应结构 ----

type sortResp struct {
	Data struct {
		List []webFile `json:"list"`
	} `json:"data"`
	Metadata struct {
		Total jInt `json:"_total"`
	} `json:"metadata"`
}

type webFile struct {
	Fid         *jStr `json:"fid"`
	FileName    jStr  `json:"file_name"`
	Category    jInt  `json:"category"`
	Size        jInt  `json:"size"`
	File        jBool `json:"file"`
	UpdatedAt   jInt  `json:"updated_at"`
	Thumbnail   jStr  `json:"thumbnail"`
	PdirFid     jStr  `json:"pdir_fid"`
	Duration    jInt  `json:"duration"`
	VideoWidth  jInt  `json:"video_width"`
	VideoHeight jInt  `json:"video_height"`
}

// UnmarshalJSON: file 缺失时默认 true (是文件不是目录).
func (w *webFile) UnmarshalJSON(b []byte) error {
	type alias webFile
	a := alias{File: true}
	if err := json.Unmarshal(b, &a); err != nil {
		return err
	}
	*w = webFile(a)
	return nil
}

func (w webFile) toFile(path string) (File, error) {
	if w.Fid == nil {
		return File{}, &Error{Msg: path + ": bad response: missing fid"}
	}
	return File{
		Fid:       string(*w.Fid),
		Name:      unescapeHTML(string(w.FileName)),
		IsDir:     !bool(w.File),
		Size:      int64(w.Size),
		Category:  int(w.Category),
		UpdatedAt: int64(w.UpdatedAt),
		Thumbnail: string(w.Thumbnail),
		ParentFid: string(w.PdirFid),
		Duration:  int(w.Duration),
		Width:     int(w.VideoWidth),
		Height:    int(w.VideoHeight),
	}, nil
}

type recentResp struct {
	Data struct {
		List []struct {
			Source jStr      `json:"source"`
			MaxTm  jInt      `json:"max_tm"`
			Files  []webFile `json:"files"`
		} `json:"list"`
	} `json:"data"`
}

type recycleResp struct {
	Data struct {
		List []recycleItem `json:"list"`
	} `json:"data"`
}

type recycleItem struct {
	Fid           *jStr `json:"fid"`
	FileName      jStr  `json:"file_name"`
	FileType      jInt  `json:"file_type"`
	PdirFid       jStr  `json:"pdir_fid"`
	MoveRecycleAt jInt  `json:"move_recycle_at"`
}

// UnmarshalJSON: file_type 缺失时默认 1 (文件).
func (r *recycleItem) UnmarshalJSON(b []byte) error {
	type alias recycleItem
	a := alias{FileType: 1}
	if err := json.Unmarshal(b, &a); err != nil {
		return err
	}
	*r = recycleItem(a)
	return nil
}

type downResp struct {
	Data []struct {
		DownloadURL jStr `json:"download_url"`
		Size        jInt `json:"size"`
	} `json:"data"`
}

type upPreResp struct {
	Data     *upPreData `json:"data"`
	Metadata struct {
		PartSize jInt `json:"part_size"`
	} `json:"metadata"`
}

type upPreData struct {
	TaskID    jStr `json:"task_id"`
	UploadID  jStr `json:"upload_id"`
	ObjKey    jStr `json:"obj_key"`
	UploadURL jStr `json:"upload_url"`
	Fid       jStr `json:"fid"`
	Bucket    jStr `json:"bucket"`
	Callback  struct {
		CallbackURL  jStr `json:"callbackUrl"`
		CallbackBody jStr `json:"callbackBody"`
	} `json:"callback"`
	AuthInfo jStr `json:"auth_info"`
}

type hashResp struct {
	Data struct {
		Finish jBool `json:"finish"`
		Fid    jStr  `json:"fid"`
	} `json:"data"`
}
