package main

// 移植自 src/main/kotlin/arisa/scraper/FilenameParser.kt, 逐行对应.
//
// 和 Kotlin 版的语义差异在这里统一抹平:
//   - Java 正则的 \d、\s 只认 ASCII, regexp2 (.NET 语义) 认 Unicode, 所以正则里一律写成 [0-9]、[ \t\n\x0B\f\r].
//   - 忽略大小写有两种: 正则里内联的 (?i) 只对 ASCII 忽略大小写; Kotlin 的 RegexOption.IGNORE_CASE
//     会自动加上 UNICODE_CASE, 这时 İ ı 算 i, ſ 算 s, 开尔文符号 K 算 k (在 JDK 21 上逐个码点实测过, 只有这 4 个
//     非 ASCII 字符会匹配上 ASCII 字母). regexp2 的 IgnoreCase 和这两种都不一样 (它只转小写, ſ 不算 s),
//     所以不用它: 这里把正则里的字母写成小写, 匹配前把输入里的 A-Z (以及 UNICODE_CASE 时的那 4 个字符)
//     转成对应的小写 ASCII 字母 (rune 数不变), 结果按下标回原串取.
//   - Kotlin 的 Char 是 UTF-16 单元, isLetter 之类对代理对 (BMP 以外的字符) 一律是 false, 这里照样复现.
//   - Kotlin 的 trim()、isBlank() 用的是 Char.isWhitespace, 包括全角空格等 Unicode 空白, 见 ktIsWhitespace.

import (
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"

	"github.com/dlclark/regexp2"
)

// ParsedName 是规则解析出的结果, Titles 按可信度排序 (中文名、英文名…).
type ParsedName struct {
	Titles  []string
	Year    *int
	Season  *int
	Episode *int
}

// IsEpisode 对应 Kotlin 的 isEpisode: episode != null.
func (p ParsedName) IsEpisode() bool { return p.Episode != nil }

// 基于规则的文件名解析. 解析不出片名或拿不准时交给 AI.

var videoExtensions = map[string]bool{
	"mkv": true, "mp4": true, "avi": true, "ts": true, "m2ts": true, "mts": true, "mov": true, "wmv": true,
	"flv": true, "rmvb": true, "rm": true, "webm": true, "m4v": true, "iso": true, "mpg": true, "mpeg": true,
}

// IsVideo 对应 FilenameParser.isVideo.
func IsVideo(name string) bool {
	return videoExtensions[ktLower(ktSubstringAfterLast(name, '.', ""))]
}

// SeasonOfDir 季目录, 如 "Season 01" "S2" "第一季".
func SeasonOfDir(name string) *int {
	n := ktTrim(name)
	if m := seasonDir.MatchEntire(n); m != nil {
		g := m.Groups[1]
		if g == "" {
			g = m.Groups[2]
		}
		return ParseNumber(g)
	}
	// "第一季（2013）全13集 内封字幕": 以第几季开头, 后面只是描述
	if m := seasonDirPrefix.Find(n); m != nil {
		return ParseNumber(m.Groups[1])
	}
	return nil
}

// IsNoiseDir 不含片名信息的目录: 画质 (4K、1080P)、版本 (日语版、粤语版)、花絮之类.
// 找片名和剧集根目录时跳过.
func IsNoiseDir(name string) bool {
	if versionDir.Matches(ktTrim(name)) {
		return true
	}
	p := ParseText(name)
	return len(p.Titles) == 0 && p.Episode == nil
}

// IsExtra 花絮、片头片尾、菜单、预告等, 不入库.
func IsExtra(fileName string, parents []string) bool {
	if extraFile.ContainsMatchIn(ktSubstringBeforeLast(fileName, '.')) {
		return true
	}
	for _, it := range parents {
		if extraDir.Matches(ktTrim(it)) {
			return true
		}
	}
	return false
}

