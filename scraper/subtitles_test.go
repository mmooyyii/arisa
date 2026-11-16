package main

// 移植自 src/test/kotlin/arisa/scraper/SubtitlesTest.kt.

import (
	"reflect"
	"testing"

	"github.com/mmooyyii/arisa/scraper/quark"
)

func subF(name string, dir bool) quark.File { return quark.File{Fid: name, Name: name, IsDir: dir} }

func TestSubtitleLanguage(t *testing.T) {
	for name, want := range map[string]string{
		".chs.ass":     "zh-Hans",
		".简体中文.srt":    "zh-Hans",
		"[CHT].ass":    "zh-Hant",
		".chs&cht.ass": "zh-Hans",
		".en.srt":      "en",
		".jpn.ass":     "ja",
		".srt":         "",
	} {
		if got := SubtitleLanguage(name); got != want {
			t.Errorf("%s: want %q got %q", name, want, got)
		}
	}
}

func TestSubtitlesMatchByName(t *testing.T) {
	v1 := subF("Show.S01E01.mkv", false)
	siblings := []quark.File{v1, subF("Show.S01E02.mkv", false), subF("Show.S01E01.chs.ass", false), subF("Show.S01E02.chs.ass", false), subF("Fonts", true)}
	var names []string
	for _, s := range SubtitlesOf(v1, siblings) {
		names = append(names, s.Name)
	}
	if !reflect.DeepEqual(names, []string{"Show.S01E01.chs.ass"}) {
		t.Errorf("got %q", names)
	}
}

func TestSubtitlesOnlyVideoTakesAll(t *testing.T) {
	v := subF("movie.mkv", false)
	var got [][2]string
	for _, s := range SubtitlesOf(v, []quark.File{v, subF("简体.srt", false), subF("English.srt", false)}) {
		got = append(got, [2]string{s.Name, s.Language})
	}
	want := [][2]string{{"English.srt", "en"}, {"简体.srt", "zh-Hans"}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %q", got)
	}
}
