package quark

import (
	"crypto/md5"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
)

// rewriteTransport 把所有请求转到假服务器上, 原来的 Host 放进 X-Orig-Host 头.
type rewriteTransport struct{ target *url.URL }

func (t rewriteTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	r := req.Clone(req.Context())
	r.Header.Set("X-Orig-Host", req.URL.Host)
	r.URL.Scheme = "http"
	r.URL.Host = t.target.Host
	r.Host = t.target.Host
	return http.DefaultTransport.RoundTrip(r)
}

func fakeServer(t *testing.T, h http.HandlerFunc) (*httptest.Server, *http.Client) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	return srv, &http.Client{Transport: rewriteTransport{u}}
}

func newTestWeb(hc *http.Client, cookie string, cb func(string)) *WebClient {
	c := NewWebClient(cookie, cb)
	c.API = "https://drive.test/1/clouddrive"
	c.hc = hc
	return c
}

func writeJSON(w http.ResponseWriter, s string) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, s)
}

func TestWebListPagingAndPuus(t *testing.T) {
	var mu sync.Mutex
	var cookies []string
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/1/clouddrive/file/sort" {
			t.Errorf("path %s", r.URL.Path)
		}
		if !strings.HasPrefix(r.URL.RawQuery, "pr=ucpro&fr=pc&pdir_fid=abc&_page=") {
			t.Errorf("query order: %s", r.URL.RawQuery)
		}
		if !strings.Contains(r.URL.RawQuery, "_sort=file_type%3Aasc%2Cfile_name%3Aasc") {
			t.Errorf("sort encoding: %s", r.URL.RawQuery)
		}
		if r.Header.Get("Referer") != webReferer || r.Header.Get("User-Agent") != WebUserAgent {
			t.Errorf("headers: %v", r.Header)
		}
		mu.Lock()
		cookies = append(cookies, r.Header.Get("Cookie"))
		mu.Unlock()
		page := r.URL.Query().Get("_page")
		var items []string
		n := 100
		if page == "2" {
			n = 50
			http.SetCookie(w, &http.Cookie{Name: "__puus", Value: "p3", Path: "/"})
		} else {
			w.Header().Add("Set-Cookie", "other=1; Path=/")
			w.Header().Add("Set-Cookie", "__puus=p2; Path=/; HttpOnly")
		}
		for i := 0; i < n; i++ {
			switch i {
			case 0:
				// file 缺失 → 文件; size 是字符串
				items = append(items, fmt.Sprintf(`{"fid":"p%s-%d","file_name":"A &amp;lt; B","size":"123","category":"1"}`, page, i))
			case 1:
				items = append(items, fmt.Sprintf(`{"fid":"p%s-%d","file_name":"dir","file":false,"thumbnail":"","pdir_fid":"abc"}`, page, i))
			default:
				items = append(items, fmt.Sprintf(`{"fid":"p%s-%d","file_name":"f","file":true,"updated_at":1700000000000}`, page, i))
			}
		}
		writeJSON(w, `{"status":200,"code":0,"data":{"list":[`+strings.Join(items, ",")+`]},"metadata":{"_total":150}}`)
	})
	var got []string
	c := newTestWeb(hc, "a=1; __puus=p1; b=2", func(s string) { got = append(got, s) })
	files, err := c.List("abc")
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 150 {
		t.Fatalf("len %d", len(files))
	}
	f0 := files[0]
	if f0.IsDir || f0.Size != 123 || f0.Name != "A < B" || f0.Category != 1 {
		t.Errorf("f0 %+v", f0)
	}
	if !files[1].IsDir || files[1].Thumbnail != "" || files[1].ParentFid != "abc" {
		t.Errorf("f1 %+v", files[1])
	}
	if files[2].IsDir || files[2].UpdatedAt != 1700000000000 {
		t.Errorf("f2 %+v", files[2])
	}
	if cookies[0] != "a=1; __puus=p1; b=2" || cookies[1] != "a=1; b=2; __puus=p2" {
		t.Errorf("cookies sent: %q", cookies)
	}
	if c.Cookie() != "a=1; b=2; __puus=p3" {
		t.Errorf("cookie %q", c.Cookie())
	}
	if len(got) != 2 || got[1] != "a=1; b=2; __puus=p3" {
		t.Errorf("callbacks %q", got)
	}
}