// ParseName 对应 FilenameParser.parse.
// fileName 文件名 (含扩展名); parents 从片库根往下的目录名, 最后一个是直接父目录.
func ParseName(fileName string, parents []string) ParsedName {
	own := ParseText(ktSubstringBeforeLast(fileName, '.'))
	// 文件名里没有片名 (如 "E05.mkv" "01.mkv"), 往上找目录名
	var dirs []string
	for i := len(parents) - 1; i >= 0; i-- {
		it := parents[i]
		if SeasonOfDir(it) == nil && !IsNoiseDir(it) {
			dirs = append(dirs, it)
		}
	}
	// 没有别的目录时, "长相思 第一季" 这种季目录里也带着片名
	var dirParsed *ParsedName
	if len(dirs) > 0 {
		p := ParseText(dirs[0])
		dirParsed = &p
	} else {
		for i := len(parents) - 1; i >= 0; i-- {
			if SeasonOfDir(parents[i]) != nil {
				p := ParseText(parents[i])
				if len(p.Titles) > 0 {
					dirParsed = &p
				}
				break
			}
		}
	}
	var seasonFromDir *int
	for i := len(parents) - 1; i >= 0; i-- {
		if s := SeasonOfDir(parents[i]); s != nil {
			seasonFromDir = s
			break
		}
	}

	var titles []string
	switch {
	case len(own.Titles) > 0:
		titles = own.Titles
	case dirParsed != nil:
		titles = dirParsed.Titles
	default:
		titles = []string{}
	}
	episode := own.Episode
	season := own.Season
	if season == nil {
		season = seasonFromDir
	}
	if season == nil && dirParsed != nil {
		season = dirParsed.Season
	}
	if season == nil && episode != nil {
		season = intPtr(1)
	}
	year := own.Year
	if year == nil && dirParsed != nil {
		year = dirParsed.Year
	}
	return ParsedName{Titles: titles, Year: year, Season: season, Episode: episode}
}

