package main

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// version 编译时用 -ldflags "-X main.version=1.2.3" 写进去, 开发版是 0.0.0-dev.
var version = "0.0.0-dev"

func CurrentVersion() string { return version }

// Updater 是自动更新. 定时读 GitHub Release 的 latest.json:
//   - 有新版 TV App 时把 APK 转存到网盘 /.arisa/app/, TV 在设置里手动更新 (电视上连 GitHub 不稳);
//   - 有新版刮削器时下载解压到 ~/.arisa/versions/<版本>, 把 ~/.arisa/current 指过去,
//     等手上的任务做完退出, launchd 用新版重启. 只对 install.sh 装的 (从 versions 目录运行的) 生效, 开发版不动.
//
// 安装包的结构 (旧版刮削器也按这个装新版, 不能改): zip 里一层 arisa-scraper-<版本>/ 目录, 下面 bin/arisa-scraper 可执行,
// 运行 `bin/arisa-scraper version` 最后一行打印版本号.
type Updater struct {
	home        string
	drive       Drive
	http        func() *http.Client
	log         func(string)
	idle        func() bool
	restart     func()
	current     string
	installDir  string
	manifestURL string
	assetURL    func(version, name string) string

	mu         sync.Mutex
	latest     *ReleaseManifest
	appOnDrive string
	lastCheck  int64
	lastError  string
}

func NewUpdater(home string, drive Drive, client func() *http.Client, log func(string), idle func() bool, restart func()) *Updater {
	return &Updater{
		home: home, drive: drive, http: client, log: log, idle: idle, restart: restart,
		current: CurrentVersion(), installDir: installDir(),
		manifestURL: GithubLatest, assetURL: ReleaseAssetURL,
	}
}

func (u *Updater) versions() string { return filepath.Join(u.home, "versions") }

func canonical(p string) string {
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return r
	}
	return filepath.Clean(p)
}

// Managed 是 install.sh 装的 (从 ~/.arisa/versions 运行), 会自动更新; 否则是开发版.
func (u *Updater) Managed() bool {
	return u.installDir != "" && canonical(filepath.Dir(u.installDir)) == canonical(u.versions())
}

func (u *Updater) Latest() *ReleaseManifest {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.latest
}

type UpdateInfo struct {
	Latest     *ReleaseManifest
	AppOnDrive string
	LastCheck  int64
	LastError  string
}

func (u *Updater) Info() UpdateInfo {
	u.mu.Lock()
	defer u.mu.Unlock()
	return UpdateInfo{u.latest, u.appOnDrive, u.lastCheck, u.lastError}
}

// Check 检查一次. selfUpdate / mirrorApp 对应配置里的开关.
func (u *Updater) Check(selfUpdate, mirrorApp bool) error {
	u.mu.Lock()
	u.lastCheck = time.Now().UnixMilli()
	u.mu.Unlock()
	err := u.check(selfUpdate, mirrorApp)
	u.mu.Lock()
	u.lastError = ""
	if err != nil {
		u.lastError = err.Error()
	}
	u.mu.Unlock()
	return err
}

func (u *Updater) check(selfUpdate, mirrorApp bool) error {
	text, err := u.get(u.manifestURL)
	if errors.Is(err, errNotFound) {
		return errors.New("GitHub 上还没有发布过版本")
	}
	if err != nil {
		return err
	}
	var m ReleaseManifest
	if err := json.Unmarshal(text, &m); err != nil {
		return fmt.Errorf("latest.json 格式不对: %w", err)
	}
	u.mu.Lock()
	u.latest = &m
	u.mu.Unlock()
	if mirrorApp && m.App != nil {
		if err := u.mirrorApp(m, *m.App); err != nil {
			return err
		}
	}
	if selfUpdate && m.Scraper != nil {
		return u.selfUpdate(m, *m.Scraper)
	}
	return nil
}

// ---------- TV App 转存到网盘 ----------

