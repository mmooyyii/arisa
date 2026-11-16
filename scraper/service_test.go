package main

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/mmooyyii/arisa/scraper/quark"
)

// testDrive 本地目录当网盘, 记录 list 次数, 可以塞「最近」动态.
type testDrive struct {
	*LocalDrive
	lists   []string
	records []quark.RecentRecord
	trash   []quark.RecycledFile
}

func (d *testDrive) List(fid string) ([]quark.File, error) {
	d.lists = append(d.lists, fid)
	return d.LocalDrive.List(fid)
}
func (d *testDrive) Recent() ([]quark.RecentRecord, error)   { return d.records, nil }
func (d *testDrive) Recycled() ([]quark.RecycledFile, error) { return d.trash, nil }

type svcEnv struct {
	root, home string
	drive      *testDrive
	service    *Service
}

func newSvcEnv(t *testing.T, agent func(e *svcEnv, batch []PendingWork) AgentResult) *svcEnv {
	e := &svcEnv{root: t.TempDir(), home: t.TempDir()}
	_ = os.WriteFile(filepath.Join(e.home, "config.yaml"), []byte("libraries:\n  - path: /没看\n    type: auto\n"), 0o644)
	e.drive = &testDrive{LocalDrive: &LocalDrive{e.root}}
	e.service = NewService(e.home, filepath.Join(e.home, "config.yaml"), &StateStore{file: filepath.Join(e.home, "state.json")}, ServiceOptions{
		DriveFactory:   func(Config) (Drive, error) { return e.drive, nil },
		TmdbFactory:    func(Config) TmdbAPI { return newFakeTmdb() },
		ImagesFactory:  func(Config) *Images { return nil },
		BangumiFactory: func(Config) BangumiAPI { return nil },
		AgentFactory:   func(Config) AgentFunc { return func(b []PendingWork) AgentResult { return agent(e, b) } },
	})
	return e
}

func (e *svcEnv) runAll() {
	for e.service.RunNext() {
	}
}

func okResult(summary string) AgentResult {
	c := 0.01
	return AgentResult{Ok: true, Summary: summary, CostCny: &c}
}

func (e *svcEnv) latestIndex(t *testing.T) *Library {
	t.Helper()
	entries, _ := os.ReadDir(filepath.Join(e.root, ".arisa"))
	var names []string
	for _, x := range entries {
		if IsIndexFile(x.Name()) {
			names = append(names, x.Name())
		}
	}
	data, err := os.ReadFile(filepath.Join(e.root, ".arisa", LatestIndex(names)))
	if err != nil {
		t.Fatal(err)
	}
	lib, err := DecodeLibrary(data)
	if err != nil {
		t.Fatal(err)
	}
	return lib
}

func TestCachingDrive(t *testing.T) {
	root := t.TempDir()
	touch(t, root, "a/1.mkv")
	inner := &testDrive{LocalDrive: &LocalDrive{root}}
	cacheFile := filepath.Join(t.TempDir(), "cache.json")
	cache := NewCachingDrive(inner, cacheFile)
	_, _ = cache.List("a")
	_, _ = cache.List("a")
	eqStr(t, []string{"a"}, inner.lists)

	// 写操作让父目录失效
	_, _ = cache.Upload("a", "2.mkv", []byte{1})
	l, _ := cache.List("a")
	eqStr(t, []string{"a/1.mkv", "a/2.mkv"}, []string{l[0].Fid, l[1].Fid})
	eq(t, 2, len(inner.lists))

	// 缓存落盘, 重启后不用再列
	if err := cache.Save(); err != nil {
		t.Fatal(err)
	}
	reopened := NewCachingDrive(inner, cacheFile)
	_, _ = reopened.List("a")
	eq(t, 2, len(inner.lists))
	reopened.InvalidateAll()
	_, _ = reopened.List("a")
	eq(t, 3, len(inner.lists))
}

// 旧版 (Kotlin) 写的扫描缓存能接着用.
func TestOldScanCacheReadable(t *testing.T) {
	root := t.TempDir()
	inner := &testDrive{LocalDrive: &LocalDrive{root}}
	file := filepath.Join(t.TempDir(), "scan-cache.json")
	_ = os.WriteFile(file, []byte(`{"listings":{"d":[{"fid":"f1","name":"a.mkv","isDir":false,"size":3,"category":1,"updatedAt":7,"parentFid":"d","duration":60}]},"stale":[],"version":1}`), 0o644)
	c := NewCachingDrive(inner, file)
	l, _ := c.List("d")
	eq(t, 0, len(inner.lists))
	eq(t, 60, l[0].Duration)
}