func TestWebErrors(t *testing.T) {
	resp := ""
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) { writeJSON(w, resp) })
	c := newTestWeb(hc, "x=1", nil)
	cases := []struct {
		body string
		auth bool
	}{
		{`{"status":401,"code":0,"message":"x"}`, true},
		{`{"status":"401","code":31001,"message":"x"}`, true},
		{`{"status":200,"code":1,"message":"请先登录"}`, true},
		{`{"status":400,"code":0,"message":"Require LOGIN"}`, true},
		{`{"status":200,"code":23008,"message":"文件不存在"}`, false},
		{`{"status":500,"code":0}`, false},
		{`<html>`, false},
		{`[1]`, false},
	}
	for _, tc := range cases {
		resp = tc.body
		_, err := c.Member()
		if err == nil {
			t.Errorf("%s: no error", tc.body)
			continue
		}
		if IsAuth(err) != tc.auth {
			t.Errorf("%s: auth=%v err=%v", tc.body, IsAuth(err), err)
		}
		var qe *Error
		if !tc.auth && !asError(err, &qe) {
			t.Errorf("%s: want *Error, got %T", tc.body, err)
		}
	}
	// status 是 1.5 这种非整数时当 0
	resp = `{"status":1.5,"code":"0","data":{"n":12345678901234567}}`
	m, err := c.Member()
	if err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(m["data"].(map[string]any)["n"]) != "12345678901234567" {
		t.Errorf("member %v", m)
	}
}

func asError(err error, target **Error) bool {
	e, ok := err.(*Error)
	if ok {
		*target = e
	}
	return ok
}

func TestWebRecycledAndRecent(t *testing.T) {
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/1/clouddrive/file/recycle/list":
			writeJSON(w, `{"status":200,"code":0,"data":{"list":[{"fid":"a","file_name":"x&#39;s"},{"fid":"b","file_type":0,"pdir_fid":"p","move_recycle_at":"5"}]}}`)
		case "/1/clouddrive/behavior/recent/list":
			if r.URL.Query().Get("item_size") != "7" {
				t.Errorf("item_size")
			}
			writeJSON(w, `{"status":200,"code":0,"data":{"list":[{"source":"SAVE_SHARE","max_tm":9,"files":[{"fid":"f"}]}]}}`)
		}
	})
	c := newTestWeb(hc, "", nil)
	rs, err := c.Recycled(50)
	if err != nil {
		t.Fatal(err)
	}
	if rs[0].IsDir || rs[0].Name != "x's" || rs[0].ParentFid != "" || !rs[1].IsDir || rs[1].ParentFid != "p" || rs[1].Time != 5 {
		t.Errorf("recycled %+v", rs)
	}
	rec, err := c.Recent(7)
	if err != nil {
		t.Fatal(err)
	}
	if len(rec) != 1 || rec[0].Source != "SAVE_SHARE" || rec[0].Time != 9 || rec[0].Files[0].Fid != "f" || rec[0].Files[0].IsDir {
		t.Errorf("recent %+v", rec)
	}
}