// ParseText 解析一段文本 (文件名去扩展名, 或目录名).
func ParseText(raw string) ParsedName {
	// 网盘重名文件会加 "(1)" 后缀; 「」里是单集标题; 开头的 "X " 是按首字母排序加的前缀
	text := duplicateSuffix.ReplaceAll(raw, "")
	text = episodeTitle.ReplaceAll(text, " ")
	text = indexPrefix.ReplaceAll(text, "")
	text = description.ReplaceAll(text, " ")
	var season, episode, year *int

	// 1. 括号分块: [桜都字幕组][葬送的芙莉莲][12][1080P]
	var titleParts, bracketTitles []string
	for _, ch := range splitBrackets(text) {
		c := ktTrim(ch.text)
		if c == "" {
			continue
		}
		if ch.inBracket {
			switch {
			case yearOnly.Matches(c):
				if year == nil {
					year = intPtr(atoi(c))
				}
			case episodeOnly.Matches(c) && episode == nil:
				episode = intPtr(atoi(episodeOnly.MatchEntire(c).Groups[1]))
			case isTag(c) || isGroup(c) || isAd(c) || crc.Matches(c):
			default:
				bracketTitles = append(bracketTitles, c)
			}
		} else {
			titleParts = append(titleParts, c)
		}
	}
	// 括号外有正文时括号里多半是标签; 全是括号 (字幕组风格) 时括号里的文字才是片名
	anyLetter := false
	for _, part := range titleParts {
		for _, r := range part {
			if ktIsLetter(r) {
				anyLetter = true
			}
		}
	}
	if !anyLetter {
		titleParts = append(titleParts, bracketTitles...)
	}
	text = strings.Join(titleParts, " ")

	// 整段只有数字: "01.mkv"
	if m := bareNumber.MatchEntire(ktTrim(text)); m != nil {
		return ParsedName{Titles: []string{}, Episode: intPtr(atoi(m.Groups[1]))}
	}

	// 2. 季/集
	if m := sxxExx.Find(text); m != nil {
		season = intPtr(atoi(m.Groups[1]))
		episode = intPtr(atoi(m.Groups[2]))
		text = runeSub(text, 0, m.Start)
	}
	if episode == nil {
		if m := xFormat.Find(text); m != nil {
			season = intPtr(atoi(m.Groups[1]))
			episode = intPtr(atoi(m.Groups[2]))
			text = runeSub(text, 0, m.Start)
		}
	}
	if m := cnSeason.Find(text); m != nil {
		if season == nil {
			season = ParseNumber(m.Groups[1])
		}
		text = runeSub(text, 0, m.Start) + runeSub(text, m.End, -1)
	}
	if episode == nil {
		if m := cnEpisode.Find(text); m != nil {
			episode = ParseNumber(m.Groups[1])
			text = runeSub(text, 0, m.Start) + " " + runeSub(text, m.End, -1)
		}
	}
	if episode == nil {
		if m := hashEpisode.Find(text); m != nil {
			episode = intPtr(atoi(m.Groups[1]))
			text = runeSub(text, 0, m.Start)
		}
	}
	if episode == nil {
		if m := epFormat.Find(text); m != nil {
			episode = intPtr(atoi(m.Groups[1]))
			text = runeSub(text, 0, m.Start)
		}
	}
	if m := seasonWord.Find(text); m != nil {
		if season == nil {
			season = intPtr(atoi(m.Groups[1]))
		}
		text = runeSub(text, 0, m.Start) + runeSub(text, m.End, -1)
	}

	// 3. 点和下划线当空格, 去掉技术标签
	text = strings.ReplaceAll(strings.ReplaceAll(text, ".", " "), "_", " ")
	var words []string
	for _, w := range wsRe.Split(text, -1) {
		if !ktIsBlank(w) {
			words = append(words, w)
		}
	}
	for i, w := range words {
		if isTag(w) {
			words = words[:i]
			break
		}
	}

	// 年份: 取最后一个像年份的词, 并截断 (年份之后一般都是标签)
	yearIdx := -1
	for i := len(words) - 1; i >= 0; i-- {
		if yearWord.Matches(words[i]) {
			yearIdx = i
			break
		}
	}
	if yearIdx > 0 {
		if year == nil {
			year = intPtr(atoi(yearWord.MatchEntire(words[yearIdx]).Groups[1]))
		}
		words = words[:yearIdx]
	}

	// 动画 " - 12" 结尾
	if episode == nil && len(words) >= 2 {
		last := words[len(words)-1]
		prev := words[len(words)-2]
		if prev == "-" && animeEpisode.MatchString(last) {
			end := 0
			for end < len(last) && last[end] >= '0' && last[end] <= '9' {
				end++
			}
			episode = intPtr(atoi(last[:end]))
			words = words[:len(words)-2]
		}
	}

	title := strings.Trim(strings.Join(words, " "), " -–·,。")
	// 去掉标签后只剩数字: "01 4K.mp4" "178 4K.mp4"
	if episode == nil {
		if m := bareNumber.MatchEntire(title); m != nil {
			return ParsedName{Titles: []string{}, Year: year, Season: season, Episode: intPtr(atoi(m.Groups[1]))}
		}
	}
	return ParsedName{Titles: splitTitles(title), Year: year, Season: season, Episode: episode}
}

// splitTitles: "流浪地球2 The Wandering Earth II" → ["流浪地球2", "The Wandering Earth II"]
func splitTitles(title string) []string {
	if ktIsBlank(title) {
		return []string{}
	}
	hasCjk := false
	for _, r := range title {
		if isCjk(r) {
			hasCjk = true
		}
	}
	if !hasCjk {
		return []string{title}
	}
	if cn, en, ok := cjkThenLatin(title); ok {
		cn, en = ktTrim(cn), ktTrim(en)
		out := []string{}
		for _, s := range []string{cn, en} {
			if s != "" {
				out = append(out, s)
			}
		}
		return out
	}
	return []string{title}
}

// cjkThenLatin 等价于原文的 CJK_THEN_LATIN.matchEntire:
// ([^A-Za-z]*[\p{IsHan}\p{IsHiragana}\p{IsKatakana}][^A-Za-z]*?)\s*([A-Za-z].*)
// regexp2 不支持 Java 的 \p{IsHan} (按 script 分), 这里手写:
// 第 2 组只能从第一个 ASCII 字母开始 (第 1 组和 \s* 都不含字母), 且 Java 的 . 不匹配行终止符;
// 第 1 组是之前的部分 (去掉结尾 ASCII 空白, 反正调用方会 trim), 里面要有汉字/平假名/片假名 (按码点, 含 BMP 以外).
func cjkThenLatin(title string) (string, string, bool) {
	i := strings.IndexFunc(title, func(r rune) bool { return r < 0x80 && isASCIILetter(byte(r)) })
	if i < 0 {
		return "", "", false
	}
	g1, g2 := title[:i], title[i:]
	if strings.ContainsAny(g2, "\n\r\u0085\u2028\u2029") {
		return "", "", false
	}
	if !strings.ContainsFunc(g1, func(r rune) bool {
		return unicode.In(r, unicode.Han, unicode.Hiragana, unicode.Katakana)
	}) {
		return "", "", false
	}
	return strings.TrimRight(g1, " \t\n\x0B\f\r"), g2, true
}