func TestAgentDecidesAsksAndLeavesUnhandled(t *testing.T) {
	var batches [][]string
	e := newSvcEnv(t, func(e *svcEnv, batch []PendingWork) AgentResult {
		var keys []string
		for _, w := range batch {
			keys = append(keys, w.Key)
		}
		batches = append(batches, keys)
		// AI: 认出芙莉莲, 问一部, 剩下一部没管
		_, _ = e.service.Store.Update(func(m *Mapping) error {
			m.Entries["没看/葬送的芙莉莲"] = MappingEntry{Target: "tv:209867", Path: "没看/葬送的芙莉莲", By: "skill"}
			return nil
		})
		for _, w := range batch {
			if strings.Contains(w.Key, "乱码") {
				_ = e.service.Review.Add(ReviewEntry{Work: w, Reason: "看不出是什么", Guess: "movie:438631"})
			}
		}
		return okResult("识别 1 部, 问了 1 部")
	})
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv") // 规则自动识别
	touch(t, e.root, "没看/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][12][1080P].mkv")
	touch(t, e.root, "没看/乱码ΣΩ/xx.mkv")
	touch(t, e.root, "没看/另一个/yy.mkv")

	e.service.Submit(ScanJob{false, "测试"})
	e.runAll()

	eq(t, 1, len(batches), "一批就交完, AI 没处理的不会马上再交")
	eq(t, 3, len(batches[0]))
	m, _ := e.service.Store.Load()
	eq(t, "tv:221851", m.Entries["没看/漫长的季节 (2023)"].Target)
	eq(t, "tv:209867", m.Entries["没看/葬送的芙莉莲"].Target)
	review, _ := e.service.Review.Load()
	byKey := map[string]ReviewEntry{}
	for _, r := range review {
		byKey[r.Work.Key] = r
	}
	eq(t, "ai", byKey["没看/乱码ΣΩ/xx.mkv"].By)
	eq(t, "movie:438631", byKey["没看/乱码ΣΩ/xx.mkv"].Guess)
	eq(t, "unhandled", byKey["没看/另一个/yy.mkv"].By)
	entries, _ := os.ReadDir(filepath.Join(e.root, ".arisa"))
	n := 0
	for _, x := range entries {
		if IsIndexFile(x.Name()) {
			n++
		}
	}
	eq(t, 1, n)

	// 再扫一次: 待确认里的不再交给 AI
	e.service.Submit(ScanJob{false, "测试"})
	e.runAll()
	eq(t, 1, len(batches))

	// 用户在网页上确认 → 移出待确认, 重新生成索引
	if err := e.service.Decide("没看/另一个/yy.mkv", "skip", nil, "没看/另一个/yy.mkv", "没看/另一个/yy.mkv", nil); err != nil {
		t.Fatal(err)
	}
	e.runAll()
	eqStr(t, []string{"没看/乱码ΣΩ/xx.mkv"}, sortedKeys(e.service.Review.Keys()))
	eq(t, 1, *e.service.Status().PendingWorks)

	// 重新交给 AI
	e.service.Retry("没看/乱码ΣΩ/xx.mkv")
	e.runAll()
	eqStr(t, []string{"没看/乱码ΣΩ/xx.mkv"}, batches[len(batches)-1])
}

func TestAgentErrorGoesToReview(t *testing.T) {
	e := newSvcEnv(t, func(*svcEnv, []PendingWork) AgentResult { return AgentResult{Ok: false, Summary: "Not logged in"} })
	touch(t, e.root, "没看/乱码ΣΩ/xx.mkv")
	e.service.Submit(ScanJob{false, "测试"})
	e.runAll()
	review, _ := e.service.Review.Load()
	eq(t, 1, len(review))
	eq(t, "error", review[0].By)
	if !strings.Contains(review[0].Reason, "Not logged in") {
		t.Fatal(review[0].Reason)
	}
}

