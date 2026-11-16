package main

import "testing"

func TestReadNfo(t *testing.T) {
	if ReadNfo("<foo></foo>") != nil {
		t.Error("非 NFO 应返回 nil")
	}
	x := `<?xml version="1.0"?>
<movie>
  <title> Tom &amp; Jerry &lt;1&gt; </title>
  <uniqueid type="tmdb" default="true">123</uniqueid>
  <uniqueid type="imdb">tt0001</uniqueid>
</movie>`
	n := ReadNfo(x)
	if n == nil || n.Root != "movie" || n.TmdbID == nil || *n.TmdbID != 123 || n.ImdbID != "tt0001" || n.Title != "Tom & Jerry <1>" {
		t.Errorf("%+v", n)
	}
	n = ReadNfo("<tvshow>\n<tmdbid>45</tmdbid><id>tt9</id></tvshow>")
	if n == nil || n.Root != "tvshow" || *n.TmdbID != 45 || n.ImdbID != "tt9" || n.Title != "" {
		t.Errorf("%+v", n)
	}
	n = ReadNfo("<episodedetails><id>12</id><tmdbid>x</tmdbid></episodedetails>")
	if n == nil || n.TmdbID != nil || n.ImdbID != "" {
		t.Errorf("%+v", n)
	}
}
