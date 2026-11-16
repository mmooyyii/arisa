package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"

	"gopkg.in/yaml.v3"
)

// 配置文件 ~/.arisa/config.yaml, 字段说明见 config.example.yaml.
// 字段名 (yaml/json 一样) 被管理网页和旧版本刮削器共用, 不能改.

// 输出模式, 见 README.
const (
	OutputIndex     = "index"      // 只写汇总索引
	OutputIndexNfo  = "index+nfo"  // 索引 + 电影 NFO + tvshow.nfo
	OutputIndexFull = "index+full" // 索引 + 全部 NFO (含单集) + 全部图片
)

// 视频旁已有 NFO 时怎么处理.
const (
	NfoTrust     = "trust"
	NfoMerge     = "merge"
	NfoIgnore    = "ignore"
	NfoOverwrite = "overwrite"
)

// 片库类型.
const (
	LibMovie = "movie"
	LibTV    = "tv"
	LibAuto  = "auto"
)

// LibraryConfig 是一个片库目录, 除 path/type 外为 nil 时用 defaults.
type LibraryConfig struct {
	Path                string   `yaml:"path" json:"path"`
	Type                string   `yaml:"type" json:"type"`
	Output              *string  `yaml:"output" json:"output"`
	ExistingNfo         *string  `yaml:"existing_nfo" json:"existing_nfo"`
	ConfidenceThreshold *float64 `yaml:"confidence_threshold" json:"confidence_threshold"`
	Language            *string  `yaml:"language" json:"language"`
	FallbackLanguage    *string  `yaml:"fallback_language" json:"fallback_language"`
}

type Defaults struct {
	Output      string `yaml:"output" json:"output"`
	ExistingNfo string `yaml:"existing_nfo" json:"existing_nfo"`
	// ConfidenceThreshold: auto 自动接受规则匹配的门槛, 低于它的交给 AI.
	ConfidenceThreshold float64 `yaml:"confidence_threshold" json:"confidence_threshold"`
	Language            string  `yaml:"language" json:"language"`
	FallbackLanguage    string  `yaml:"fallback_language" json:"fallback_language"`
}

type TmdbConfig struct {
	APIKey string `yaml:"api_key" json:"api_key"`
	// Proxy 可选代理, 如 http://127.0.0.1:7890 (默认走 api.tmdb.org, 国内能直连)
	Proxy *string `yaml:"proxy" json:"proxy"`
}

type BangumiConfig struct {
	// Proxy 可选代理 (api.bgm.tv 在国内可能被 DNS 污染)
	Proxy *string `yaml:"proxy" json:"proxy"`
}

type RateLimit struct {
	ListPerSec   float64 `yaml:"list_per_sec" json:"list_per_sec"`
	UploadPerSec float64 `yaml:"upload_per_sec" json:"upload_per_sec"`
}

type ScanConfig struct {
	// KeepIndexes 保留几个历史索引.
	KeepIndexes int `yaml:"keep_indexes" json:"keep_indexes"`
}

// AgentConfig 是常驻服务里自动识别用的 AI (DeepSeek).
type AgentConfig struct {
	Enabled bool `yaml:"enabled" json:"enabled"`
	// APIKey 也可以用环境变量 ARISA_DEEPSEEK_API_KEY. 为空时不启用 AI.
	APIKey string `yaml:"api_key" json:"api_key"`
	// Model 花费按 Flash 的价格算, 换成更贵的模型时网页上的花费会偏低.
	Model          string `yaml:"model" json:"model"`
	BatchSize      int    `yaml:"batch_size" json:"batch_size"`
	TimeoutMinutes int    `yaml:"timeout_minutes" json:"timeout_minutes"`
}

// On: 打开了并且有 key.
func (a AgentConfig) On() bool { return a.Enabled && strings.TrimSpace(a.APIKey) != "" }

type ServeConfig struct {
	Port int `yaml:"port" json:"port"`
	// PollSeconds 多久看一次夸克的「最近」.
	PollSeconds int `yaml:"poll_seconds" json:"poll_seconds"`
	// FullScanHour 每天几点全量重扫 (移动、删除在「最近」里看不到).
	FullScanHour int `yaml:"full_scan_hour" json:"full_scan_hour"`
}

type UpdateConfig struct {
	// SelfUpdate 刮削器自动更新到最新版 (只对 install.sh 装的生效).
	SelfUpdate bool `yaml:"self_update" json:"self_update"`
	// App 把新版 TV App 转存到网盘.
	App        bool    `yaml:"app" json:"app"`
	CheckHours int     `yaml:"check_hours" json:"check_hours"`
	Proxy      *string `yaml:"proxy" json:"proxy"`
}

