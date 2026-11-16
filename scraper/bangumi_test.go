package main

import "testing"

type fakeBangumi struct{ all []BangumiSubject }

func (f fakeBangumi) SearchAnime(string) ([]BangumiSubject, error) { return f.all, nil }
func (f fakeBangumi) Subject(id int) (*BangumiSubject, error) {
	for _, s := range f.all {
		if s.ID == id {
			return &s, nil
		}
	}
	return nil, errNotFound
}

func TestBangumiMatch(t *testing.T) {
	api := fakeBangumi{[]BangumiSubject{
		{ID: 140001, Name: "Re:ゼロから始める異世界生活", NameCn: "Re：从零开始的异世界生活", Date: "2016-04-03", Platform: "TV"},
		{ID: 278826, Name: "Re:ゼロから始める異世界生活 2nd season", NameCn: "Re：从零开始的异世界生活 第二季", Date: "2020-07-08", Platform: "TV"},
		{ID: 225462, Name: "Re:ゼロから始める異世界生活 Memory Snow", NameCn: "Re：从零开始的异世界生活 雪之回忆", Date: "2018-10-06", Platform: "剧场版"},
		{ID: 1, Name: "千と千尋の神隠し", NameCn: "千与千寻", Date: "2001-07-20", Platform: "剧场版"},
	}}
	id := func(original string, year int, movie bool) int {
		s, err := MatchBangumi(api, original, &year, movie)
		if err != nil {
			t.Fatal(err)
		}
		if s == nil {
			return 0
		}
		return s.ID
	}
	// 名字完全对上才算, 续作不会被当成第一季
	eq(t, 140001, id("Re:ゼロから始める異世界生活", 2016, false))
	eq(t, 1, id("千と千尋の神隠し", 2001, true))
	// 年份差太多不认
	eq(t, 0, id("千と千尋の神隠し", 2010, true))
	eq(t, 0, id("ゼロから", 2016, false))
}

// 片库索引: 默认值不写, 必填字段 (CompatTest 冻结的) 都在.
func TestLibraryJSONShape(t *testing.T) {
	lib := &Library{Schema: LibrarySchema, GeneratedAt: 1, Items: []MediaItem{{ID: "tv:1", Type: TypeTV, Title: "x",
		Seasons: []Season{{Number: 0, Episodes: []Episode{{Season: 0, Episode: 0}}}}}}}
	got := string(libraryJSON(lib))
	want := `{"schema":1,"generatedAt":1,"items":[{"id":"tv:1","type":"tv","title":"x","art":{},"seasons":[{"number":0,"episodes":[{"season":0,"episode":0}]}],"match":{"source":"","confidence":0}}]}`
	eq(t, want, got)
}
