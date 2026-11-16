package main

import (
	"bytes"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// Images 算主色调和 BlurHash, 按图片路径缓存在磁盘 (缓存文件和旧版通用).
type Images struct {
	http     *http.Client
	cacheDir string
}

type ImageAnalysis struct{ Color, BlurHash string }

func NewImages(client *http.Client, cacheDir string) *Images { return &Images{client, cacheDir} }

func (im *Images) Analyze(tmdbPath, size string) ImageAnalysis {
	if im == nil || tmdbPath == "" {
		return ImageAnalysis{}
	}
	cache := filepath.Join(im.cacheDir, sha1Hex(size+tmdbPath)+".txt")
	if data, err := os.ReadFile(cache); err == nil {
		c, b, _ := strings.Cut(string(data), "\n")
		return ImageAnalysis{c, b}
	}
	var result ImageAnalysis
	if data, err := im.Download(TmdbImage(tmdbPath, size)); err == nil && data != nil {
		if img, _, err := image.Decode(bytes.NewReader(data)); err == nil {
			result = ImageAnalysis{dominantColor(img), blurHash(img, 4, 3)}
		}
	}
	_ = os.MkdirAll(im.cacheDir, 0o755)
	_ = os.WriteFile(cache, []byte(result.Color+"\n"+result.BlurHash), 0o644)
	return result
}

// Download 下载图片, 不是 2xx 返回 nil.
func (im *Images) Download(u string) ([]byte, error) {
	resp, err := im.http.Get(u)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, nil
	}
	return io.ReadAll(resp.Body)
}

func rgbAt(img image.Image, x, y int) (int, int, int) {
	r, g, b, _ := img.At(x, y).RGBA()
	return int(r >> 8), int(g >> 8), int(b >> 8)
}

// dominantColor 主色调: 颜色量化成 4bit 一档, 按 "出现次数 × 饱和度" 取最大的一档.
// 太暗太亮的像素不参与, 结果再压暗一点, 方便做背景渐变.
func dominantColor(img image.Image) string {
	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	step := max(1, max(w, h)/64)
	buckets := map[int]*[4]float64{}
	for y := 0; y < h; y += step {
		for x := 0; x < w; x += step {
			r, g, b := rgbAt(img, bounds.Min.X+x, bounds.Min.Y+y)
			mx, mn := max(r, g, b), min(r, g, b)
			if mx < 40 || mn > 225 {
				continue
			}
			sat := 0.0
			if mx != 0 {
				sat = float64(mx-mn) / float64(mx)
			}
			key := (r>>4)<<8 | (g>>4)<<4 | b>>4
			acc := buckets[key]
			if acc == nil {
				acc = &[4]float64{}
				buckets[key] = acc
			}
			wt := 0.3 + sat
			acc[0] += float64(r) * wt
			acc[1] += float64(g) * wt
			acc[2] += float64(b) * wt
			acc[3] += wt
		}
	}
	var best *[4]float64
	for _, v := range buckets {
		if best == nil || v[3] > best[3] {
			best = v
		}
	}
	if best == nil {
		return "#202020"
	}
	const scale = 0.7
	c := func(i int) int { return int(math.Round(best[i] / best[3] * scale)) }
	return fmt.Sprintf("#%02x%02x%02x", c(0), c(1), c(2))
}

// blurHash 编码, 算法见 https://github.com/woltapp/blurhash
func blurHash(img image.Image, cx, cy int) string {
	bounds := img.Bounds()
	// 先缩小, 编码只需要低频信息
	w := min(bounds.Dx(), 64)
	h := max(1, bounds.Dy()*w/bounds.Dx())
	pix := make([][3]float64, w*h)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r, g, b := rgbAt(img, bounds.Min.X+x*bounds.Dx()/w, bounds.Min.Y+y*bounds.Dy()/h)
			pix[y*w+x] = [3]float64{srgbToLinear(r), srgbToLinear(g), srgbToLinear(b)}
		}
	}
	factors := make([][3]float64, cx*cy)
	for j := 0; j < cy; j++ {
		for i := 0; i < cx; i++ {
			norm := 2.0
			if i == 0 && j == 0 {
				norm = 1
			}
			var f [3]float64
			for y := 0; y < h; y++ {
				for x := 0; x < w; x++ {
					basis := norm * math.Cos(math.Pi*float64(i*x)/float64(w)) * math.Cos(math.Pi*float64(j*y)/float64(h))
					p := pix[y*w+x]
					f[0] += basis * p[0]
					f[1] += basis * p[1]
					f[2] += basis * p[2]
				}
			}
			s := 1.0 / float64(w*h)
			factors[j*cx+i] = [3]float64{f[0] * s, f[1] * s, f[2] * s}
		}
	}
	var sb strings.Builder
	sb.WriteString(base83((cx-1)+(cy-1)*9, 1))
	ac := factors[1:]
	maxValue := 1.0
	if len(ac) > 0 {
		actual := 0.0
		for _, f := range ac {
			for _, v := range f {
				actual = math.Max(actual, math.Abs(v))
			}
		}
		q := clamp(int(actual*166-0.5), 0, 82)
		maxValue = float64(q+1) / 166
		sb.WriteString(base83(q, 1))
	} else {
		sb.WriteString(base83(0, 1))
	}
	dc := factors[0]
	sb.WriteString(base83(linearToSrgb(dc[0])<<16+linearToSrgb(dc[1])<<8+linearToSrgb(dc[2]), 4))
	for _, f := range ac {
		q := func(v float64) int { return clamp(int(signPow(v/maxValue, 0.5)*9+9.5), 0, 18) }
		sb.WriteString(base83(q(f[0])*19*19+q(f[1])*19+q(f[2]), 2))
	}
	return sb.String()
}

const base83Chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

func base83(value, length int) string {
	var sb strings.Builder
	for i := 1; i <= length; i++ {
		digit := (value / int(math.Pow(83, float64(length-i)))) % 83
		sb.WriteByte(base83Chars[digit])
	}
	return sb.String()
}

func clamp(v, lo, hi int) int { return max(lo, min(hi, v)) }

func srgbToLinear(v int) float64 {
	c := float64(v) / 255
	if c <= 0.04045 {
		return c / 12.92
	}
	return math.Pow((c+0.055)/1.055, 2.4)
}

func linearToSrgb(v float64) int {
	c := math.Max(0, math.Min(1, v))
	if c <= 0.0031308 {
		return int(c*12.92*255 + 0.5)
	}
	return int((1.055*math.Pow(c, 1/2.4)-0.055)*255 + 0.5)
}

func signPow(v, exp float64) float64 { return math.Copysign(math.Pow(math.Abs(v), exp), v) }
