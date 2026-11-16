package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/mmooyyii/arisa/scraper/quark"
)

// LoginExpiredError: 夸克登录失效, 要重新登录. 命令行遇到它退出码是 3.
type LoginExpiredError struct{ Msg string }

func (e *LoginExpiredError) Error() string { return e.Msg }

func isLoginExpired(err error) bool {
	var l *LoginExpiredError
	return errors.As(err, &l)
}

// Drive 是刮削器用到的网盘操作, 抽出来方便用本地目录测试.
type Drive interface {
	RootFid() string
	List(fid string) ([]quark.File, error)
	Download(fid string) ([]byte, error)
	Upload(parentFid, name string, data []byte) (string, error)
	Delete(fids []string) error
	Mkdir(parentFid, name string) (string, error)
	Rename(fid, newName string) error
	// Recent 网盘最近的变化 (转存、上传…), 不支持的实现返回空.
	Recent() ([]quark.RecentRecord, error)
	// Recycled 最近删除的 (回收站), 不支持的实现返回空.
	Recycled() ([]quark.RecycledFile, error)
	// Thumbnail 夸克为视频生成的缩略图.
	Thumbnail(f quark.File) ([]byte, error)
}

// Resolve 按 "/电影/沙丘" 这样的路径找文件或目录, 不存在返回 nil.
func Resolve(d Drive, path string) (*quark.File, error) {
	cur := quark.File{Fid: d.RootFid(), IsDir: true}
	for _, part := range strings.Split(path, "/") {
		if part == "" {
			continue
		}
		list, err := d.List(cur.Fid)
		if err != nil {
			return nil, err
		}
		found := false
		for _, f := range list {
			if f.Name == part {
				cur, found = f, true
				break
			}
		}
		if !found {
			return nil, nil
		}
	}
	return &cur, nil
}

// findDir 在 parent 里找名字为 name 的目录, 没有时建一个.
func findOrMkdir(d Drive, parent, name string) (string, error) {
	list, err := d.List(parent)
	if err != nil {
		return "", err
	}
	for _, f := range list {
		if f.IsDir && f.Name == name {
			return f.Fid, nil
		}
	}
	return d.Mkdir(parent, name)
}

// findDir 找目录, 没有返回 "".
func findDir(d Drive, parent, name string) (string, error) {
	list, err := d.List(parent)
	if err != nil {
		return "", err
	}
	for _, f := range list {
		if f.IsDir && f.Name == name {
			return f.Fid, nil
		}
	}
	return "", nil
}

// RateLimiter 按每秒次数限速, 避免触发夸克风控.
type RateLimiter struct {
	mu       sync.Mutex
	interval time.Duration
	last     time.Time
}

func NewRateLimiter(perSecond float64) *RateLimiter {
	if perSecond <= 0 {
		perSecond = 1
	}
	return &RateLimiter{interval: time.Duration(float64(time.Second) / perSecond)}
}

func (r *RateLimiter) Acquire() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if wait := time.Until(r.last.Add(r.interval)); wait > 0 {
		time.Sleep(wait)
	}
	r.last = time.Now()
}

// QuarkDrive 是真的夸克网盘, 带限速和重试.
type QuarkDrive struct {
	client      *quark.WebClient
	listLimiter *RateLimiter
	writeLimit  *RateLimiter
}

func NewQuarkDrive(client *quark.WebClient, rate RateLimit) *QuarkDrive {
	return &QuarkDrive{client, NewRateLimiter(rate.ListPerSec), NewRateLimiter(rate.UploadPerSec)}
}

// retry 失败重试 3 次; 登录失效不重试, 换成 LoginExpiredError.
func retry[T any](limiter *RateLimiter, f func() (T, error)) (T, error) {
	var zero T
	for attempt := 1; ; attempt++ {
		if limiter != nil {
			limiter.Acquire()
		}
		v, err := f()
		if err == nil {
			return v, nil
		}
		if quark.IsAuth(err) {
			return zero, &LoginExpiredError{"夸克登录失效, 运行 arisa-scraper login 重新登录 (" + err.Error() + ")"}
		}
		if attempt >= 3 {
			return zero, err
		}
		time.Sleep(time.Duration(2*attempt) * time.Second)
	}
}

