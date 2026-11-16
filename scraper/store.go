package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/mmooyyii/arisa/scraper/quark"
)

// ~/.arisa 下的几个 JSON 文件. 升级后、回退后的刮削器都会读, 字段名和旧版 (Kotlin) 一致, 不能改.

func prettyJSON(v any) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "    ")
	_ = enc.Encode(v)
	return bytes.TrimRight(buf.Bytes(), "\n")
}

var processLocks sync.Map

// withFileLock 跨进程的互斥 (同一进程内也互斥). 常驻服务、它启动的 AI 子进程、网页上的操作可能同时改同一个文件.
func withFileLock(lockPath string, f func() error) error {
	mu, _ := processLocks.LoadOrStore(lockPath, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()
	if err := os.MkdirAll(dirOf(lockPath), 0o755); err != nil {
		return err
	}
	fd, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return err
	}
	defer fd.Close()
	if err := syscall.Flock(int(fd.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(fd.Fd()), syscall.LOCK_UN)
	return f()
}

// ---------- 登录态 state.json ----------

type State struct {
	Cookie string           `json:"cookie,omitempty"`
	Tv     *quark.TvSession `json:"tv,omitempty"`
}

type StateStore struct {
	file string
	mu   sync.Mutex
}

func (s *StateStore) Load() (State, error) {
	var st State
	data, err := os.ReadFile(s.file)
	if os.IsNotExist(err) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	return st, json.Unmarshal(data, &st)
}

func (s *StateStore) Update(f func(*State)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	st, err := s.Load()
	if err != nil {
		return err
	}
	f(&st)
	// 只有自己能读
	return writeFileAtomic(s.file, prettyJSON(st), 0o600)
}

// ---------- 映射表 mapping.json ----------

// MappingEntry 是识别结论: 网盘里某个文件或目录 → TMDB 作品.
// key 是 fid, 改名、移动后仍然有效. 一个文件归属于离它最近的、有映射的祖先 (含自己),
// 所以给剧集目录做了映射, 以后放进去的新集数不用再识别.
type MappingEntry struct {
	// Target: "movie:693134" / "tv:1396" / "skip" (不是正片, 不入库).
	Target string `json:"target"`
	// Path 做决定时的路径, 只给人看.
	Path string `json:"path"`
	// Season 强制季号, 目录名看不出第几季时用. 0 表示特别篇.
	Season *int `json:"season,omitempty"`
	// EpisodeOffset 集数加上这个数. TMDB 把多季合成一季, 而文件按季内编号时用.
	EpisodeOffset *int `json:"episodeOffset,omitempty"`
	// Bangumi 指定 Bangumi 条目 (动画的中文名用它的), 不指定时按原名自动匹配.
	Bangumi *int   `json:"bangumi,omitempty"`
	Note    string `json:"note,omitempty"`
	// By 谁做的决定: skill / user / nfo / rules
	By string `json:"by"`
	At int64  `json:"at"`
}

// 旧版不写默认值 (by 缺省是 skill).
func (e *MappingEntry) UnmarshalJSON(data []byte) error {
	type plain MappingEntry
	p := plain{By: "skill"}
	if err := json.Unmarshal(data, &p); err != nil {
		return err
	}
	*e = MappingEntry(p)
	if e.At == 0 {
		e.At = time.Now().UnixMilli()
	}
	return nil
}

const Skip = "skip"

var targetRe = regexp.MustCompile(`^((movie|tv):[0-9]+|skip)$`)

func ValidTarget(t string) bool { return targetRe.MatchString(t) }

func (e MappingEntry) IsSkip() bool { return e.Target == Skip }

// Tmdb 返回 (类型, TMDB ID), skip 时 ok=false.
func (e MappingEntry) Tmdb() (kind string, id int, ok bool) {
	if e.IsSkip() {
		return "", 0, false
	}
	k, v, _ := strings.Cut(e.Target, ":")
	n, err := strconv.Atoi(v)
	return k, n, err == nil
}

type Mapping struct {
	Entries map[string]MappingEntry `json:"entries"`
}

// Resolve 文件对应的映射: 先看文件自己, 再从近到远看所在目录. 返回映射所在的 fid.
func (m Mapping) Resolve(f ScannedFile) (string, *MappingEntry) {
	if e, ok := m.Entries[f.File.Fid]; ok {
		return f.File.Fid, &e
	}
	for i := len(f.Dirs) - 1; i >= 0; i-- {
		if e, ok := m.Entries[f.Dirs[i].Fid]; ok {
			return f.Dirs[i].Fid, &e
		}
	}
	return "", nil
}

func (m Mapping) clone() Mapping {
	out := Mapping{Entries: make(map[string]MappingEntry, len(m.Entries))}
	for k, v := range m.Entries {
		out.Entries[k] = v
	}
	return out
}

func (m Mapping) equal(o Mapping) bool { return bytes.Equal(prettyJSON(m), prettyJSON(o)) }

const MappingName = "mapping.json"

// MappingStore: 映射表存在本地 ~/.arisa/mapping.json, 同时备份到网盘 /.arisa/mapping.json,
// 换机器或本地丢了能从网盘恢复.
type MappingStore struct {
	file  string
	drive Drive
}

func (s *MappingStore) Load() (Mapping, error) {
	m := Mapping{Entries: map[string]MappingEntry{}}
	data, err := os.ReadFile(s.file)
	if os.IsNotExist(err) {
		if s.drive == nil {
			return m, nil
		}
		remote, err := s.remote()
		if err != nil || remote == nil {
			return m, err
		}
		if err := writeFileAtomic(s.file, remote, 0o644); err != nil {
			return m, err
		}
		data = remote
	} else if err != nil {
		return m, err
	}
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("映射表 %s 格式不对: %w", s.file, err)
	}
	if m.Entries == nil {
		m.Entries = map[string]MappingEntry{}
	}
	return m, nil
}