type bracketChunk struct {
	text      string
	inBracket bool
}

func splitBrackets(text string) []bracketChunk {
	var out []bracketChunk
	var buf strings.Builder
	depth := 0
	for _, ch := range text {
		switch {
		case strings.ContainsRune(bracketOpen, ch):
			if depth == 0 && buf.Len() > 0 {
				out = append(out, bracketChunk{buf.String(), false})
				buf.Reset()
			}
			if depth > 0 {
				buf.WriteRune(ch)
			}
			depth++
		case strings.ContainsRune(bracketClose, ch) && depth > 0:
			depth--
			if depth == 0 {
				out = append(out, bracketChunk{buf.String(), true})
				buf.Reset()
			} else {
				buf.WriteRune(ch)
			}
		default:
			buf.WriteRune(ch)
		}
	}
	if buf.Len() > 0 {
		out = append(out, bracketChunk{buf.String(), depth > 0})
	}
	return out
}

func isTagWord(p string) bool {
	if tags[p] {
		return true
	}
	for _, re := range tagPatterns {
		if re.MatchString(p) {
			return true
		}
	}
	return false
}

func isTag(word string) bool {
	w := ktTrim(ktLower(word))
	if isTagWord(w) {
		return true
	}
	parts := tagSplit.Split(word, -1)
	if len(parts) <= 1 {
		return false
	}
	for _, p := range parts {
		if p != "" && !isTagWord(ktLower(p)) {
			return false
		}
	}
	return true
}

func isGroup(s string) bool {
	for _, h := range groupHints {
		if ktContainsIgnoreCase(s, h) {
			return true
		}
	}
	return false
}

func isAd(s string) bool {
	for _, h := range adHints {
		if ktContainsIgnoreCase(s, h) {
			return true
		}
	}
	return false
}

// isCjk 原文参数是 Char, BMP 以外的字符 (代理对) 的 script 是 UNKNOWN, 这里照样不算.
func isCjk(r rune) bool {
	return r <= 0xFFFF && unicode.In(r, unicode.Han, unicode.Hiragana, unicode.Katakana, unicode.Hangul)
}