func (u *Updater) mirrorApp(m ReleaseManifest, apk ReleaseAsset) error {
	root, err := findOrMkdir(u.drive, u.drive.RootFid(), ArisaDir)
	if err != nil {
		return err
	}
	dir, err := findOrMkdir(u.drive, root, ReleaseAppDir)
	if err != nil {
		return err
	}
	files, err := u.drive.List(dir)
	if err != nil {
		return err
	}
	var existing *ReleaseManifest
	var manifests []string
	hasApk := false
	for _, f := range files {
		if f.Name == ReleaseFile {
			manifests = append(manifests, f.Fid)
			if data, err := u.drive.Download(f.Fid); err == nil {
				var e ReleaseManifest
				if json.Unmarshal(data, &e) == nil {
					existing = &e
				}
			}
		}
		if f.Name == apk.Name {
			hasApk = true
		}
	}
	if existing != nil {
		u.mu.Lock()
		u.appOnDrive = existing.Version
		u.mu.Unlock()
	}
	if existing != nil && existing.VersionCode >= m.VersionCode && hasApk {
		return nil
	}

	u.log("转存 TV App " + m.Version + " 到网盘")
	data, err := u.download(m.Version, apk)
	if err != nil {
		return err
	}
	if !hasApk {
		if _, err := u.drive.Upload(dir, apk.Name, data); err != nil {
			return err
		}
	}
	// 先删旧清单再传新的, 同名上传夸克会自动改名
	if err := u.drive.Delete(manifests); err != nil {
		return err
	}
	forTv := m
	forTv.Scraper = nil
	body, _ := json.MarshalIndent(forTv, "", "    ")
	if _, err := u.drive.Upload(dir, ReleaseFile, body); err != nil {
		return err
	}
	// 旧 APK 只留上一个版本. 按文件名里的版本号排, 版本号认不出的按上传时间;
	// 只按上传时间的话, 同一秒传的几个 (测试里就是) 谁新谁旧说不准
	type oldApk struct {
		fid  string
		code int
		at   int64
	}
	var oldApks []oldApk
	for _, f := range files {
		if strings.HasSuffix(f.Name, ".apk") && f.Name != apk.Name {
			code := VersionCode(strings.TrimSuffix(strings.TrimPrefix(f.Name, "arisa-"), ".apk"))
			oldApks = append(oldApks, oldApk{f.Fid, code, f.UpdatedAt})
		}
	}
	sort.SliceStable(oldApks, func(i, j int) bool {
		if oldApks[i].code != oldApks[j].code {
			return oldApks[i].code > oldApks[j].code
		}
		return oldApks[i].at > oldApks[j].at
	})
	var del []string
	for i, a := range oldApks {
		if i >= 1 {
			del = append(del, a.fid)
		}
	}
	if err := u.drive.Delete(del); err != nil {
		return err
	}
	u.mu.Lock()
	u.appOnDrive = m.Version
	u.mu.Unlock()
	u.log("TV App " + m.Version + " 已转存到 /" + ArisaDir + "/" + ReleaseAppDir + "/" + apk.Name)
	return nil
}

// ---------- 刮削器自己 ----------

