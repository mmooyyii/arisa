package quark

import (
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strings"
	"testing"
)

func TestWebLogin(t *testing.T) {
	confirmed := false
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		switch r.Header.Get("X-Orig-Host") + r.URL.Path {
		case "uop.quark.cn/cas/ajax/getTokenForQrcodeLogin":
			if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(q.Get("request_id")) {
				t.Errorf("request_id %s", q.Get("request_id"))
			}
			w.Header().Add("Set-Cookie", "z=1; Path=/")
			writeJSON(w, `{"status":2000000,"data":{"members":{"token":"TK"}}}`)
		case "uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken":
			if r.Header.Get("Cookie") != "z=1" {
				t.Errorf("cookie %q", r.Header.Get("Cookie"))
			}
			if !confirmed {
				writeJSON(w, `{"status":50004001,"message":"not scanned"}`)
				return
			}
			writeJSON(w, `{"status":"2000000","data":{"members":{"service_ticket":"ST"}}}`)
		case "pan.quark.cn/account/info":
			if q.Get("st") != "ST" || q.Get("lw") != "scan" {
				t.Errorf("account/info %s", r.URL.RawQuery)
			}
			w.Header().Add("Set-Cookie", "__pus=P; Path=/")
			w.Header().Add("Set-Cookie", "a=x=y; Path=/")
			w.Header().Add("Set-Cookie", "z=2; Path=/")
			writeJSON(w, `{"status":200}`)
		default:
			t.Errorf("unexpected %s", r.URL)
		}
	})
	l := NewWebLogin()
	l.hc = hc
	content, token, err := l.Start()
	if err != nil {
		t.Fatal(err)
	}
	if token != "TK" || content != "https://su.quark.cn/4_eMHBJ?token=TK&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400" {
		t.Errorf("start %s %s", content, token)
	}
	if c, err := l.Poll(token); c != "" || err != nil {
		t.Errorf("pending: %q %v", c, err)
	}
	confirmed = true
	c, err := l.Poll(token)
	if err != nil {
		t.Fatal(err)
	}
	// 保持第一次出现的顺序, z 被覆盖但位置不变
	if c != "z=2; __pus=P; a=x=y" {
		t.Errorf("cookie %q", c)
	}
}

func TestTvRefreshAndList(t *testing.T) {
	tokenCalls := 0
	listCalls := 0
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		switch r.Header.Get("X-Orig-Host") + r.URL.Path {
		case "code.test/quarkdrive/token":
			tokenCalls++
			body, _ := io.ReadAll(r.Body)
			var m map[string]any
			_ = json.Unmarshal(body, &m)
			if m["refresh_token"] != "R" || m["device_id"] != "dev" || m["code"] != nil {
				t.Errorf("token body %s", body)
			}
			if !strings.HasPrefix(string(body), `{"req_id":"`) {
				t.Errorf("token body order %s", body)
			}
			if tokenCalls == 1 {
				writeJSON(w, `{"code":200,"data":{"access_token":"A1","refresh_token":"R"}}`)
			} else {
				writeJSON(w, `{"code":"200","data":{"access_token":"A2","refresh_token":"R"}}`)
			}
		case "tv.test/file":
			if !strings.HasPrefix(r.URL.RawQuery, "req_id=") || !strings.Contains(r.URL.RawQuery, "&access_token=") ||
				!strings.Contains(r.URL.RawQuery, "&channel=GENERAL&method=list&parent_fid=0&") {
				t.Errorf("query %s", r.URL.RawQuery)
			}
			if r.Header.Get("X-Pan-Client-Id") != tvClientID || len(r.Header.Get("X-Pan-Token")) != 64 {
				t.Errorf("headers %v", r.Header)
			}
			listCalls++
			if q.Get("access_token") == "A1" {
				// token 失效, 刷新后重试
				writeJSON(w, `{"status":-1,"errno":10001,"error_info":"x"}`)
				return
			}
			if q.Get("page_index") == "0" {
				var files []string
				for i := 0; i < 100; i++ {
					files = append(files, `{"fid":"a","filename":"n","isdir":0,"size":"1024","category":1}`)
				}
				files[0] = `{"fid":"d","filename":"dir","isdir":1,"thumbnail_url":""}`
				writeJSON(w, `{"status":200,"errno":0,"data":{"total_count":"101","files":[`+strings.Join(files, ",")+`]}}`)
			} else {
				writeJSON(w, `{"status":200,"errno":0,"data":{"total_count":101,"files":[{"fid":"last","size":5}]}}`)
			}
		default:
			t.Errorf("unexpected %s", r.URL)
		}
	})
	var sessions []TvSession
	c := NewTvClient(TvSession{DeviceID: "dev", RefreshToken: "R"}, func(s TvSession) { sessions = append(sessions, s) })
	c.API = "https://tv.test"
	c.CodeAPI = "http://code.test/quarkdrive"
	c.hc = hc
	files, err := c.List(Root)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 101 || !files[0].IsDir || files[1].IsDir || files[1].Size != 1024 || files[100].Fid != "last" {
		t.Errorf("files %d %+v %+v", len(files), files[0], files[1])
	}
	if tokenCalls != 2 || listCalls != 3 {
		t.Errorf("tokenCalls %d listCalls %d", tokenCalls, listCalls)
	}
	if c.Session().AccessToken != "A2" || len(sessions) != 2 || sessions[1].AccessToken != "A2" {
		t.Errorf("session %+v %+v", c.Session(), sessions)
	}
}