var cnDigits = map[rune]int{'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}

// ParseNumber 阿拉伯数字或中文数字 (到 99).
func ParseNumber(s string) *int {
	if v, ok := ktToIntOrNull(s); ok {
		return &v
	}
	if s == "" {
		return nil
	}
	if !strings.ContainsRune(s, '十') {
		// 原文 s.length == 1 是 UTF-16 长度; BMP 以外的单字长度是 2, 但它也不在 digits 里, 结果一样是 null
		rs := []rune(s)
		if len(rs) == 1 {
			if d, ok := cnDigits[rs[0]]; ok {
				return &d
			}
		}
		return nil
	}
	tens, ones, _ := strings.Cut(s, "十")
	// 原文 single() 在多于一个字时会抛异常 (如 "一二十"), 这里返回 nil
	single := func(p string) (int, bool) {
		rs := []rune(p)
		if len(rs) != 1 {
			return 0, false
		}
		d, ok := cnDigits[rs[0]]
		return d, ok
	}
	t, o := 1, 0
	if tens != "" {
		d, ok := single(tens)
		if !ok {
			return nil
		}
		t = d
	}
	if ones != "" {
		d, ok := single(ones)
		if !ok {
			return nil
		}
		o = d
	}
	v := t*10 + o
	return &v
}

const (
	bracketOpen  = "[【(（《"
	bracketClose = "]】)）》"
	// Java 的 \s
	jws = ` \t\n\x0B\f\r`
)

var (
	wsRe            = regexp.MustCompile(`[ \t\n\x0B\f\r]+`)
	tagSplit        = regexp.MustCompile(`[ ._&+]+`)
	animeEpisode    = regexp.MustCompile(`^[0-9]{1,4}(v[0-9])?$`)
	crc             = newKtRegex(`[0-9A-Fa-f]{8}`, foldNone)
	duplicateSuffix = newKtRegex(`\([0-9]{1,2}\)(?=(?:\r\n|[\n\r\u0085\u2028\u2029])?\z)`, foldNone) // 原文 \(\d{1,2}\)$, Java 的 $ 也匹配结尾行终止符之前
	versionDir      = newKtRegex(`(日语|日文|粤语|国语|英语|原声|中配|日配)版?|(新|旧|原|导演剪辑|加长|完整|未删减)版`, foldNone)
	// 原文内联 (?i), 只对 ASCII 忽略大小写, 字母已写成小写
	extraFile = newKtRegex(
		`(?<![A-Za-z])(nc(op|ed)[0-9]*|menu[0-9]*|pv[0-9]*|cm[0-9]*|preview|trailer|sample|teaser)(?![A-Za-z])|tokuten|预告|花絮|特典|彩蛋|片头曲|片尾曲|无字幕?(op|ed)`,
		foldASCII,
	)
	// 原文内联 (?i), 只对 ASCII 忽略大小写, 字母已写成小写
	extraDir        = newKtRegex(`backdrops|theme-music|trailers|featurettes|behind the scenes|deleted scenes|interviews|scenes|shorts|sps?|extras?|cds?|scans?|menus?|bonus|特典|花絮|op|ed|op&?ed|nc(op|ed)?|pvs?|cms?|音乐|原声|ost|漫画|小说|字幕|fonts?|subs?`, foldASCII)
	bareNumber      = newKtRegex(`(?:ep?)?([0-9]{1,3})`, foldUnicode) // 原文 RegexOption.IGNORE_CASE, 字母已写成小写
	yearOnly        = newKtRegex(`(19[0-9]{2}|20[0-9]{2})`, foldNone)
	yearWord        = newKtRegex(`\(?((?:19|20)[0-9]{2})\)?`, foldNone)
	episodeOnly     = newKtRegex(`(?:ep?|第)?([0-9]{1,4})(?:v[0-9]|集|话|話)?`, foldUnicode) // 原文 RegexOption.IGNORE_CASE, 字母已写成小写
	sxxExx          = newKtRegex(`[Ss]([0-9]{1,2})[ ._-]?[Ee][Pp]?([0-9]{1,4})`, foldNone)
	xFormat         = newKtRegex(`(?<![0-9xX])([0-9]{1,2})[xX]([0-9]{2,3})(?![0-9])`, foldNone)
	epFormat        = newKtRegex(`(?<![A-Za-z])(?:ep|e)[ ._-]?([0-9]{1,4})(?![0-9])`, foldUnicode) // 原文 RegexOption.IGNORE_CASE, 字母已写成小写
	cnEpisode       = newKtRegex(`第[`+jws+`]*([0-9一二两三四五六七八九十零]+)[`+jws+`]*[集话話期]`, foldNone)
	cnSeason        = newKtRegex(`第[`+jws+`]*([0-9一二两三四五六七八九十]+)[`+jws+`]*季`, foldNone)
	seasonWord      = newKtRegex(`(?<![A-Za-z])(?:season[ ._-]?|s)([0-9]{1,2})(?![0-9])(?![EeXx])`, foldUnicode) // 原文 RegexOption.IGNORE_CASE, 字母已写成小写
	seasonDirPrefix = newKtRegex(`^(?:[0-9]{1,2}[.、 ][`+jws+`]*)?(?:[^`+jws+`]*?[`+jws+`])?第[`+jws+`]*([0-9一二两三四五六七八九十]+)[`+jws+`]*季`, foldNone)
	hashEpisode     = newKtRegex(`#([0-9]{1,4})(?![0-9])`, foldNone)
	episodeTitle    = newKtRegex(`[「『][^」』]*[」』]`, foldNone)
	indexPrefix     = newKtRegex(`^[A-Z][ _](?=[^A-Za-z ])`, foldNone)
	description     = newKtRegex(`全[`+jws+`]*[0-9]+[`+jws+`]*[集季话話部]|[0-9]+[`+jws+`]*-[`+jws+`]*[0-9]+[`+jws+`]*[集季]|附[^`+jws+`]*|[内外][封挂嵌]、?[内外]?[封挂嵌]?字幕|[0-9]+部合集|合集`, foldNone)
	seasonDir       = newKtRegex(`(?:season[ ._-]?|s)([0-9]{1,2})|第[`+jws+`]*([0-9一二两三四五六七八九十]+)[`+jws+`]*季`, foldUnicode) // 原文 RegexOption.IGNORE_CASE, 字母已写成小写
)

var tags = func() map[string]bool {
	m := map[string]bool{}
	for _, t := range []string{
		"2160p", "1080p", "1080i", "720p", "480p", "4k", "8k", "uhd", "fhd", "hd", "sd",
		"hdr", "hdr10", "hdr10+", "hdr10plus", "dv", "dovi", "sdr", "hlg", "imax",
		"web-dl", "webdl", "webrip", "web", "bluray", "blu-ray", "bdrip", "brrip", "bd", "remux", "hdtv", "dvdrip", "hdrip",
		"x264", "x265", "h264", "h265", "h.264", "h.265", "hevc", "avc", "av1", "vp9", "10bit", "8bit", "hi10p",
		"aac", "ac3", "dts", "dts-hd", "truehd", "atmos", "ddp", "dd", "flac", "eac3", "ma", "lpcm", "opus",
		"nf", "amzn", "dsnp", "hmax", "atvp", "itunes", "netflix",
		"proper", "repack", "extended", "uncut", "remastered", "complete",
		"国语", "粤语", "国粤", "中字", "中英", "双语", "简繁", "简体", "繁体", "内封", "外挂", "内嵌", "特效",
		"国英", "国英双语", "中英双字", "简繁内封", "简日双语", "高清", "超清", "蓝光", "无损", "全集", "完结",
	} {
		m[t] = true
	}
	return m
}()

// tagPatterns 原文都是 matches (整串匹配), 没有断言, 用标准库并加 ^$.
var tagPatterns = []*regexp.Regexp{
	regexp.MustCompile(`^[0-9]{3,4}[pi]$`),
	regexp.MustCompile(`^(dd|ddp|dts|aac|ac3|eac3|truehd)[0-9.+]*$`),
	regexp.MustCompile(`^[0-9]\.[0-9]$`),
	regexp.MustCompile(`^[0-9]+(\.[0-9]+)?(gb|mb)$`),
	regexp.MustCompile(`^(x|h)\.?26[45]$`),
	regexp.MustCompile(`^[0-9]+fps$`),
	regexp.MustCompile(`^[0-9]+audios?$`),
	regexp.MustCompile(`^(chs|cht|chi|big5|gb|jpn?|eng)([&_-](chs|cht|chi|big5|gb|jpn?|eng))*$`),
}

var (
	groupHints = []string{"字幕组", "字幕社", "字幕組", "raws", "-raws", "sub", "压制", "壓制", "发布", "fansub"}
	adHints    = []string{"下载", "下載", "www.", ".com", ".cn", "电影天堂", "阳光电影", "首发", "高清"}
)

// ---- 以下是复现 Kotlin/Java 语义的小工具 ----

// ktRegex 包一层 regexp2, 行为对齐 Kotlin Regex 的 find / matches / matchEntire / replace.
// fold 见文件开头的说明; 不是 foldNone 时正则里的字母要写成小写.
type ktRegex struct {
	find, full *regexp2.Regexp
	fold       ktFold
}

type ktFold int

const (
	foldNone    ktFold = iota // 区分大小写
	foldASCII                 // 内联 (?i): 只对 ASCII 忽略大小写
	foldUnicode               // RegexOption.IGNORE_CASE: CASE_INSENSITIVE | UNICODE_CASE
)

// ktMatch 的 Start、End 是 rune 下标 ([Start, End)), Groups 从原串里取, 没参与匹配的组是 "".
type ktMatch struct {
	Start, End int
	Groups     []string
}

func newKtRegex(p string, fold ktFold) *ktRegex {
	return &ktRegex{
		find: regexp2.MustCompile(p, regexp2.None),
		full: regexp2.MustCompile(`\A(?:`+p+`)\z`, regexp2.None),
		fold: fold,
	}
}

func (k *ktRegex) input(s string) string {
	if k.fold == foldNone {
		return s
	}
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'A' && r <= 'Z':
			return r + 'a' - 'A'
		case k.fold == foldASCII:
			return r
		case r == '\u0130' || r == '\u0131': // İ ı
			return 'i'
		case r == '\u017F': // ſ
			return 's'
		case r == '\u212A': // 开尔文符号 K
			return 'k'
		}
		return r
	}, s)
}

