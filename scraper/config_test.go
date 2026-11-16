package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestConfigRoundTripOnlyNonDefaults(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "config.yaml")
	_ = os.WriteFile(file, []byte("# 旧文件\ntmdb:\n  api_key: abc\n"), 0o644)
	c, err := LoadConfigFile(file)
	if err != nil {
		t.Fatal(err)
	}
	out := OutputIndex
	proxy := "http://127.0.0.1:7890"
	c.Libraries = []LibraryConfig{{Path: "/电影", Type: LibMovie}, {Path: "/动画", Type: LibAuto, Output: &out}}
	c.Update = UpdateConfig{SelfUpdate: false, App: true, CheckHours: 6, Proxy: &proxy}
	if err := SaveConfig(file, c); err != nil {
		t.Fatal(err)
	}
	back, err := LoadConfigFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(c, back) {
		t.Fatalf("读回来不一样:\n%+v\n%+v", c, back)
	}
	bak, _ := os.ReadFile(file + ".bak")
	if !strings.HasPrefix(string(bak), "# 旧文件") {
		t.Fatal("没留 .bak")
	}
	text, _ := os.ReadFile(file)
	if strings.Contains(string(text), "poll_seconds") {
		t.Fatal("默认值不写: " + string(text))
	}
	if !strings.Contains(string(text), "self_update: false") || !strings.Contains(string(text), "api_key: abc") {
		t.Fatal(string(text))
	}
	if strings.Contains(string(text), "type: auto") {
		t.Fatal("片库默认类型不写: " + string(text))
	}
	// 环境变量里的 key 不进文件
	env, _ := loadConfigEnv(file, func(k string) string {
		if k == "ARISA_TMDB_API_KEY" {
			return "env"
		}
		return ""
	})
	eq(t, "env", env.Tmdb.APIKey)
	raw, _ := LoadConfigFile(file)
	eq(t, "abc", raw.Tmdb.APIKey)
}

// 旧版 (kaml) 读写的配置: 不认识的字段忽略, 枚举值不对报错.
func TestConfigLenientAndValidated(t *testing.T) {
	file := filepath.Join(t.TempDir(), "config.yaml")
	_ = os.WriteFile(file, []byte("future_field: 1\nlibraries:\n  - path: /a\n    whatever: x\n"), 0o644)
	c, err := LoadConfigFile(file)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, LibAuto, c.Libraries[0].Type)
	_ = os.WriteFile(file, []byte("libraries:\n  - path: /a\n    type: anime\n"), 0o644)
	if _, err := LoadConfigFile(file); err == nil {
		t.Fatal("不认识的类型应该报错")
	}
}

// 网页上的设置用 JSON: 所有字段都在, 空的是 null.
func TestConfigJSONForWebPage(t *testing.T) {
	var m map[string]map[string]any
	_ = json.Unmarshal(ConfigJSON(DefaultConfig()), &m)
	if _, ok := m["tmdb"]["proxy"]; !ok || m["tmdb"]["proxy"] != nil {
		t.Fatal("proxy 应该是 null")
	}
	eq(t, "index+nfo", m["defaults"]["output"].(string))
	if _, err := ParseConfigJSON([]byte(`{"bogus":1}`)); err == nil {
		t.Fatal("不认识的字段应该报错")
	}
	c, err := ParseConfigJSON([]byte(`{"serve":{"port":8000}}`))
	if err != nil {
		t.Fatal(err)
	}
	eq(t, 8000, c.Serve.Port)
	eq(t, 90, c.Serve.PollSeconds)
}