func TestTvDownloadAndStreaming(t *testing.T) {
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Query().Get("method") {
		case "download":
			writeJSON(w, `{"status":200,"errno":0,"data":{"download_url":"https://dl/x","size":"12345"}}`)
		case "streaming":
			writeJSON(w, `{"status":200,"errno":0,"data":{"video_info":[{"resolution":"4k","url":"u","size":"9","width":3840},{"resolution":"low","url":""}]}}`)
		case "user_info":
			writeJSON(w, `{"status":200,"errno":5,"error_info":"boom"}`)
		}
	})
	c := NewTvClient(TvSession{DeviceID: "dev", RefreshToken: "R", AccessToken: "A"}, nil)
	c.API = "https://tv.test"
	c.hc = hc
	l, err := c.DownloadLink("f")
	if err != nil || l.URL != "https://dl/x" || l.Size != 12345 || l.Headers["User-Agent"] != TvUserAgent {
		t.Errorf("%+v %v", l, err)
	}
	vs, err := c.StreamingLinks("f")
	if err != nil || len(vs) != 1 || vs[0].Size != 9 || vs[0].Width != 3840 {
		t.Errorf("%+v %v", vs, err)
	}
	_, err = c.UserInfo()
	if err == nil || IsAuth(err) || err.Error() != "/user: status=200 errno=5 boom" {
		t.Errorf("%v", err)
	}
}

func TestTvPollLogin(t *testing.T) {
	code := ""
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oauth/code":
			if code == "" {
				writeJSON(w, `{"status":-1,"errno":40001,"error_info":"wait"}`)
				return
			}
			writeJSON(w, `{"status":200,"errno":0,"code":"`+code+`"}`)
		case "/quarkdrive/token":
			body, _ := io.ReadAll(r.Body)
			if !strings.Contains(string(body), `"code":"C"`) {
				t.Errorf("token body %s", body)
			}
			writeJSON(w, `{"code":200,"data":{"access_token":"A","refresh_token":"R"}}`)
		}
	})
	c := NewTvClient(NewTvDevice(), nil)
	c.API = "https://tv.test"
	c.CodeAPI = "http://code.test/quarkdrive"
	c.hc = hc
	if ok, err := c.PollLogin("q"); ok || err != nil {
		t.Errorf("pending %v %v", ok, err)
	}
	code = "C"
	if ok, err := c.PollLogin("q"); !ok || err != nil {
		t.Errorf("done %v %v", ok, err)
	}
	if s := c.Session(); s.RefreshToken != "R" || s.AccessToken != "A" || len(s.DeviceID) != 32 {
		t.Errorf("session %+v", s)
	}
	b, _ := json.Marshal(TvSession{DeviceID: "d"})
	if string(b) != `{"deviceId":"d"}` {
		t.Errorf("%s", b)
	}
}
