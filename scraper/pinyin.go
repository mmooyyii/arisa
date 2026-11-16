package main

// 移植自 src/main/kotlin/arisa/scraper/Pinyin.kt 的 encode,
// 以及 library/src/main/kotlin/arisa/library/Search.kt 的 LibrarySearch.normalize.

import (
	"strings"
	"unicode"

	"github.com/mozillazg/go-pinyin"
)

// 原文用 pinyin4j: 不带声调, ü 写成 v, 小写, 多音字列出所有读音.
var pinyinArgs = func() pinyin.Args {
	a := pinyin.NewArgs()
	a.Style = pinyin.Normal
	a.Heteronym = true
	// 没有拼音的字返回空, 由 PinyinEncode 原样输出
	a.Fallback = func(rune, pinyin.Args) []string { return nil }
	return a
}()

// PinyinEncode 生成索引里搜索用的拼音 (Library.pinyin), TV 上只做匹配.
// "长安3" → "chang|zhang an 3": 去掉空格标点后逐字, 多音字列出所有读音, 没拼音的字原样.
// 输出的读音个数和 Normalize 后的 rune 数一样, TV 端靠这个对齐.
func PinyinEncode(name string) string {
	norm := []rune(Normalize(name))
	out := make([]string, 0, len(norm))
	for _, c := range norm {
		var readings []string
		seen := map[string]bool{}
		for _, p := range pinyin.SinglePinyin(c, pinyinArgs) {
			p = pinyinClean(p)
			if p != "" && !seen[p] {
				seen[p] = true
				readings = append(readings, p)
			}
		}
		if len(readings) == 0 {
			out = append(out, string(c))
		} else {
			out = append(out, strings.Join(readings, "|"))
		}
	}
	return strings.Join(out, " ")
}

// pinyinClean 把 go-pinyin 不带声调风格里残留的符号收拾成 pinyin4j WITH_V 的样子:
// ü → v; ê (含带声调的 ế ề, 如 "欸") → e; 去掉残留的组合声调符 (如 "呣" 的 m̀), 只留 a-z.
func pinyinClean(p string) string {
	var b strings.Builder
	for _, r := range p {
		switch {
		case r >= 'a' && r <= 'z':
			b.WriteRune(r)
		case r == 'ü':
			b.WriteRune('v')
		case r == 'ê' || r == 'ế' || r == 'ề':
			b.WriteRune('e')
		}
	}
	return b.String()
}

// Normalize 对应 LibrarySearch.normalize: 转小写, 只留字母和数字.
// 原文逐个 UTF-16 单元判断 Character.isLetterOrDigit, 代理对两半都不算, 所以 BMP 以外的字符丢掉.
func Normalize(s string) string {
	var b strings.Builder
	for _, r := range ktLower(s) {
		if r <= 0xFFFF && (unicode.IsLetter(r) || unicode.IsDigit(r)) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// IsHan 对应 LibrarySearch.isHan: Unicode script 是 Han.
// 原文参数是 Char, BMP 以外的汉字 (代理对) 的 script 是 UNKNOWN, 这里照样不算.
func IsHan(r rune) bool { return r <= 0xFFFF && unicode.Is(unicode.Han, r) }
