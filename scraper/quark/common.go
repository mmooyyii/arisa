package quark

import (
	"bytes"
	"crypto/md5"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Root 是网盘根目录的 fid.
const Root = "0"

// okhttpUA 是原版 (OkHttp 4.12.0) 没设 User-Agent 时默认带的值, 照着带上.
const okhttpUA = "okhttp/4.12.0"

// newHTTPClient 对应原版的 defaultHttpClient: 连接超时 15 秒, 读超时 60 秒.
func newHTTPClient() *http.Client {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	tr.TLSHandshakeTimeout = 15 * time.Second
	tr.ResponseHeaderTimeout = 60 * time.Second
	return &http.Client{
		Transport: tr,
		Timeout:   5 * time.Minute,
		// OkHttp 跟随重定向时会保留 Cookie 头, Go 默认换域名就丢掉, 这里补回去
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 20 {
				return errors.New("stopped after 20 redirects")
			}
			if c := via[0].Header.Get("Cookie"); c != "" && req.Header.Get("Cookie") == "" {
				req.Header.Set("Cookie", c)
			}
			return nil
		},
	}
}

var defaultHTTPClient = newHTTPClient()

// doRequest 发请求并读完响应体. 没设 User-Agent 时和原版一样带 OkHttp 的默认值.
func doRequest(hc *http.Client, req *http.Request) (*http.Response, []byte, error) {
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", okhttpUA)
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp, nil, err
	}
	return resp, body, nil
}

// param 是一个查询参数; 用切片保证顺序 (pr/fr 在前), 和原版拼出来的 URL 一致.
type param struct{ k, v string }

// okhttpQueryEncodeSet 是 OkHttp addQueryParameter 会转义的字符.
const okhttpQueryEncodeSet = " !\"#$&'(),/:;<=>?@[]\\^`{|}~%+"

// queryEscape 按 OkHttp addQueryParameter 的规则转义: 控制字符、非 ASCII 和上面那些符号转成 %XX.
func queryEscape(s string) string {
	const hexDigits = "0123456789ABCDEF"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < 0x20 || c >= 0x7f || strings.IndexByte(okhttpQueryEncodeSet, c) >= 0 {
			b.WriteByte('%')
			b.WriteByte(hexDigits[c>>4])
			b.WriteByte(hexDigits[c&15])
		} else {
			b.WriteByte(c)
		}
	}
	return b.String()
}

// withQuery 在 base 后面按顺序拼上查询参数.
func withQuery(base string, ps []param) string {
	if len(ps) == 0 {
		return base
	}
	var b strings.Builder
	b.WriteString(base)
	if strings.Contains(base, "?") {
		b.WriteByte('&')
	} else {
		b.WriteByte('?')
	}
	for i, p := range ps {
		if i > 0 {
			b.WriteByte('&')
		}
		b.WriteString(queryEscape(p.k))
		b.WriteByte('=')
		b.WriteString(queryEscape(p.v))
	}
	return b.String()
}

// obj 是按插入顺序输出的 JSON 对象, 对应原版的 buildJsonObject.
type obj []field

// field 是 obj 里的一个键值.
type field struct {
	k string
	v any
}

