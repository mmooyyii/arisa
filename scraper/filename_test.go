package main

// 移植自 src/test/kotlin/arisa/scraper/FilenameParserTest.kt.

import (
	"reflect"
	"testing"
)

func intp(v int) *int { return &v }

func fmtInt(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}

func eqInt(a, b *int) bool { return (a == nil) == (b == nil) && (a == nil || *a == *b) }

func assertParsed(t *testing.T, label string, want, got ParsedName) {
	t.Helper()
	if want.Titles == nil {
		want.Titles = []string{}
	}
	if !reflect.DeepEqual(want.Titles, got.Titles) || !eqInt(want.Year, got.Year) ||
		!eqInt(want.Season, got.Season) || !eqInt(want.Episode, got.Episode) {
		t.Errorf("%s:\n want %q y=%v s=%v e=%v\n got  %q y=%v s=%v e=%v", label,
			want.Titles, fmtInt(want.Year), fmtInt(want.Season), fmtInt(want.Episode),
			got.Titles, fmtInt(got.Year), fmtInt(got.Season), fmtInt(got.Episode))
	}
}

func checkParse(t *testing.T, file string, titles []string, year, season, episode *int, parents ...string) {
	t.Helper()
	assertParsed(t, file, ParsedName{titles, year, season, episode}, ParseName(file, parents))
}

func TestMovies(t *testing.T) {
	checkParse(t, "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.H.265.mkv", []string{"Dune Part Two"}, intp(2024), nil, nil)
	checkParse(t, "沙丘2 (2024).mkv", []string{"沙丘2"}, intp(2024), nil, nil)
	checkParse(t, "【高清下载】流浪地球2.The.Wandering.Earth.II.2023.国语中字.mp4", []string{"流浪地球2", "The Wandering Earth II"}, intp(2023), nil, nil)
	checkParse(t, "The.Matrix.1999.1080p.BluRay.x264.mkv", []string{"The Matrix"}, intp(1999), nil, nil)
	checkParse(t, "Blade.Runner.2049.2017.1080p.mkv", []string{"Blade Runner 2049"}, intp(2017), nil, nil)
	checkParse(t, "让子弹飞.mkv", []string{"让子弹飞"}, nil, nil, nil)
}

func TestEpisodes(t *testing.T) {
	checkParse(t, "Breaking.Bad.S02E05.720p.mkv", []string{"Breaking Bad"}, nil, intp(2), intp(5))
	checkParse(t, "漫长的季节 S01E01.mkv", []string{"漫长的季节"}, nil, intp(1), intp(1))
	checkParse(t, "权游第三季第9集.mkv", []string{"权游"}, nil, intp(3), intp(9))
	checkParse(t, "[桜都字幕组][葬送的芙莉莲][Sousou no Frieren][12][1080P][简繁内封].mkv",
		[]string{"葬送的芙莉莲", "Sousou no Frieren"}, nil, intp(1), intp(12))
	checkParse(t, "[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv", []string{"Frieren"}, nil, intp(1), intp(12))
	checkParse(t, "Friends.3x07.mkv", []string{"Friends"}, nil, intp(3), intp(7))
}

func TestTitleFromDirectory(t *testing.T) {
	checkParse(t, "E05.mkv", []string{"漫长的季节"}, intp(2023), intp(1), intp(5), "剧集", "漫长的季节 (2023)", "Season 01")
	checkParse(t, "01.mkv", []string{"繁花"}, nil, intp(2), intp(1), "繁花", "第二季")
}

// 用户网盘里的真实路径.
func TestRealWorld(t *testing.T) {
	checkParse(t, "01 4K.mp4", []string{"长相思"}, intp(2023), intp(1), intp(1), "【国产剧】长相思.全2季.国语中字.4K", "长相思S01.2023", "4K")
	checkParse(t, "23.mp4", []string{"长相思"}, intp(2024), intp(2), intp(23), "【国产剧】长相思.全2季.国语中字.4K", "长相思S02.2024", "1080P")
	checkParse(t, "178 4K(1).mp4", []string{"凡人修仙传"}, nil, intp(1), intp(178), "凡人修仙传")
	checkParse(t, "01.mp4", []string{"全职猎人"}, intp(1999), intp(1), intp(1), "Q 全职猎人 4K 148集新版+旧版+粤语版+日语版+剧场版+漫画  G", "全职猎人 1999", "日语版")
	checkParse(t, "22.mp4", []string{"小市民系列"}, nil, intp(2), intp(22), "X 小市民系列 1-2季", "S02")
	checkParse(t, "Teach.You.a.Lesson.S01E01.2026.2160p.NF.WEB-DL.H265.DV.HDR.DDP5.1.Atmos.3Audio.mkv",
		[]string{"Teach You a Lesson"}, intp(2026), intp(1), intp(1),
		"铁.拳.教.育（2026）NF DV&HDR", "S01", "4K HDR")
}