func (q *QuarkDrive) RootFid() string { return quark.Root }
func (q *QuarkDrive) List(fid string) ([]quark.File, error) {
	return retry(q.listLimiter, func() ([]quark.File, error) { return q.client.List(fid) })
}
func (q *QuarkDrive) Download(fid string) ([]byte, error) {
	return retry(q.listLimiter, func() ([]byte, error) { return q.client.Download(fid) })
}
func (q *QuarkDrive) Upload(parent, name string, data []byte) (string, error) {
	return retry(q.writeLimit, func() (string, error) { return q.client.Upload(parent, name, data) })
}
func (q *QuarkDrive) Delete(fids []string) error {
	if len(fids) == 0 {
		return nil
	}
	_, err := retry(q.writeLimit, func() (struct{}, error) { return struct{}{}, q.client.Delete(fids) })
	return err
}
func (q *QuarkDrive) Mkdir(parent, name string) (string, error) {
	return retry(q.writeLimit, func() (string, error) { return q.client.Mkdir(parent, name) })
}
func (q *QuarkDrive) Rename(fid, name string) error {
	_, err := retry(q.writeLimit, func() (struct{}, error) { return struct{}{}, q.client.Rename(fid, name) })
	return err
}
func (q *QuarkDrive) Recent() ([]quark.RecentRecord, error) {
	return retry(q.listLimiter, func() ([]quark.RecentRecord, error) { return q.client.Recent(50) })
}
func (q *QuarkDrive) Recycled() ([]quark.RecycledFile, error) {
	return retry(q.listLimiter, func() ([]quark.RecycledFile, error) { return q.client.Recycled(50) })
}
func (q *QuarkDrive) Thumbnail(f quark.File) ([]byte, error) {
	if f.Thumbnail == "" {
		return nil, errors.New("夸克没有为这个文件生成缩略图")
	}
	return retry(nil, func() ([]byte, error) { return q.client.Thumbnail(f.Thumbnail) })
}

// LocalDrive 用本地目录模拟网盘, fid 就是相对路径. 测试和离线调试用.
type LocalDrive struct{ Root string }

func (l *LocalDrive) file(fid string) string {
	if fid == "" {
		return l.Root
	}
	return filepath.Join(l.Root, fid)
}

func (l *LocalDrive) rel(p string) string {
	r, _ := filepath.Rel(l.Root, p)
	return r
}

func (l *LocalDrive) RootFid() string { return "" }

func (l *LocalDrive) List(fid string) ([]quark.File, error) {
	entries, err := os.ReadDir(l.file(fid))
	if err != nil {
		return nil, nil
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	var out []quark.File
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		f := quark.File{Fid: l.rel(filepath.Join(l.file(fid), e.Name())), Name: e.Name(), IsDir: e.IsDir(), UpdatedAt: info.ModTime().UnixMilli()}
		if !e.IsDir() {
			f.Size, f.Category = info.Size(), 1
		}
		out = append(out, f)
	}
	return out, nil
}

func (l *LocalDrive) Download(fid string) ([]byte, error) { return os.ReadFile(l.file(fid)) }

func (l *LocalDrive) Upload(parent, name string, data []byte) (string, error) {
	p := filepath.Join(l.file(parent), name)
	return l.rel(p), os.WriteFile(p, data, 0o644)
}

func (l *LocalDrive) Delete(fids []string) error {
	for _, f := range fids {
		if err := os.RemoveAll(l.file(f)); err != nil {
			return err
		}
	}
	return nil
}

func (l *LocalDrive) Mkdir(parent, name string) (string, error) {
	p := filepath.Join(l.file(parent), name)
	return l.rel(p), os.MkdirAll(p, 0o755)
}

func (l *LocalDrive) Rename(fid, name string) error {
	p := l.file(fid)
	if err := os.Rename(p, filepath.Join(filepath.Dir(p), name)); err != nil {
		return fmt.Errorf("rename failed: %s", fid)
	}
	return nil
}