func (o obj) MarshalJSON() ([]byte, error) {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, p := range o {
		if i > 0 {
			b.WriteByte(',')
		}
		k, err := marshalNoEscape(p.k)
		if err != nil {
			return nil, err
		}
		b.Write(k)
		b.WriteByte(':')
		v, err := marshalNoEscape(p.v)
		if err != nil {
			return nil, err
		}
		b.Write(v)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

// marshalNoEscape 和 kotlinx 一样不把 < > & 转成 \u003c 这类.
func marshalNoEscape(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(b.Bytes(), "\n"), nil
}

// hexOf 对应原版的 hex(algorithm, bytes): 摘要转小写十六进制.
func hexOf(algorithm string, data []byte) string {
	switch algorithm {
	case "MD5":
		s := md5.Sum(data)
		return hex.EncodeToString(s[:])
	case "SHA-1":
		s := sha1.Sum(data)
		return hex.EncodeToString(s[:])
	case "SHA-256":
		s := sha256.Sum256(data)
		return hex.EncodeToString(s[:])
	}
	panic("unknown algorithm " + algorithm)
}

// take 取前 n 个字符, 对应 Kotlin 的 String.take.
func take(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

// ---- 宽松的 JSON 字段: 夸克的数字有时是字符串有时是数字 ----

// intField 对应原版的 intField: 字段缺失、不是整数 (intOrNull 为 null) 时都当 0.
func intField(m map[string]json.RawMessage, name string) int {
	raw, ok := m[name]
	if !ok {
		return 0
	}
	s, ok := primitiveContent(raw)
	if !ok {
		return 0
	}
	n, err := strconv.ParseInt(s, 10, 32)
	if err != nil {
		return 0
	}
	return int(n)
}

// stringField 对应原版的 stringField: 缺失时为空串, 数字等原样取文本.
func stringField(m map[string]json.RawMessage, name string) string {
	raw, ok := m[name]
	if !ok {
		return ""
	}
	s, _ := primitiveContent(raw)
	return s
}

// primitiveContent 取 JSON 基本值的文本 (相当于 kotlinx 的 JsonPrimitive.content).
// 字符串去掉引号, 数字/布尔取原文. null、对象、数组返回 ("", false).
func primitiveContent(raw json.RawMessage) (string, bool) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return "", false
	}
	switch raw[0] {
	case '"':
		var s string
		if err := json.Unmarshal(raw, &s); err != nil {
			return "", false
		}
		return s, true
	case '{', '[', 'n':
		return "", false
	}
	return string(raw), true
}

// parseObject 把响应解析成 JSON 对象, 不是对象就报错 (原版 .jsonObject).
func parseObject(text []byte) (map[string]json.RawMessage, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(text, &m); err != nil || m == nil {
		return nil, errors.New("not a json object")
	}
	return m, nil
}

// jInt 是宽松的整数: 接受数字或数字字符串, null 保持默认值, 解析不了当 0.
type jInt int64

func (n *jInt) UnmarshalJSON(b []byte) error {
	raw := bytes.TrimSpace(b)
	if string(raw) == "null" {
		return nil
	}
	s, ok := primitiveContent(raw)
	if !ok {
		*n = 0
		return nil
	}
	if v, err := strconv.ParseInt(s, 10, 64); err == nil {
		*n = jInt(v)
		return nil
	}
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		*n = jInt(int64(f))
		return nil
	}
	*n = 0
	return nil
}

// jStr 是宽松的字符串: 数字/布尔取原文, null 保持默认值.
type jStr string

func (s *jStr) UnmarshalJSON(b []byte) error {
	raw := bytes.TrimSpace(b)
	if string(raw) == "null" {
		return nil
	}
	v, _ := primitiveContent(raw)
	*s = jStr(v)
	return nil
}

// jBool 是宽松的布尔: 接受 true/false 或 "true"/"false", null 保持默认值.
type jBool bool

func (v *jBool) UnmarshalJSON(b []byte) error {
	raw := bytes.TrimSpace(b)
	if string(raw) == "null" {
		return nil
	}
	s, _ := primitiveContent(raw)
	switch strings.ToLower(s) {
	case "true":
		*v = true
	case "false":
		*v = false
	default:
		if n, err := strconv.ParseFloat(s, 64); err == nil {
			*v = n != 0
		}
	}
	return nil
}

// decodeJSON 把整个响应解到结构体里, 失败时报 bad response.
func decodeJSON(path string, text []byte, v any) error {
	if err := json.Unmarshal(text, v); err != nil {
		return &Error{Msg: path + ": bad response: " + err.Error()}
	}
	return nil
}

func ifEmpty(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