// uploadServer 模拟上传的全部接口. finish 为 true 时 hash 接口直接秒传.
func uploadServer(t *testing.T, instant bool, calls *[]string) *http.Client {
	var mu sync.Mutex
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		host := r.Header.Get("X-Orig-Host")
		mu.Lock()
		*calls = append(*calls, r.Method+" "+host+r.URL.Path)
		mu.Unlock()
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		switch {
		case host == "drive.test":
			if r.Method == "POST" && r.Header.Get("Content-Type") != "application/json; charset=utf-8" {
				t.Errorf("api content-type %q", r.Header.Get("Content-Type"))
			}
			switch r.URL.Path {
			case "/1/clouddrive/file/upload/pre":
				if req["file_name"] != "a.nfo" || req["format_type"] != "application/xml" || req["size"] != float64(10) || req["pdir_fid"] != "dir" {
					t.Errorf("pre %v", req)
				}
				writeJSON(w, `{"status":200,"code":0,"data":{"task_id":"T","upload_id":"U","obj_key":"o/k","upload_url":"http://oss.example.com","fid":"F","bucket":"bkt","auth_info":"AI","callback":{"callbackUrl":"cu","callbackBody":"a=${x}&b=1","extra":1}},"metadata":{"part_size":4}}`)
			case "/1/clouddrive/file/update/hash":
				sum := md5.Sum([]byte("0123456789"))
				if req["md5"] != fmt.Sprintf("%x", sum) || req["task_id"] != "T" {
					t.Errorf("hash %v", req)
				}
				if instant {
					writeJSON(w, `{"status":200,"code":0,"data":{"finish":true,"fid":"F2"}}`)
				} else {
					writeJSON(w, `{"status":200,"code":0,"data":{"finish":false}}`)
				}
			case "/1/clouddrive/file/upload/auth":
				meta := req["auth_meta"].(string)
				writeJSON(w, fmt.Sprintf(`{"status":200,"code":0,"data":{"auth_key":"KEY:%s"}}`, base64.StdEncoding.EncodeToString([]byte(meta))))
			case "/1/clouddrive/file/upload/finish":
				if req["obj_key"] != "o/k" || req["task_id"] != "T" {
					t.Errorf("finish %v", req)
				}
				writeJSON(w, `{"status":200,"code":0}`)
			default:
				t.Errorf("unexpected %s", r.URL.Path)
			}
		case host == "bkt.oss.example.com":
			if r.URL.Path != "/o/k" || r.Header.Get("Referer") != webReferer+"/" ||
				r.Header.Get("X-Oss-User-Agent") != ossUA || r.Header.Get("X-Oss-Date") == "" {
				t.Errorf("oss headers %v %s", r.Header, r.URL)
			}
			meta := authMeta(t, r.Header.Get("Authorization"))
			if r.Method == "PUT" {
				pn := r.URL.Query().Get("partNumber")
				if r.URL.RawQuery != "partNumber="+pn+"&uploadId=U" {
					t.Errorf("part query %s", r.URL.RawQuery)
				}
				if r.Header.Get("Content-Type") != "application/xml" {
					t.Errorf("part content-type %q", r.Header.Get("Content-Type"))
				}
				want := "PUT\n\napplication/xml\n" + r.Header.Get("X-Oss-Date") + "\nx-oss-date:" + r.Header.Get("X-Oss-Date") +
					"\nx-oss-user-agent:" + ossUA + "\n/bkt/o/k?partNumber=" + pn + "&uploadId=U"
				if meta != want {
					t.Errorf("part auth meta %q", meta)
				}
				parts := map[string]string{"1": "0123", "2": "4567", "3": "89"}
				if string(body) != parts[pn] {
					t.Errorf("part %s body %q", pn, body)
				}
				w.Header().Set("ETag", `"E`+pn+`"`)
				return
			}
			// commit
			if ct := r.Header.Values("Content-Type"); len(ct) != 1 || ct[0] != "application/xml" {
				t.Errorf("commit content-type %q", ct)
			}
			sum := md5.Sum(body)
			md5b64 := base64.StdEncoding.EncodeToString(sum[:])
			if r.Header.Get("Content-MD5") != md5b64 || r.URL.RawQuery != "uploadId=U" {
				t.Errorf("commit md5/query")
			}
			cb, _ := base64.StdEncoding.DecodeString(r.Header.Get("X-Oss-Callback"))
			if string(cb) != `{"callbackUrl":"cu","callbackBody":"a=${x}&b=1"}` {
				t.Errorf("callback %s", cb)
			}
			wantXML := "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUpload>\n" +
				"<Part>\n<PartNumber>1</PartNumber>\n<ETag>\"E1\"</ETag>\n</Part>\n" +
				"<Part>\n<PartNumber>2</PartNumber>\n<ETag>\"E2\"</ETag>\n</Part>\n" +
				"<Part>\n<PartNumber>3</PartNumber>\n<ETag>\"E3\"</ETag>\n</Part>\n" +
				"</CompleteMultipartUpload>"
			if string(body) != wantXML {
				t.Errorf("commit xml %q", body)
			}
			d := r.Header.Get("X-Oss-Date")
			want := "POST\n" + md5b64 + "\napplication/xml\n" + d + "\nx-oss-callback:" + r.Header.Get("X-Oss-Callback") +
				"\nx-oss-date:" + d + "\nx-oss-user-agent:" + ossUA + "\n/bkt/o/k?uploadId=U"
			if meta != want {
				t.Errorf("commit auth meta %q", meta)
			}
		default:
			t.Errorf("unexpected host %s", host)
		}
	})
	return hc
}