type Config struct {
	Defaults  Defaults        `yaml:"defaults" json:"defaults"`
	Tmdb      TmdbConfig      `yaml:"tmdb" json:"tmdb"`
	Bangumi   BangumiConfig   `yaml:"bangumi" json:"bangumi"`
	RateLimit RateLimit       `yaml:"rate_limit" json:"rate_limit"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Agent     AgentConfig     `yaml:"agent" json:"agent"`
	Serve     ServeConfig     `yaml:"serve" json:"serve"`
	Update    UpdateConfig    `yaml:"update" json:"update"`
	Libraries []LibraryConfig `yaml:"libraries" json:"libraries"`
}

func DefaultConfig() Config {
	return Config{
		Defaults:  Defaults{Output: OutputIndexNfo, ExistingNfo: NfoTrust, ConfidenceThreshold: 0.97, Language: "zh-CN", FallbackLanguage: "en-US"},
		RateLimit: RateLimit{ListPerSec: 2, UploadPerSec: 1},
		Scan:      ScanConfig{KeepIndexes: 5},
		Agent:     AgentConfig{Enabled: true, Model: "deepseek-chat", BatchSize: 20, TimeoutMinutes: 30},
		Serve:     ServeConfig{Port: 7893, PollSeconds: 90, FullScanHour: 4},
		Update:    UpdateConfig{SelfUpdate: true, App: true, CheckHours: 6},
		Libraries: []LibraryConfig{},
	}
}

// Effective 是 defaults 和片库自己的覆盖项合并后的结果.
type Effective struct {
	Output              string
	ExistingNfo         string
	ConfidenceThreshold float64
	Language            string
	FallbackLanguage    string
}

func (c Config) Effective(lib LibraryConfig) Effective {
	e := Effective{c.Defaults.Output, c.Defaults.ExistingNfo, c.Defaults.ConfidenceThreshold, c.Defaults.Language, c.Defaults.FallbackLanguage}
	if lib.Output != nil {
		e.Output = *lib.Output
	}
	if lib.ExistingNfo != nil {
		e.ExistingNfo = *lib.ExistingNfo
	}
	if lib.ConfidenceThreshold != nil {
		e.ConfidenceThreshold = *lib.ConfidenceThreshold
	}
	if lib.Language != nil {
		e.Language = *lib.Language
	}
	if lib.FallbackLanguage != nil {
		e.FallbackLanguage = *lib.FallbackLanguage
	}
	return e
}

func oneOf(field, v string, allowed ...string) error {
	for _, a := range allowed {
		if v == a {
			return nil
		}
	}
	return fmt.Errorf("%s 只能是 %s, 不能是 %q", field, strings.Join(allowed, " / "), v)
}

// normalize 补上片库的默认类型, 检查枚举值.
func (c *Config) normalize() error {
	if c.Libraries == nil {
		c.Libraries = []LibraryConfig{}
	}
	outputs := []string{OutputIndex, OutputIndexNfo, OutputIndexFull}
	nfos := []string{NfoTrust, NfoMerge, NfoIgnore, NfoOverwrite}
	if err := oneOf("defaults.output", c.Defaults.Output, outputs...); err != nil {
		return err
	}
	if err := oneOf("defaults.existing_nfo", c.Defaults.ExistingNfo, nfos...); err != nil {
		return err
	}
	for i := range c.Libraries {
		l := &c.Libraries[i]
		if l.Type == "" {
			l.Type = LibAuto
		}
		if err := oneOf("libraries.type", l.Type, LibMovie, LibTV, LibAuto); err != nil {
			return err
		}
		if l.Output != nil {
			if err := oneOf("libraries.output", *l.Output, outputs...); err != nil {
				return err
			}
		}
		if l.ExistingNfo != nil {
			if err := oneOf("libraries.existing_nfo", *l.ExistingNfo, nfos...); err != nil {
				return err
			}
		}
	}
	return nil
}

// LoadConfigFile 只读配置文件, 不合并环境变量 (网页上编辑用, 免得把环境变量里的 key 写进文件). 不认识的字段忽略.
func LoadConfigFile(path string) (Config, error) {
	c := DefaultConfig()
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err := yaml.Unmarshal(data, &c); err != nil {
		return c, fmt.Errorf("配置文件 %s 格式不对: %w", path, err)
	}
	if err := c.normalize(); err != nil {
		return c, fmt.Errorf("配置文件 %s: %w", path, err)
	}
	return c, nil
}

// LoadConfig: key 也可以用环境变量传, 优先级高于配置文件.
func LoadConfig(path string) (Config, error) {
	return loadConfigEnv(path, os.Getenv)
}

func loadConfigEnv(path string, env func(string) string) (Config, error) {
	c, err := LoadConfigFile(path)
	if err != nil {
		return c, err
	}
	if v := strings.TrimSpace(env("ARISA_TMDB_API_KEY")); v != "" {
		c.Tmdb.APIKey = v
	}
	if v := strings.TrimSpace(env("ARISA_DEEPSEEK_API_KEY")); v != "" {
		c.Agent.APIKey = v
	}
	return c, nil
}

// toPlain 把结构转成 yaml 的通用值 (map/list/标量), 用来比较和裁剪默认值.
func toPlain(v any) any {
	data, _ := yaml.Marshal(v)
	var out any
	_ = yaml.Unmarshal(data, &out)
	return out
}

// prune 删掉和默认值一样的项, 返回裁剪后的值和是否还剩东西.
func prune(v, def any) (any, bool) {
	m, ok := v.(map[string]any)
	if !ok {
		return v, !reflect.DeepEqual(v, def)
	}
	dm, _ := def.(map[string]any)
	out := map[string]any{}
	for k, val := range m {
		if keep, ok := prune(val, dm[k]); ok {
			out[k] = keep
		}
	}
	return out, len(out) > 0
}

// SaveConfig 写配置文件, 只写和默认值不一样的项, 旧文件留一份 .bak. 文件里原来的注释会丢掉.
func SaveConfig(path string, c Config) error {
	if err := c.normalize(); err != nil {
		return err
	}
	// 片库的各项也只写非默认的
	libs := make([]any, 0, len(c.Libraries))
	for _, l := range c.Libraries {
		p, _ := prune(toPlain(l), toPlain(LibraryConfig{Type: LibAuto}))
		pm, _ := p.(map[string]any)
		if pm == nil {
			pm = map[string]any{}
		}
		pm["path"] = l.Path
		libs = append(libs, pm)
	}
	cc := c
	cc.Libraries = nil
	top, _ := prune(toPlain(cc), toPlain(func() Config { d := DefaultConfig(); d.Libraries = nil; return d }()))
	tm, _ := top.(map[string]any)
	if tm == nil {
		tm = map[string]any{}
	}
	delete(tm, "libraries")
	if len(libs) > 0 {
		tm["libraries"] = libs
	}
	var buf bytes.Buffer
	buf.WriteString("# 由管理网页保存, 字段说明见 config.example.yaml\n")
	if len(tm) > 0 {
		enc := yaml.NewEncoder(&buf)
		enc.SetIndent(2)
		if err := enc.Encode(orderedTop(tm)); err != nil {
			return err
		}
		_ = enc.Close()
	}
	// 写出去的能读回来才写
	check := DefaultConfig()
	if err := yaml.Unmarshal(buf.Bytes(), &check); err != nil {
		return fmt.Errorf("生成的配置读不回来: %w", err)
	}
	if old, err := os.ReadFile(path); err == nil {
		if err := os.WriteFile(path+".bak", old, 0o600); err != nil {
			return err
		}
	}
	return writeFileAtomic(path, buf.Bytes(), 0o600)
}

// orderedTop 按 Config 里字段的顺序输出顶层各段, 读起来和 config.example.yaml 一样.
func orderedTop(m map[string]any) *yaml.Node {
	order := []string{"defaults", "tmdb", "bangumi", "rate_limit", "scan", "agent", "serve", "update", "libraries"}
	node := &yaml.Node{Kind: yaml.MappingNode}
	for _, k := range order {
		v, ok := m[k]
		if !ok {
			continue
		}
		var val yaml.Node
		_ = val.Encode(v)
		node.Content = append(node.Content, &yaml.Node{Kind: yaml.ScalarNode, Value: k}, &val)
	}
	return node
}

// ConfigJSON / ParseConfigJSON 是网页上用的 JSON 形式: 所有字段都写出来, 空的是 null.
func ConfigJSON(c Config) json.RawMessage {
	data, _ := json.Marshal(c)
	return data
}

// ParseConfigJSON 解析网页提交的配置, 不认识的字段报错, 缺的用默认值.
func ParseConfigJSON(data []byte) (Config, error) {
	c := DefaultConfig()
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return c, err
	}
	if c.Libraries == nil {
		c.Libraries = []LibraryConfig{}
	}
	return c, c.normalize()
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(dirOf(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func dirOf(path string) string {
	i := strings.LastIndex(path, "/")
	if i <= 0 {
		return "/"
	}
	return path[:i]
}