func toKtMatch(m *regexp2.Match, orig []rune) *ktMatch {
	gs := m.Groups()
	out := &ktMatch{Start: m.Index, End: m.Index + m.Length, Groups: make([]string, len(gs))}
	for i, g := range gs {
		if len(g.Captures) > 0 {
			out.Groups[i] = string(orig[g.Index : g.Index+g.Length])
		}
	}
	return out
}

// Find 对应 Regex.find: 第一个匹配.
func (k *ktRegex) Find(s string) *ktMatch {
	m, err := k.find.FindStringMatch(k.input(s))
	if err != nil || m == nil {
		return nil
	}
	return toKtMatch(m, []rune(s))
}

// ContainsMatchIn 对应 Regex.containsMatchIn.
func (k *ktRegex) ContainsMatchIn(s string) bool {
	ok, err := k.find.MatchString(k.input(s))
	return err == nil && ok
}

// MatchEntire 对应 Regex.matchEntire: 整串匹配.
func (k *ktRegex) MatchEntire(s string) *ktMatch {
	m, err := k.full.FindStringMatch(k.input(s))
	if err != nil || m == nil {
		return nil
	}
	return toKtMatch(m, []rune(s))
}

// Matches 对应 Regex.matches / String.matches: 整串匹配.
func (k *ktRegex) Matches(s string) bool {
	ok, err := k.full.MatchString(k.input(s))
	return err == nil && ok
}