func authMeta(t *testing.T, auth string) string {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(auth, "KEY:"))
	if err != nil {
		t.Errorf("auth %q", auth)
	}
	return string(b)
}

func TestWebUploadMultipart(t *testing.T) {
	var calls []string
	c := newTestWeb(uploadServer(t, false, &calls), "c=1", nil)
	fid, err := c.Upload("dir", "a.nfo", []byte("0123456789"))
	if err != nil {
		t.Fatal(err)
	}
	if fid != "F" {
		t.Errorf("fid %s", fid)
	}
	want := []string{
		"POST drive.test/1/clouddrive/file/upload/pre",
		"POST drive.test/1/clouddrive/file/update/hash",
		"POST drive.test/1/clouddrive/file/upload/auth",
		"PUT bkt.oss.example.com/o/k",
		"POST drive.test/1/clouddrive/file/upload/auth",
		"PUT bkt.oss.example.com/o/k",
		"POST drive.test/1/clouddrive/file/upload/auth",
		"PUT bkt.oss.example.com/o/k",
		"POST drive.test/1/clouddrive/file/upload/auth",
		"POST bkt.oss.example.com/o/k",
		"POST drive.test/1/clouddrive/file/upload/finish",
	}
	if strings.Join(calls, "\n") != strings.Join(want, "\n") {
		t.Errorf("calls:\n%s", strings.Join(calls, "\n"))
	}
}

func TestWebUploadInstant(t *testing.T) {
	var calls []string
	c := newTestWeb(uploadServer(t, true, &calls), "c=1", nil)
	fid, err := c.Upload("dir", "a.nfo", []byte("0123456789"))
	if err != nil {
		t.Fatal(err)
	}
	if fid != "F2" || len(calls) != 2 {
		t.Errorf("fid %s calls %v", fid, calls)
	}
}

func TestWebDownloadAndMkdir(t *testing.T) {
	_, hc := fakeServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch r.Header.Get("X-Orig-Host") + r.URL.Path {
		case "drive.test/1/clouddrive/file/download":
			if string(body) != `{"fids":["f1"]}` {
				t.Errorf("download body %s", body)
			}
			w.Header().Add("Set-Cookie", "__puus=new; Path=/")
			writeJSON(w, `{"status":200,"code":0,"data":[{"download_url":"https://dl.test/x","size":42}]}`)
		case "dl.test/x":
			if r.Header.Get("Cookie") != "a=1; __puus=new" || r.Header.Get("User-Agent") != WebUserAgent {
				t.Errorf("dl headers %v", r.Header)
			}
			_, _ = io.WriteString(w, "content")
		case "drive.test/1/clouddrive/file":
			if string(body) != `{"dir_init_lock":false,"dir_path":"","file_name":"a&b","pdir_fid":"0"}` {
				t.Errorf("mkdir body %s", body)
			}
			writeJSON(w, `{"status":200,"code":0,"data":{"fid":"NEW"}}`)
		}
	})
	c := newTestWeb(hc, "a=1", nil)
	b, err := c.Download("f1")
	if err != nil || string(b) != "content" {
		t.Fatalf("%s %v", b, err)
	}
	fid, err := c.Mkdir(Root, "a&b")
	if err != nil || fid != "NEW" {
		t.Fatalf("%s %v", fid, err)
	}
}

func TestQueryEscape(t *testing.T) {
	got := withQuery("https://x/y", []param{{"a", "S:custom|OPT@0"}, {"b", "x y+z/中"}, {"c", ""}})
	want := "https://x/y?a=S%3Acustom%7COPT%400&b=x%20y%2Bz%2F%E4%B8%AD&c="
	if got != want {
		t.Errorf("%s", got)
	}
}