func (u *Updater) selfUpdate(m ReleaseManifest, asset ReleaseAsset) error {
	if !u.Managed() || m.VersionCode <= VersionCode(u.current) {
		return nil
	}
	target := filepath.Join(u.versions(), m.Version)
	if st, err := os.Stat(target); err != nil || !st.IsDir() {
		u.log("下载刮削器 " + m.Version)
		data, err := u.download(m.Version, asset)
		if err != nil {
			return err
		}
		tmp := filepath.Join(u.versions(), m.Version+".tmp")
		_ = os.RemoveAll(tmp)
		if err := unzipStrip(data, tmp); err != nil {
			_ = os.RemoveAll(tmp)
			return err
		}
		if bins, err := os.ReadDir(filepath.Join(tmp, "bin")); err == nil {
			for _, b := range bins {
				_ = os.Chmod(filepath.Join(tmp, "bin", b.Name()), 0o755)
			}
		}
		// 先在新版里跑一下, 起不来就不切换
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		out, err := exec.CommandContext(ctx, filepath.Join(tmp, "bin/arisa-scraper"), "version").CombinedOutput()
		cancel()
		text := strings.TrimSpace(string(out))
		lines := strings.Split(text, "\n")
		if err != nil || lines[len(lines)-1] != m.Version {
			_ = os.RemoveAll(tmp)
			return fmt.Errorf("新版 %s 试运行失败: %s", m.Version, truncate(text, 300))
		}
		if err := os.Rename(tmp, target); err != nil {
			return fmt.Errorf("改名失败 %s: %w", tmp, err)
		}
	}
	if err := u.switchTo(target); err != nil {
		return err
	}
	// 版本目录只留当前和上一个, 出问题时可以手动切回去
	if entries, err := os.ReadDir(u.versions()); err == nil {
		running := canonical(u.installDir)
		for _, e := range entries {
			p := filepath.Join(u.versions(), e.Name())
			if e.IsDir() && p != target && canonical(p) != running {
				_ = os.RemoveAll(p)
			}
		}
	}
	u.log("刮削器已更新到 " + m.Version + ", 等当前任务做完后重启")
	for !u.idle() {
		time.Sleep(5 * time.Second)
	}
	u.restart()
	return nil
}

// switchTo 把 current 链接原子地换成指向 target. 用相对链接 versions/<版本>, 整个 ~/.arisa 挪地方也不坏.
func (u *Updater) switchTo(target string) error {
	tmp := filepath.Join(u.home, "current.new")
	_ = os.Remove(tmp)
	if err := os.Symlink(filepath.Join("versions", filepath.Base(target)), tmp); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(u.home, "current"))
}

// ---------- 下载 ----------

func (u *Updater) get(url string) ([]byte, error) {
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("User-Agent", "arisa-scraper/"+u.current)
	resp, err := u.http().Do(req)
	if err != nil {
		return nil, fmt.Errorf("下载失败 %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		return nil, fmt.Errorf("下载失败 HTTP 404: %s: %w", url, errNotFound)
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("下载失败 HTTP %d: %s", resp.StatusCode, url)
	}
	return io.ReadAll(resp.Body)
}

func (u *Updater) download(version string, asset ReleaseAsset) ([]byte, error) {
	data, err := u.get(u.assetURL(version, asset.Name))
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(data)
	sha := hex.EncodeToString(sum[:])
	if int64(len(data)) != asset.Size || sha != asset.Sha256 {
		return nil, fmt.Errorf("%s 校验失败 (大小 %d, sha256 %s)", asset.Name, len(data), sha)
	}
	return data, nil
}

// installDir 安装目录 (里面有 bin/), 从自己的可执行文件位置推出来; 不是 bin/ 下运行的 (go run、测试) 返回 "".
func installDir() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	exe = canonical(exe)
	if filepath.Base(filepath.Dir(exe)) != "bin" {
		return ""
	}
	return filepath.Dir(filepath.Dir(exe))
}

// unzipStrip 解压安装包, 去掉最外层的 arisa-scraper-x.y.z/ 目录.
func unzipStrip(data []byte, to string) error {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return err
	}
	root, err := filepath.Abs(to)
	if err != nil {
		return err
	}
	for _, f := range zr.File {
		_, rel, ok := strings.Cut(f.Name, "/")
		if !ok || rel == "" {
			continue
		}
		out := filepath.Join(root, rel)
		if !strings.HasPrefix(out, root+string(filepath.Separator)) {
			return fmt.Errorf("压缩包里有越界路径: %s", f.Name)
		}
		if f.FileInfo().IsDir() || strings.HasSuffix(f.Name, "/") {
			if err := os.MkdirAll(out, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		mode := os.FileMode(0o644)
		if f.Mode()&0o111 != 0 {
			mode = 0o755
		}
		w, err := os.OpenFile(out, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
		if err == nil {
			_, err = io.Copy(w, rc)
			w.Close()
		}
		rc.Close()
		if err != nil {
			return err
		}
	}
	return nil
}