func (l *LocalDrive) Recent() ([]quark.RecentRecord, error)   { return nil, nil }
func (l *LocalDrive) Recycled() ([]quark.RecycledFile, error) { return nil, nil }
func (l *LocalDrive) Thumbnail(quark.File) ([]byte, error) {
	return nil, errors.New("不支持缩略图")
}

// CachingDrive: 目录列表缓存在本地, 只有被 Invalidate 的目录才重新列, 常驻服务靠它做增量扫描.
// 失效的目录保留旧列表、只标记过期, 这样 IsListed 仍然知道它在片库里.
// 写操作会让相关目录失效. 移动、删除不一定能从「最近」里看出来, 所以要定期 InvalidateAll.
type CachingDrive struct {
	inner    Drive
	file     string
	mu       sync.Mutex
	listings map[string][]quark.File
	stale    map[string]bool
}

// 缓存文件格式和旧版一致 (version 1), 升级、回退都能接着用.
type driveCache struct {
	Listings map[string][]quark.File `json:"listings,omitempty"`
	Stale    []string                `json:"stale,omitempty"`
	Version  int                     `json:"version,omitempty"`
}

const driveCacheVersion = 1

func NewCachingDrive(inner Drive, file string) *CachingDrive {
	c := &CachingDrive{inner: inner, file: file, listings: map[string][]quark.File{}, stale: map[string]bool{}}
	if data, err := os.ReadFile(file); err == nil {
		var dc driveCache
		if json.Unmarshal(data, &dc) == nil && dc.Version == driveCacheVersion {
			for k, v := range dc.Listings {
				c.listings[k] = v
			}
			for _, s := range dc.Stale {
				c.stale[s] = true
			}
		}
	}
	return c
}

func (c *CachingDrive) RootFid() string { return c.inner.RootFid() }

func (c *CachingDrive) List(fid string) ([]quark.File, error) {
	c.mu.Lock()
	if l, ok := c.listings[fid]; ok && !c.stale[fid] {
		c.mu.Unlock()
		return l, nil
	}
	c.mu.Unlock()
	l, err := c.inner.List(fid)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.listings[fid] = l
	delete(c.stale, fid)
	c.mu.Unlock()
	return l, nil
}

// Cached 缓存里的文件信息 (含缩略图地址).
func (c *CachingDrive) Cached(fid string) *quark.File {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, l := range c.listings {
		for _, f := range l {
			if f.Fid == fid {
				f := f
				return &f
			}
		}
	}
	return nil
}

// Fresh 重新列一次文件所在目录, 拿到最新的信息 (缩略图地址会过期).
func (c *CachingDrive) Fresh(fid string) (*quark.File, error) {
	c.mu.Lock()
	parent, found := "", false
	for dir, l := range c.listings {
		for _, f := range l {
			if f.Fid == fid {
				parent, found = dir, true
			}
		}
	}
	if found {
		c.stale[parent] = true
	}
	c.mu.Unlock()
	if !found {
		return nil, nil
	}
	l, err := c.List(parent)
	if err != nil {
		return nil, err
	}
	for _, f := range l {
		if f.Fid == fid {
			f := f
			return &f, nil
		}
	}
	return nil, nil
}

// IsListed 列过的目录 (含已过期的).
func (c *CachingDrive) IsListed(fid string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	_, ok := c.listings[fid]
	return ok
}

func (c *CachingDrive) Invalidate(fid string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.listings[fid]; ok {
		c.stale[fid] = true
	}
}

// Forget 目录被删了: 丢掉它的列表.
func (c *CachingDrive) Forget(fid string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.listings, fid)
	delete(c.stale, fid)
}

func (c *CachingDrive) InvalidateAll() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.listings = map[string][]quark.File{}
	c.stale = map[string]bool{}
}

func (c *CachingDrive) Save() error {
	c.mu.Lock()
	dc := driveCache{Listings: c.listings, Version: driveCacheVersion}
	for s := range c.stale {
		dc.Stale = append(dc.Stale, s)
	}
	sort.Strings(dc.Stale)
	data, err := json.Marshal(dc)
	c.mu.Unlock()
	if err != nil {
		return err
	}
	return writeFileAtomic(c.file, data, 0o644)
}

