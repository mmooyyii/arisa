package quark

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
)

const (
	// WebLoginUOP 是扫码登录接口的默认地址.
	WebLoginUOP     = "https://uop.quark.cn"
	webLoginClient  = "532"
	webLoginQrShort = "https://su.quark.cn/4_eMHBJ"
)

// WebLogin 是夸克网页版 (pan.quark.cn) 扫码登录, 拿到和浏览器一样的 Cookie, 给 WebClient 用.
// 网页登录不占 TV 端的设备名额 (TV 端登录多了会报「设备数超限」).
type WebLogin struct {
	// UOP 是登录接口地址, 默认 WebLoginUOP; 测试时改成假服务器.
	UOP string
	// Pan 是网页版地址 (换 Cookie 的 /account/info 也在这里), 默认 https://pan.quark.cn.
	Pan string

	mu sync.Mutex
	// cookies 按第一次出现的顺序保存 (原版用 linkedMapOf), 同名的后来者覆盖值但不挪位置.
	cookieNames []string
	cookies     map[string]string
	hc          *http.Client
}

// NewWebLogin 建一个扫码登录会话.
func NewWebLogin() *WebLogin {
	return &WebLogin{UOP: WebLoginUOP, Pan: webReferer, cookies: map[string]string{}, hc: defaultHTTPClient}
}

// Start 开始扫码登录. qrContent 是二维码里的链接, 要自己画成图; token 给 Poll 用.
func (l *WebLogin) Start() (qrContent, token string, err error) {
	m, err := l.get(l.UOP+"/cas/ajax/getTokenForQrcodeLogin", []param{
		{"client_id", webLoginClient}, {"v", "1.2"}, {"request_id", uuidV4()},
	})
	if err != nil {
		return "", "", err
	}
	members, ok := membersOf(m)
	if !ok {
		return "", "", &Error{Msg: "getTokenForQrcodeLogin: no data.members"}
	}
	token = stringField(members, "token")
	qrContent = withQuery(webLoginQrShort, []param{
		{"token", token},
		{"client_id", webLoginClient},
		{"ssb", "weblogin"},
		{"uc_param_str", ""},
		{"uc_biz_str", "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0"},
	})
	return qrContent, token, nil
}

// Poll 查扫码结果: 还没扫码/没确认时返回 "", nil; 确认后返回 Cookie.
func (l *WebLogin) Poll(token string) (string, error) {
	m, err := l.get(l.UOP+"/cas/ajax/getServiceTicketByQrcodeToken", []param{
		{"client_id", webLoginClient}, {"v", "1.2"}, {"token", token}, {"request_id", uuidV4()},
	})
	if err != nil {
		return "", err
	}
	if intField(m, "status") != 2000000 {
		return "", nil
	}
	members, ok := membersOf(m)
	if !ok {
		return "", &Error{Msg: "getServiceTicketByQrcodeToken: no data.members"}
	}
	ticket := stringField(members, "service_ticket")
	if _, err := l.get(l.Pan+"/account/info", []param{{"st", ticket}, {"lw", "scan"}}); err != nil {
		return "", err
	}
	l.mu.Lock()
	_, ok = l.cookies["__pus"]
	l.mu.Unlock()
	if !ok {
		return "", &Error{Msg: "扫码登录没有拿到 Cookie"}
	}
	return l.cookieHeader(), nil
}

// membersOf 取 data.members.
func membersOf(m map[string]json.RawMessage) (map[string]json.RawMessage, bool) {
	data, ok := objectField(m, "data")
	if !ok {
		return nil, false
	}
	return objectField(data, "members")
}

func (l *WebLogin) cookieHeader() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	parts := make([]string, 0, len(l.cookieNames))
	for _, k := range l.cookieNames {
		parts = append(parts, k+"="+l.cookies[k])
	}
	return strings.Join(parts, "; ")
}

func (l *WebLogin) get(url string, query []param) (map[string]json.RawMessage, error) {
	req, err := http.NewRequest("GET", withQuery(url, query), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", WebUserAgent)
	req.Header.Set("Referer", webReferer+"/")
	if c := l.cookieHeader(); c != "" {
		req.Header.Set("Cookie", c)
	}
	resp, text, err := doRequest(l.hc, req)
	if resp != nil {
		l.collect(resp)
	}
	if err != nil {
		return nil, err
	}
	m, err := parseObject(text)
	if err != nil {
		return nil, &Error{Msg: url + ": bad response: " + take(string(text), 200)}
	}
	return m, nil
}

// collect 记下响应里的 Set-Cookie.
func (l *WebLogin) collect(resp *http.Response) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, h := range resp.Header.Values("Set-Cookie") {
		pair, _, _ := strings.Cut(h, ";")
		name, value, _ := strings.Cut(pair, "=")
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}
		if _, ok := l.cookies[name]; !ok {
			l.cookieNames = append(l.cookieNames, name)
		}
		l.cookies[name] = value
	}
}

// uuidV4 生成随机 UUID (版本 4), 对应原版的 UUID.randomUUID().
func uuidV4() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