func TestAnimeReleases(t *testing.T) {
	reZero := []string{"Re：從零開始的異世界生活", "05. Re：從零開始的異世界生活 第二季", "[动漫国&KNA&轻之国度字幕组][Re從零開始的異世界生活][26-50+SP26-50(第二季全集)][1080P][简体][MP4]"}
	checkParse(t, "[DMG&KNA&LKSUB][Re - Zero_kara_Hajimeru_Isekai_Seikatsu][38][1080P][GB].mp4", []string{"Re - Zero kara Hajimeru Isekai Seikatsu"}, nil, intp(2), intp(38), reZero...)
	checkParse(t, "[AI-Raws] 無職転生 #01 (BD HEVC 1920x1080 yuv444p10le FLAC)[06057AC0].mkv", []string{"無職転生"}, nil, intp(1), intp(1),
		"W 無職轉生~到了異世界就拿出真本事~ 全3季 1080P", "01.無職轉生～第一季", "[AI-Raws][无职转生～到了异世界就拿出真本事～][BDRip][1080p][HEVC-yuv444p10le FLAC MKV]")
	checkParse(t, "[DMG&RoxyLib] 無職転生 第16話「親子ケンカ」 [BDRip][AVC_AAC][1080P][CHS](B7800C0B).mp4", []string{"無職転生"}, nil, intp(1), intp(16),
		"W 無職轉生~到了異世界就拿出真本事~ 全3季 1080P", "01.無職轉生～第一季", "x")
	checkParse(t, "12.mkv", []string{"我的青春恋爱物语果然有问题"}, intp(2013), intp(3), intp(12),
		"W 我的青春恋爱物语果然有问题（2013）1-3季 附OVA+OP+ED 内封、外挂字幕 4K", "第三季（2020）全12集 内封、外挂字幕 4K（Ma10p x265 flac）")
	checkParse(t, "01.mp4", []string{"小市民系列"}, nil, intp(1), intp(1), "X 小市民系列 1-2季", "S01")
	if !IsExtra("E40第11话 IN《STAR BEAT!》.mp4", []string{"BanG Dream! ", "BanG Dream! (2017)", "backdrops"}) {
		t.Error("backdrops")
	}
}

func TestExtras(t *testing.T) {
	if !IsExtra("[VCB-Studio] Hibike! Euphonium [NCED01][Ma10p_1080p][x265_flac].mkv", nil) {
		t.Error("NCED01")
	}
	if !IsExtra("Bang Dream! [Menu01].mkv", nil) {
		t.Error("Menu01")
	}
	if !IsExtra("01.mkv", []string{"吹响吧！上低音号", "SPs"}) {
		t.Error("SPs")
	}
	if IsExtra("Breaking.Bad.S01E01.mkv", []string{"绝命毒师", "Season 01"}) {
		t.Error("Breaking Bad")
	}
	if !IsNoiseDir("4K HDR") {
		t.Error("4K HDR")
	}
	if !IsNoiseDir("日语版") {
		t.Error("日语版")
	}
	if IsNoiseDir("全职猎人 1999") {
		t.Error("全职猎人 1999")
	}
}

func TestSeasonDirs(t *testing.T) {
	for _, c := range []struct {
		name string
		want *int
	}{{"Season 01", intp(1)}, {"S2", intp(2)}, {"第三季", intp(3)}, {"Sopranos", nil}} {
		if got := SeasonOfDir(c.name); !eqInt(c.want, got) {
			t.Errorf("%s: want %v got %v", c.name, fmtInt(c.want), fmtInt(got))
		}
	}
}

func TestChineseNumbers(t *testing.T) {
	for s, want := range map[string]int{"九": 9, "十": 10, "十二": 12, "二十": 20, "二十三": 23} {
		if got := ParseNumber(s); !eqInt(&want, got) {
			t.Errorf("%s: want %d got %v", s, want, fmtInt(got))
		}
	}
}