func (c *CachingDrive) Download(fid string) ([]byte, error)     { return c.inner.Download(fid) }
func (c *CachingDrive) Recent() ([]quark.RecentRecord, error)   { return c.inner.Recent() }
func (c *CachingDrive) Recycled() ([]quark.RecycledFile, error) { return c.inner.Recycled() }
func (c *CachingDrive) Thumbnail(f quark.File) ([]byte, error)  { return c.inner.Thumbnail(f) }

func (c *CachingDrive) Upload(parent, name string, data []byte) (string, error) {
	fid, err := c.inner.Upload(parent, name, data)
	c.Invalidate(parent)
	return fid, err
}

func (c *CachingDrive) Delete(fids []string) error {
	err := c.inner.Delete(fids)
	c.invalidateParentsOf(fids)
	c.mu.Lock()
	for _, f := range fids {
		delete(c.listings, f)
		delete(c.stale, f)
	}
	c.mu.Unlock()
	return err
}

func (c *CachingDrive) Mkdir(parent, name string) (string, error) {
	fid, err := c.inner.Mkdir(parent, name)
	c.Invalidate(parent)
	return fid, err
}

func (c *CachingDrive) Rename(fid, name string) error {
	err := c.inner.Rename(fid, name)
	c.invalidateParentsOf([]string{fid})
	return err
}

func (c *CachingDrive) invalidateParentsOf(fids []string) {
	set := map[string]bool{}
	for _, f := range fids {
		set[f] = true
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for dir, l := range c.listings {
		for _, f := range l {
			if set[f.Fid] {
				c.stale[dir] = true
				break
			}
		}
	}
}

// LazyDrive 用到时才创建 (启动时可能还没登录), Reset 后下次重新创建, 换 Cookie 时用.
type LazyDrive struct {
	mu      sync.Mutex
	factory func() (Drive, error)
	drive   Drive
}

func (l *LazyDrive) Reset() {
	l.mu.Lock()
	l.drive = nil
	l.mu.Unlock()
}

func (l *LazyDrive) d() (Drive, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.drive == nil {
		d, err := l.factory()
		if err != nil {
			return nil, err
		}
		l.drive = d
	}
	return l.drive, nil
}

func (l *LazyDrive) RootFid() string {
	d, err := l.d()
	if err != nil {
		return quark.Root
	}
	return d.RootFid()
}
func (l *LazyDrive) List(fid string) ([]quark.File, error) {
	d, err := l.d()
	if err != nil {
		return nil, err
	}
	return d.List(fid)
}
func (l *LazyDrive) Download(fid string) ([]byte, error) {
	d, err := l.d()
	if err != nil {
		return nil, err
	}
	return d.Download(fid)
}
func (l *LazyDrive) Upload(p, n string, b []byte) (string, error) {
	d, err := l.d()
	if err != nil {
		return "", err
	}
	return d.Upload(p, n, b)
}
func (l *LazyDrive) Delete(fids []string) error {
	d, err := l.d()
	if err != nil {
		return err
	}
	return d.Delete(fids)
}
func (l *LazyDrive) Mkdir(p, n string) (string, error) {
	d, err := l.d()
	if err != nil {
		return "", err
	}
	return d.Mkdir(p, n)
}
func (l *LazyDrive) Rename(fid, n string) error {
	d, err := l.d()
	if err != nil {
		return err
	}
	return d.Rename(fid, n)
}
func (l *LazyDrive) Recent() ([]quark.RecentRecord, error) {
	d, err := l.d()
	if err != nil {
		return nil, err
	}
	return d.Recent()
}
func (l *LazyDrive) Recycled() ([]quark.RecycledFile, error) {
	d, err := l.d()
	if err != nil {
		return nil, err
	}
	return d.Recycled()
}
func (l *LazyDrive) Thumbnail(f quark.File) ([]byte, error) {
	d, err := l.d()
	if err != nil {
		return nil, err
	}
	return d.Thumbnail(f)
}
