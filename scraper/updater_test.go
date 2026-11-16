package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
)

type updaterEnv struct {
	t         *testing.T
	home      string
	driveRoot string
	mu        sync.Mutex
	files     map[string][]byte
	server    *httptest.Server
	restarted int
}

func newUpdaterEnv(t *testing.T) *updaterEnv {
	e := &updaterEnv{t: t, home: t.TempDir(), driveRoot: t.TempDir(), files: map[string][]byte{}}
	e.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		e.mu.Lock()
		body, ok := e.files[strings.TrimPrefix(r.URL.Path, "/")]
		e.mu.Unlock()
		if !ok {
			w.WriteHeader(404)
			return
		}
		_, _ = w.Write(body)
	}))
	t.Cleanup(e.server.Close)
	return e
}

func (e *updaterEnv) asset(name string, data []byte) *ReleaseAsset {
	e.mu.Lock()
	e.files[name] = data
	e.mu.Unlock()
	sum := sha256.Sum256(data)
	return &ReleaseAsset{name, int64(len(data)), hex.EncodeToString(sum[:])}
}

// scraperZip 假的刮削器安装包: bin/arisa-scraper 打印 printed.
func scraperZip(version, printed string) []byte {
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	w, _ := z.Create("arisa-scraper-" + version + "/bin/arisa-scraper")
	_, _ = w.Write([]byte("#!/bin/sh\necho " + printed + "\n"))
	w, _ = z.Create("arisa-scraper-" + version + "/README.txt")
	_, _ = w.Write([]byte{1, 2, 3})
	_ = z.Close()
	return buf.Bytes()
}

func (e *updaterEnv) publish(version string, zipData []byte) {
	if zipData == nil {
		zipData = scraperZip(version, version)
	}
	m := ReleaseManifest{
		Version: version, VersionCode: VersionCode(version),
		Scraper: e.asset("arisa-scraper-"+version+".zip", zipData),
		App:     e.asset("arisa-"+version+".apk", []byte("apk-"+version)),
	}
	data, _ := json.Marshal(m)
	e.mu.Lock()
	e.files["latest.json"] = data
	e.mu.Unlock()
}

func (e *updaterEnv) updater(current, installDir string) *Updater {
	u := NewUpdater(e.home, &LocalDrive{e.driveRoot}, func() *http.Client { return http.DefaultClient }, func(string) {}, func() bool { return true }, func() { e.restarted++ })
	u.current, u.installDir = current, installDir
	u.manifestURL = e.server.URL + "/latest.json"
	u.assetURL = func(_, name string) string { return e.server.URL + "/" + name }
	return u
}

func (e *updaterEnv) installed(version string) string {
	p := filepath.Join(e.home, "versions", version)
	_ = os.MkdirAll(filepath.Join(p, "bin"), 0o755)
	return p
}

func (e *updaterEnv) appDir() string { return filepath.Join(e.driveRoot, ".arisa", "app") }

func dirNames(p string) []string {
	entries, _ := os.ReadDir(p)
	var out []string
	for _, x := range entries {
		out = append(out, x.Name())
	}
	sort.Strings(out)
	return out
}

func TestMirrorsAppToDrive(t *testing.T) {
	e := newUpdaterEnv(t)
	e.publish("0.2.0", nil)
	if err := e.updater("0.1.0", "").Check(true, true); err != nil {
		t.Fatal(err)
	}
	apk, _ := os.ReadFile(filepath.Join(e.appDir(), "arisa-0.2.0.apk"))
	eq(t, "apk-0.2.0", string(apk))
	data, _ := os.ReadFile(filepath.Join(e.appDir(), "latest.json"))
	var onDrive ReleaseManifest
	_ = json.Unmarshal(data, &onDrive)
	eq(t, "0.2.0", onDrive.Version)
	if onDrive.Scraper != nil {
		t.Fatal("给 TV 的清单不带刮削器")
	}

	// 新版出来后换掉清单, 旧 APK 留一个
	e.publish("0.3.0", nil)
	if err := e.updater("0.1.0", "").Check(false, true); err != nil {
		t.Fatal(err)
	}
	e.publish("0.4.0", nil)
	if err := e.updater("0.1.0", "").Check(false, true); err != nil {
		t.Fatal(err)
	}
	eqStr(t, []string{"arisa-0.3.0.apk", "arisa-0.4.0.apk", "latest.json"}, dirNames(e.appDir()))
}

func TestBadChecksumRejected(t *testing.T) {
	e := newUpdaterEnv(t)
	e.publish("0.2.0", nil)
	e.files["arisa-0.2.0.apk"] = []byte("tampered")
	if err := e.updater("0.1.0", "").Check(false, true); err == nil {
		t.Fatal("校验不对应该报错")
	}
	if _, err := os.Stat(filepath.Join(e.appDir(), "latest.json")); err == nil {
		t.Fatal("不该写清单")
	}
}

func TestSelfUpdateSwitchesAndRestarts(t *testing.T) {
	e := newUpdaterEnv(t)
	e.publish("0.2.0", nil)
	old := e.installed("0.1.0")
	if err := e.updater("0.1.0", old).Check(true, false); err != nil {
		t.Fatal(err)
	}
	eq(t, 1, e.restarted)
	link, _ := os.Readlink(filepath.Join(e.home, "current"))
	eq(t, "versions/0.2.0", link)
	st, err := os.Stat(filepath.Join(e.home, "current", "bin", "arisa-scraper"))
	if err != nil || st.Mode()&0o111 == 0 {
		t.Fatal("新版不能执行")
	}
	if _, err := os.Stat(old); err != nil {
		t.Fatal("旧版要留着")
	}

	// 再升一次, 0.1.0 被清掉, 留当前运行的 0.2.0 和新的 0.3.0
	e.publish("0.3.0", nil)
	if err := e.updater("0.2.0", filepath.Join(e.home, "versions", "0.2.0")).Check(true, false); err != nil {
		t.Fatal(err)
	}
	eqStr(t, []string{"0.2.0", "0.3.0"}, dirNames(filepath.Join(e.home, "versions")))
}

func TestBrokenReleaseNotInstalled(t *testing.T) {
	e := newUpdaterEnv(t)
	e.publish("0.2.0", scraperZip("0.2.0", "boom"))
	if err := e.updater("0.1.0", e.installed("0.1.0")).Check(true, false); err == nil {
		t.Fatal("试运行失败应该报错")
	}
	eq(t, 0, e.restarted)
	if _, err := os.Lstat(filepath.Join(e.home, "current")); err == nil {
		t.Fatal("不该切换")
	}
	eqStr(t, []string{"0.1.0"}, dirNames(filepath.Join(e.home, "versions")))
}

func TestDevBuildAndSameVersionSkipped(t *testing.T) {
	e := newUpdaterEnv(t)
	e.publish("0.2.0", nil)
	_ = e.updater("0.0.0-dev", filepath.Join(t.TempDir(), "repo")).Check(true, false)
	_ = e.updater("0.2.0", e.installed("0.2.0")).Check(true, false)
	eq(t, 0, e.restarted)
}

func TestVersionCode(t *testing.T) {
	eq(t, 1002003, VersionCode("1.2.3"))
	eq(t, 0, VersionCode("0.0.0-dev"))
	eq(t, 0, VersionCode("1.2"))
	eq(t, 0, VersionCode("1.1000.0"))
}
