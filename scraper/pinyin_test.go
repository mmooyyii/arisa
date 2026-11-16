package main

// 移植自 src/test/kotlin/arisa/scraper/PinyinTest.kt 的 encode.
// 多音字读音顺序可能和 pinyin4j 不同, 按集合比较.

import (
	"reflect"
	"sort"
	"strings"
	"testing"
)

// readingSets 把 "liu lang di|de" 拆成每个字排好序的读音.
func readingSets(s string) [][]string {
	var out [][]string
	for _, w := range strings.Split(s, " ") {
		r := strings.Split(w, "|")
		sort.Strings(r)
		out = append(out, r)
	}
	return out
}

func assertPinyin(t *testing.T, name, want string) {
	t.Helper()
	got := PinyinEncode(name)
	if !reflect.DeepEqual(readingSets(want), readingSets(got)) {
		t.Errorf("%s: want %q got %q", name, want, got)
	}
	if n := len([]rune(Normalize(name))); n != len(strings.Split(got, " ")) {
		t.Errorf("%s: 读音数 %d 和字数 %d 对不上", name, len(strings.Split(got, " ")), n)
	}
}

func TestPinyinEncode(t *testing.T) {
	assertPinyin(t, "流浪地球 2", "liu lang di|de qiu 2")
	first := strings.Split(strings.Split(PinyinEncode("长安"), " ")[0], "|")
	sort.Strings(first)
	if !reflect.DeepEqual(first, []string{"chang", "zhang"}) {
		t.Errorf("长: got %q", first)
	}
	assertPinyin(t, "蜘蛛侠：S·H", "zhi zhu xia s h")
	assertPinyin(t, "吕", "lv")
}

// 移植时补的: 输出的读音数必须和 Normalize 后的字数一样.
func TestPinyinAlignment(t *testing.T) {
	for _, s := range []string{"", "🎬流浪地球𠀀2", "İSTANBUL", "ＡＢＣ１２３", "欸呣嗯", "长安三万里", "ΟΔΟΣ"} {
		got := PinyinEncode(s)
		n := len([]rune(Normalize(s)))
		words := strings.Split(got, " ")
		if got == "" {
			words = nil
		}
		if len(words) != n {
			t.Errorf("%q: %q 有 %d 段, Normalize 有 %d 字", s, got, len(words), n)
		}
		for _, w := range words {
			if w == "" || strings.Contains(w, "||") {
				t.Errorf("%q: 空读音 %q", s, got)
			}
		}
	}
	if got := PinyinEncode("欸"); strings.ContainsAny(got, "êếề") {
		t.Errorf("欸: %q", got)
	}
	if got := Normalize("Ab 1·𠀀。"); got != "ab1" {
		t.Errorf("Normalize: %q", got)
	}
}