// 以下是移植时补的边界用例, 期望值按 Kotlin (Java 正则) 的语义推出来.
func TestParseTextEdges(t *testing.T) {
	// 全角数字: Java 的 \d 不认, 所以 "Ｓ０１Ｅ０２" 不是季集, 整段当片名
	assertParsed(t, "全角季集", ParsedName{Titles: []string{"Ｓ０１Ｅ０２"}}, ParseText("Ｓ０１Ｅ０２"))
	// 全角数字的集号也不认
	assertParsed(t, "全角集号", ParsedName{Titles: []string{"繁花 第０１集"}}, ParseText("繁花 第０１集"))
	// 只有全角数字: BARE_NUMBER 不匹配, 当片名
	assertParsed(t, "全角裸数字", ParsedName{Titles: []string{"０１"}}, ParseText("０１"))
	// 全角空格: Java 的 \s 不认, 不拆词; 但 trim() 会去掉首尾的全角空格
	assertParsed(t, "全角空格", ParsedName{Titles: []string{"繁花　第二部"}}, ParseText("　繁花　第二部　"))
	// 全角空格隔开的年份: 不是一个独立的词, 不认年份
	assertParsed(t, "全角空格年份", ParsedName{Titles: []string{"Dune　2021"}}, ParseText("Dune　2021"))
	// "第 二 季" 里的 ASCII 空格算 \s
	assertParsed(t, "第 二 季", ParsedName{Titles: []string{"繁花"}, Season: intp(2), Episode: intp(3)}, ParseText("繁花 第 二 季 第3集"))
	// 大小写: SEASON_WORD 忽略大小写
	assertParsed(t, "season 2", ParsedName{Titles: []string{"Show"}, Season: intp(2)}, ParseText("Show season 2"))
	// 原文 RegexOption.IGNORE_CASE 带 UNICODE_CASE: ſ (U+017F) 算 s, 所以 "ſ01" 是第 1 季
	assertParsed(t, "ſ 算 s", ParsedName{Titles: []string{"Show"}, Season: intp(1)}, ParseText("Show ſ01"))
	// 同理 İ 算 i, 是字母, 挡住了后面 "S01" 的 (?<![A-Za-z])
	assertParsed(t, "İ 算字母", ParsedName{Titles: []string{"CHTİS01"}}, ParseText("CHTİS01"))
	// 内联 (?i) 只对 ASCII: "ſample" 不是 sample, 不算花絮
	if IsExtra("Show ſample.mkv", nil) || !IsExtra("Show SAMPLE.mkv", nil) {
		t.Error("EXTRA_FILE 的 (?i) 应只对 ASCII")
	}
	// 网盘重名后缀
	assertParsed(t, "重名后缀", ParsedName{Titles: []string{"让子弹飞"}}, ParseText("让子弹飞(1)"))
	// 首字母前缀 "X "
	assertParsed(t, "首字母前缀", ParsedName{Titles: []string{"小市民系列"}}, ParseText("X 小市民系列"))
}

func TestParseTextNoPanic(t *testing.T) {
	for _, s := range []string{
		"", " ", "　", ".", "[", "]", "【】", "((()))", "第十季", "第一二十集", "第十十集",
		"🎬 流浪地球2 🌍 2023", "🎬🎬🎬", "[🎬][01]", "𠀀𠀁 S01E01", "Show - 🎬", "😀 - 12",
		"Ｓ０１Ｅ０２", "第１集", "全１２集", "\u212A", "İSTANBUL 2020", "Show(1)\n", "x\u2028y",
	} {
		_ = ParseText(s)
		_ = ParseName(s+".mkv", []string{s, "Season 01"})
		_ = IsNoiseDir(s)
		_ = IsExtra(s, []string{s})
		_ = SeasonOfDir(s)
	}
	// 代理对里的汉字在原文 isCjk/isLetter 里都不算 (按 UTF-16 单元判断)
	assertParsed(t, "BMP 以外", ParsedName{Titles: []string{"𠀀 Abc"}}, ParseText("𠀀 Abc"))
	// emoji 不是字母, 括号外只有 emoji 时括号里的文字算片名
	assertParsed(t, "emoji", ParsedName{Titles: []string{"🎬 葬送的芙莉莲"}, Season: nil, Episode: intp(3)}, ParseText("🎬[葬送的芙莉莲][03]"))
}

func TestParseNumberEdges(t *testing.T) {
	cases := []struct {
		s    string
		want *int
	}{
		{"", nil}, {"+5", intp(5)}, {"-3", intp(-3)}, {"+", nil}, {"１２", intp(12)}, {"2147483648", nil},
		{"一二十", nil}, {"十十", nil}, {"零", intp(0)}, {"两", intp(2)}, {"十五", intp(15)}, {"𠀀", nil},
	}
	for _, c := range cases {
		if got := ParseNumber(c.s); !eqInt(c.want, got) {
			t.Errorf("%q: want %v got %v", c.s, fmtInt(c.want), fmtInt(got))
		}
	}
}