// ReplaceAll 对应 String.replace(Regex, String), repl 里不能有 $ 引用.
func (k *ktRegex) ReplaceAll(s, repl string) string {
	orig := []rune(s)
	m, err := k.find.FindStringMatch(k.input(s))
	if err != nil || m == nil {
		return s
	}
	var b strings.Builder
	last := 0
	for m != nil {
		b.WriteString(string(orig[last:m.Index]))
		b.WriteString(repl)
		last = m.Index + m.Length
		if m, err = k.find.FindNextMatch(m); err != nil {
			break
		}
	}
	b.WriteString(string(orig[last:]))
	return b.String()
}

func intPtr(v int) *int { return &v }

// atoi 只用在正则抓出来的 ASCII 数字上 (对应原文的 toInt()).
func atoi(s string) int {
	v, err := strconv.Atoi(s)
	if err != nil {
		panic("atoi: " + s)
	}
	return v
}

// runeSub 按 rune 下标截取 [from, to), to < 0 表示到结尾.
func runeSub(s string, from, to int) string {
	rs := []rune(s)
	if to < 0 {
		to = len(rs)
	}
	return string(rs[from:to])
}

func isASCIILetter(c byte) bool { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' }

// ktIsLetter 对应 Char.isLetter: 参数是 UTF-16 单元, 代理对不算字母.
func ktIsLetter(r rune) bool { return r <= 0xFFFF && unicode.IsLetter(r) }

// ktIsWhitespace 对应 Kotlin/JVM 的 Char.isWhitespace: Character.isWhitespace || Character.isSpaceChar.
func ktIsWhitespace(r rune) bool {
	switch {
	case r == '\t' || r == '\n' || r == '\x0B' || r == '\f' || r == '\r':
		return true
	case r >= 0x1C && r <= 0x1F:
		return true
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

// ktTrim 对应 String.trim().
func ktTrim(s string) string { return strings.TrimFunc(s, ktIsWhitespace) }

// ktIsBlank 对应 String.isBlank().
func ktIsBlank(s string) bool { return strings.TrimFunc(s, ktIsWhitespace) == "" }

// ktLower 对应 String.lowercase() (Locale.ROOT). Java 特殊的只有 İ (U+0130) → "i\u0307" 两个字符;
// 词尾 Σ → ς 的规则没有复现 (只影响 Normalize 输出里的希腊字母, 不影响匹配).
func ktLower(s string) string {
	if strings.ContainsRune(s, 'İ') {
		s = strings.ReplaceAll(s, "İ", "i\u0307")
	}
	return strings.ToLower(s)
}

// ktSubstringBeforeLast 对应 substringBeforeLast(c): 没有时返回原串.
func ktSubstringBeforeLast(s string, c byte) string {
	if i := strings.LastIndexByte(s, c); i >= 0 {
		return s[:i]
	}
	return s
}

// ktSubstringAfterLast 对应 substringAfterLast(c, missing).
func ktSubstringAfterLast(s string, c byte, missing string) string {
	if i := strings.LastIndexByte(s, c); i >= 0 {
		return s[i+1:]
	}
	return missing
}

// ktCharEqualsIgnoreCase 对应 Char.equals(other, ignoreCase = true).
// 原文按 UTF-16 单元比, 代理对没有大小写, 所以 BMP 以外的字符只认完全相同.
func ktCharEqualsIgnoreCase(a, b rune) bool {
	if a == b {
		return true
	}
	if a > 0xFFFF || b > 0xFFFF {
		return false
	}
	au, bu := unicode.ToUpper(a), unicode.ToUpper(b)
	return au == bu || unicode.ToLower(au) == unicode.ToLower(bu)
}

// ktStartsWithIgnoreCase 对应 startsWith(prefix, ignoreCase = true).
func ktStartsWithIgnoreCase(s, prefix string) bool {
	sr, pr := []rune(s), []rune(prefix)
	if len(pr) > len(sr) {
		return false
	}
	for i, r := range pr {
		if !ktCharEqualsIgnoreCase(sr[i], r) {
			return false
		}
	}
	return true
}

// ktContainsIgnoreCase 对应 contains(other, ignoreCase = true).
func ktContainsIgnoreCase(s, sub string) bool {
	sr := []rune(s)
	for i := 0; i <= len(sr); i++ {
		if ktStartsWithIgnoreCase(string(sr[i:]), sub) {
			return true
		}
	}
	return false
}

// ktToIntOrNull 对应 String.toIntOrNull(): 可带 +/- 号, 数字按 Character.digit 认 (全角等 Unicode 数字也算,
// 但 BMP 以外的不算, 因为原文按 UTF-16 单元判断), 溢出返回 false.
func ktToIntOrNull(s string) (int, bool) {
	rs := []rune(s)
	if len(rs) == 0 {
		return 0, false
	}
	start, neg := 0, false
	if rs[0] < '0' {
		if len(rs) == 1 {
			return 0, false
		}
		start = 1
		switch rs[0] {
		case '-':
			neg = true
		case '+':
		default:
			return 0, false
		}
	}
	// 和 Kotlin 一样按负数累加, 才能表示 Int.MIN_VALUE
	const minInt32 = -1 << 31
	limit := int64(-(1<<31 - 1))
	if neg {
		limit = minInt32
	}
	var acc int64
	for _, r := range rs[start:] {
		d := ktDigit(r)
		if d < 0 {
			return 0, false
		}
		acc = acc*10 - int64(d)
		if acc < limit {
			return 0, false
		}
	}
	if neg {
		return int(acc), true
	}
	return int(-acc), true
}

// ktDigit 对应 Character.digit(ch, 10), 不是数字返回 -1.
// Unicode 的十进制数字 (Nd) 都是连续的 0~9 一组, 往前数连续的 Nd 个数就能算出数值.
func ktDigit(r rune) int {
	if r > 0xFFFF || !unicode.IsDigit(r) {
		return -1
	}
	n := 0
	for c := r - 1; c >= 0 && unicode.IsDigit(c); c-- {
		n++
	}
	return n % 10
}

// ktCompare 对应 String.compareTo: 按 UTF-16 单元比较.
func ktCompare(a, b string) int {
	ua, ub := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(ua) && i < len(ub); i++ {
		if ua[i] != ub[i] {
			return int(ua[i]) - int(ub[i])
		}
	}
	return len(ua) - len(ub)
}