func TestWatchRecent(t *testing.T) {
	e := newSvcEnv(t, func(*svcEnv, []PendingWork) AgentResult { return okResult("") })
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv")
	e.service.Submit(ScanJob{false, "启动"})
	e.runAll()
	e.drive.records = append(e.drive.records, quark.RecentRecord{Source: "SAVE_SHARE", Time: 1})
	_ = e.service.Poll() // 第一次只记位置
	eq(t, false, e.service.RunNext())

	// 新的一集上传到已扫描过的目录
	touch(t, e.root, "没看/漫长的季节 (2023)/E02.mkv")
	e.drive.records = append(e.drive.records, quark.RecentRecord{Source: "PLAY", Time: 2, Files: []quark.File{{Fid: "x", Name: "x.mkv", ParentFid: "没看/漫长的季节 (2023)"}}})
	_ = e.service.Poll()
	eq(t, false, e.service.RunNext(), "播放记录不算变化")

	e.drive.records = append(e.drive.records, quark.RecentRecord{Source: "UPLOAD_FILE", Time: 3, Files: []quark.File{{Fid: "没看/漫长的季节 (2023)/E02.mkv", Name: "E02.mkv", ParentFid: "没看/漫长的季节 (2023)"}}})
	e.drive.lists = nil
	_ = e.service.Poll()
	e.runAll()
	// 增量: 只重新列了变化的目录 (以及写索引时的 .arisa 目录)
	seen := map[string]bool{}
	var listed []string
	for _, l := range e.drive.lists {
		if l != "" && !strings.HasPrefix(l, ".arisa") && !seen[l] {
			seen[l] = true
			listed = append(listed, l)
		}
	}
	eqStr(t, []string{"没看/漫长的季节 (2023)"}, listed)
	eqInts(t, []int{1, 2}, episodesOf(e.latestIndex(t).Items[0]))

	// 同一条动态不会重复触发
	_ = e.service.Poll()
	eq(t, false, e.service.RunNext())

	// 删掉一集: 回收站里出现, 那个目录重新列, 索引里少一集
	_ = os.Remove(filepath.Join(e.root, "没看/漫长的季节 (2023)/E01.mkv"))
	e.drive.trash = append(e.drive.trash, quark.RecycledFile{Fid: "没看/漫长的季节 (2023)/E01.mkv", Name: "E01.mkv", ParentFid: "没看/漫长的季节 (2023)", Time: 10})
	_ = e.service.Poll()
	e.runAll()
	eqInts(t, []int{2}, episodesOf(e.latestIndex(t).Items[0]))

	// 整部删掉: 作品从索引里消失
	_ = os.RemoveAll(filepath.Join(e.root, "没看/漫长的季节 (2023)"))
	e.drive.trash = append(e.drive.trash, quark.RecycledFile{Fid: "没看/漫长的季节 (2023)", Name: "漫长的季节 (2023)", IsDir: true, ParentFid: "没看", Time: 11})
	_ = e.service.Poll()
	e.runAll()
	eq(t, 0, len(e.latestIndex(t).Items))
}

func TestPendingAndUndecideForWebPage(t *testing.T) {
	e := newSvcEnv(t, func(*svcEnv, []PendingWork) AgentResult { return okResult("") })
	touch(t, e.root, "没看/漫长的季节 (2023)/E01.mkv") // 规则自动识别
	touch(t, e.root, "没看/乱码ΣΩ/xx.mkv")
	if w, _ := e.service.PendingWorks(); w != nil {
		t.Fatal("没扫描过应该是 nil")
	}

	e.service.Submit(ScanJob{false, "测试"})
	e.runAll()
	w, _ := e.service.PendingWorks()
	eq(t, 1, len(w))
	eq(t, "没看/乱码ΣΩ/xx.mkv", w[0].Key)
	eq(t, 1, len(e.service.Library().Items))

	// 认错了: 撤销后回到未识别, 并排一次扫描
	if err := e.service.Undecide("没看/漫长的季节 (2023)"); err != nil {
		t.Fatal(err)
	}
	m, _ := e.service.Store.Load()
	eq(t, 0, len(m.Entries))
	w, _ = e.service.PendingWorks()
	eq(t, 2, len(w))
	if len(e.service.Status().Queued) == 0 {
		t.Fatal("应该排了扫描")
	}
}

func TestBatchKeysKeepsTopDirTogether(t *testing.T) {
	w := func(key, top string) PendingWork { return PendingWork{Key: key, Dirs: []PendingDir{{top, top}}} }
	keys := batchKeys([]PendingWork{w("a1", "A"), w("a2", "A"), w("b1", "B"), w("c1", "C")}, 3)
	got := sortedKeys(keys)
	sort.Strings(got)
	eqStr(t, []string{"a1", "a2", "b1"}, got)
}
