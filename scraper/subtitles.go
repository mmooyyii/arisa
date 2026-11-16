package main

// 移植自 src/main/kotlin/arisa/scraper/Subtitles.kt.
// 找视频旁边的外挂字幕, 从文件名猜语言.

import (
	"regexp"
	"sort"
	"strings"

	"github.com/mmooyyii/arisa/scraper/quark"
)

var subtitleExtensions = map[string]bool{"srt": true, "ass": true, "ssa": true, "vtt": true}

// IsSubtitle 对应 Subtitles.isSubtitle.
func IsSubtitle(name string) bool {
	return subtitleExtensions[ktLower(ktSubstringAfterLast(name, '.', ""))]
}

// SubtitlesOf 对应 Subtitles.of:
// 同目录下文件名以视频名 (去掉扩展名) 开头的字幕; 目录里只有这一个视频时, 所有字幕都算它的.
func SubtitlesOf(video quark.File, siblings []quark.File) []SubtitleFile {
	var subs []quark.File
	for _, it := range siblings {
		if !it.IsDir && IsSubtitle(it.Name) {
			subs = append(subs, it)
		}
	}
	if len(subs) == 0 {
		return []SubtitleFile{}
	}
	base := ktSubstringBeforeLast(video.Name, '.')
	videos := 0
	for _, it := range siblings {
		if !it.IsDir && IsVideo(it.Name) {
			videos++
		}
	}
	onlyVideo := videos <= 1
	out := []SubtitleFile{}
	for _, it := range subs {
		if onlyVideo || ktStartsWithIgnoreCase(it.Name, base) {
			// 原文 removePrefix 是区分大小写的
			out = append(out, SubtitleFile{Fid: it.Fid, Name: it.Name, Language: SubtitleLanguage(strings.TrimPrefix(it.Name, base))})
		}
	}
	// sortedBy 是稳定排序, String 按 UTF-16 单元比较
	sort.SliceStable(out, func(i, j int) bool { return ktCompare(out[i].Name, out[j].Name) < 0 })
	return out
}

var subtitleTokenSplit = regexp.MustCompile(`[.\[\]()_ \t\n\x0B\f\r-]+`)

// SubtitleLanguage 对应 Subtitles.language: 按文件名里的标记猜语言, 简繁双语算简体. 猜不出返回 "".
func SubtitleLanguage(name string) string {
	n := ktSubstringBeforeLast(ktLower(name), '.')
	tokens := map[string]bool{}
	for _, t := range subtitleTokenSplit.Split(n, -1) {
		if t != "" {
			tokens[t] = true
		}
	}
	has := func(words ...string) bool {
		for _, it := range words {
			if tokens[it] {
				return true
			}
			cjk := false
			for _, c := range it {
				if c > 0x2e80 {
					cjk = true
				}
			}
			if cjk && strings.Contains(n, it) {
				return true
			}
		}
		return false
	}
	switch {
	case has("chs", "sc", "gb", "hans", "zh-hans", "zh-cn", "简体", "简中", "简日", "简英", "简繁", "chs&cht", "chs_cht"):
		return "zh-Hans"
	case has("cht", "tc", "big5", "hant", "zh-hant", "zh-tw", "zh-hk", "繁体", "繁體", "繁中", "繁日"):
		return "zh-Hant"
	case has("zh", "chi", "zho", "chn", "chinese", "中文", "中字"):
		return "zh"
	case has("en", "eng", "english", "英文"):
		return "en"
	case has("ja", "jp", "jpn", "japanese", "日文", "日语"):
		return "ja"
	case has("ko", "kor", "korean", "韩文"):
		return "ko"
	default:
		return ""
	}
}