// Update 读-改-写, 加文件锁.
func (s *MappingStore) Update(change func(*Mapping) error) (Mapping, error) {
	var out Mapping
	err := withFileLock(s.file+".lock", func() error {
		m, err := s.Load()
		if err != nil {
			return err
		}
		if err := change(&m); err != nil {
			return err
		}
		out = m
		return s.Save(m)
	})
	return out, err
}

func (s *MappingStore) Save(m Mapping) error {
	return writeFileAtomic(s.file, prettyJSON(m), 0o644)
}

func (s *MappingStore) remote() ([]byte, error) {
	dir, err := findDir(s.drive, s.drive.RootFid(), ArisaDir)
	if err != nil || dir == "" {
		return nil, err
	}
	list, err := s.drive.List(dir)
	if err != nil {
		return nil, err
	}
	for _, f := range list {
		if f.Name == MappingName {
			return s.drive.Download(f.Fid)
		}
	}
	return nil, nil
}

// Backup 本地和网盘内容不同时上传 (先传新的再删旧的).
func (s *MappingStore) Backup() error {
	if s.drive == nil {
		return nil
	}
	data, err := os.ReadFile(s.file)
	if err != nil {
		return nil
	}
	dir, err := findOrMkdir(s.drive, s.drive.RootFid(), ArisaDir)
	if err != nil {
		return err
	}
	list, err := s.drive.List(dir)
	if err != nil {
		return err
	}
	var old []string
	for _, f := range list {
		if f.Name == MappingName {
			old = append(old, f.Fid)
		}
	}
	if len(old) == 1 {
		if remote, err := s.drive.Download(old[0]); err == nil && bytes.Equal(remote, data) {
			return nil
		}
	}
	// 夸克不覆盖同名文件: 先用临时名上传, 删掉旧的, 再改回正式名字
	fid, err := s.drive.Upload(dir, fmt.Sprintf("mapping-%d.json", time.Now().UnixMilli()), data)
	if err != nil {
		return err
	}
	if err := s.drive.Delete(old); err != nil {
		return err
	}
	return s.drive.Rename(fid, MappingName)
}

// ---------- 待确认 review.json ----------

// ReviewEntry: AI 拿不准、或者没处理的作品, 等用户在网页上确认.
// 在这里的作品不会再交给 AI, 用户确认 (decide/skip) 或点「重新识别」后移出.
type ReviewEntry struct {
	Work PendingWork `json:"work"`
	// Reason 为什么要人来确认.
	Reason string `json:"reason"`
	// Guess AI 的猜测 (movie:ID / tv:ID).
	Guess string `json:"guess,omitempty"`
	// By: ai (AI 主动提问) / unhandled (AI 跑完了也没处理) / error (AI 运行出错)
	By string `json:"by"`
	At int64  `json:"at"`
}

func (e *ReviewEntry) UnmarshalJSON(data []byte) error {
	type plain ReviewEntry
	p := plain{By: "ai"}
	if err := json.Unmarshal(data, &p); err != nil {
		return err
	}
	*e = ReviewEntry(p)
	return nil
}

type reviewFile struct {
	Entries []ReviewEntry `json:"entries"`
}

type ReviewStore struct{ file string }

func (s *ReviewStore) lock(f func() error) error { return withFileLock(s.file+".lock", f) }

func (s *ReviewStore) read() ([]ReviewEntry, error) {
	data, err := os.ReadFile(s.file)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var rf reviewFile
	return rf.Entries, json.Unmarshal(data, &rf)
}

func (s *ReviewStore) write(entries []ReviewEntry) error {
	if entries == nil {
		entries = []ReviewEntry{}
	}
	return writeFileAtomic(s.file, prettyJSON(reviewFile{entries}), 0o644)
}

func (s *ReviewStore) Load() ([]ReviewEntry, error) {
	var out []ReviewEntry
	err := s.lock(func() (err error) { out, err = s.read(); return })
	return out, err
}

func (s *ReviewStore) Keys() map[string]bool {
	all, _ := s.Load()
	keys := map[string]bool{}
	for _, e := range all {
		keys[e.Work.Key] = true
	}
	return keys
}

// Add 同一作品只留最新的一条.
func (s *ReviewStore) Add(e ReviewEntry) error {
	if e.By == "" {
		e.By = "ai"
	}
	if e.At == 0 {
		e.At = time.Now().UnixMilli()
	}
	return s.lock(func() error {
		all, err := s.read()
		if err != nil {
			return err
		}
		var left []ReviewEntry
		for _, x := range all {
			if x.Work.Key != e.Work.Key {
				left = append(left, x)
			}
		}
		return s.write(append(left, e))
	})
}

func (s *ReviewStore) Remove(key string) bool {
	removed := false
	_ = s.lock(func() error {
		all, err := s.read()
		if err != nil {
			return err
		}
		var left []ReviewEntry
		for _, x := range all {
			if x.Work.Key != key {
				left = append(left, x)
			}
		}
		removed = len(left) != len(all)
		return s.write(left)
	})
	return removed
}

// Retain 已经不在待识别里的 (被映射了、被删了) 移出.
func (s *ReviewStore) Retain(pending map[string]bool) {
	_ = s.lock(func() error {
		all, err := s.read()
		if err != nil {
			return err
		}
		var left []ReviewEntry
		for _, x := range all {
			if pending[x.Work.Key] {
				left = append(left, x)
			}
		}
		if len(left) == len(all) {
			return nil
		}
		return s.write(left)
	})
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

var errNotFound = errors.New("not found")
